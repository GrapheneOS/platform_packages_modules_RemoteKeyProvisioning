/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.rkpdapp.provisioner;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.InstantConverter;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpKey;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.interfaces.ServiceManagerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.StatsProcessor;
import com.android.rkpdapp.utils.X509Utils;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import co.nstant.in.cbor.CborException;

/**
 * Provides an easy package to run the provisioning process from start to finish, interfacing
 * with the system interface and the server backend in order to provision attestation certificates
 * to the device.
 */
public class Provisioner {
    private static final String TAG = "RkpdProvisioner";
    private static final int SAFE_CSR_BATCH_SIZE = 20;
    private static final int FAILURE_MAXIMUM = 5;

    private final Context mContext;
    private final ProvisionedKeyDao mKeyDao;

    public Provisioner(final Context applicationContext, ProvisionedKeyDao keyDao) {
        mContext = applicationContext;
        mKeyDao = keyDao;
    }

    /**
     * Check to see if we need to perform provisioning or not for the given
     * IRemotelyProvisionedComponent.
     * @param serviceName the name of the remotely provisioned component to be provisioned
     * @return true if the remotely provisioned component requires more keys, false if the pool
     *         of available keys is healthy.
     */
    public boolean isProvisioningNeeded(ProvisionerMetrics metrics, String serviceName) {
        return calculateKeysRequired(metrics, serviceName) > 0;
    }

    /**
     * Generate, sign and store remotely provisioned keys.
     */
    public void provisionKeys(ProvisionerMetrics metrics, String serviceName,
            GeekResponse geekResponse) throws CborException, RkpdException, InterruptedException {
        try {
            ServiceManagerInterface serviceManagerInterface = new ServiceManagerInterface(
                    serviceName);
            SystemInterface systemInterface = new SystemInterface(serviceManagerInterface);

            int keysRequired = calculateKeysRequired(metrics, serviceName);
            Log.i(TAG, "Requested number of keys for provisioning: " + keysRequired);
            if (keysRequired == 0) {
                metrics.setStatus(ProvisionerMetrics.Status.NO_PROVISIONING_NEEDED);
                return;
            }

            List<RkpKey> keysGenerated = generateKeys(metrics, keysRequired, systemInterface);
            checkForInterrupts();
            List<byte[]> certChains = fetchCertificates(metrics, keysGenerated, systemInterface,
                    geekResponse);
            checkForInterrupts();
            List<ProvisionedKey> keys = associateCertsWithKeys(certChains, keysGenerated);

            mKeyDao.insertKeys(keys);
            Log.i(TAG, "Total provisioned keys: " + keys.size());
            metrics.setStatus(ProvisionerMetrics.Status.KEYS_SUCCESSFULLY_PROVISIONED);
        } catch (InterruptedException e) {
            metrics.setStatus(ProvisionerMetrics.Status.INTERRUPTED);
            throw e;
        } catch (RkpdException e) {
            if (Settings.getFailureCounter(mContext) > FAILURE_MAXIMUM) {
                Log.e(TAG, "Too many failures, resetting defaults.");
                Settings.resetDefaultConfig(mContext);
            }
            // Rethrow to provide failure signal to caller
            throw e;
        }
    }

    private List<RkpKey> generateKeys(ProvisionerMetrics metrics, int numKeysRequired,
            SystemInterface systemInterface)
            throws CborException, RkpdException, InterruptedException {
        List<RkpKey> keyArray = new ArrayList<>(numKeysRequired);
        checkForInterrupts();
        for (long i = 0; i < numKeysRequired; i++) {
            keyArray.add(systemInterface.generateKey(metrics));
        }
        return keyArray;
    }

    private List<byte[]> fetchCertificates(ProvisionerMetrics metrics, List<RkpKey> keysGenerated,
            SystemInterface systemInterface, GeekResponse geekResponse)
            throws RkpdException, CborException {
        int provisionedSoFar = 0;
        List<byte[]> certChains = new ArrayList<>(keysGenerated.size());
        while (provisionedSoFar != keysGenerated.size()) {
            int batch_size = Math.min(keysGenerated.size() - provisionedSoFar, SAFE_CSR_BATCH_SIZE);
            certChains.addAll(batchProvision(metrics, systemInterface, geekResponse,
                    keysGenerated.subList(provisionedSoFar, batch_size + provisionedSoFar)));
            provisionedSoFar += batch_size;
        }
        return certChains;
    }

    private List<byte[]> batchProvision(ProvisionerMetrics metrics, SystemInterface systemInterface,
            GeekResponse response, List<RkpKey> keysGenerated)
            throws RkpdException, CborException {
        int batch_size = keysGenerated.size();
        if (batch_size < 1) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Request at least 1 key to be signed. Num requested: " + batch_size);
        }
        byte[] certRequest = systemInterface.generateCsr(metrics, response, keysGenerated);
        if (certRequest == null) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Failed to serialize payload");
        }
        return new ServerInterface(mContext).requestSignedCertificates(certRequest,
                response.getChallenge(), metrics);
    }

    private List<ProvisionedKey> associateCertsWithKeys(List<byte[]> certChains,
            List<RkpKey> keysGenerated) throws RkpdException {
        List<ProvisionedKey> provisionedKeys = new ArrayList<>();
        for (byte[] chain : certChains) {
            X509Certificate cert;
            try {
                cert = X509Utils.formatX509Certs(chain)[0];
            } catch (CertificateException | NoSuchAlgorithmException | NoSuchProviderException
                    | InvalidAlgorithmParameterException e) {
                Log.e(TAG, "Unable to parse certificate chain."
                        + Base64.encodeToString(chain, Base64.DEFAULT), e);
                throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                        "Failed to interpret DER encoded certificate chain", e);
            }
            long expirationDate = cert.getNotAfter().getTime();
            byte[] rawPublicKey = X509Utils.getAndFormatRawPublicKey(cert);
            if (rawPublicKey == null) {
                Log.e(TAG, "Skipping malformed public key.");
                continue;
            }
            for (RkpKey key : keysGenerated) {
                if (Arrays.equals(key.getPublicKey(), rawPublicKey)) {
                    provisionedKeys.add(key.generateProvisionedKey(chain,
                            InstantConverter.fromTimestamp(expirationDate)));
                    keysGenerated.remove(key);
                    break;
                }
            }
        }
        return provisionedKeys;
    }

    /**
     * Calculate the number of keys to be provisioned.
     */
    private int calculateKeysRequired(ProvisionerMetrics metrics, String serviceName) {
        int numExtraAttestationKeys = Settings.getExtraSignedKeysAvailable(mContext);
        Instant expirationTime = Settings.getExpirationTime(mContext);
        StatsProcessor.PoolStats poolStats = StatsProcessor.processPool(mKeyDao, serviceName,
                numExtraAttestationKeys, expirationTime);
        metrics.setIsKeyPoolEmpty(poolStats.keysUnassigned == 0);
        return poolStats.keysToGenerate;
    }

    private void checkForInterrupts() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }
}
