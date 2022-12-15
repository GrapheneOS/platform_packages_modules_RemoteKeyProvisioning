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
import android.os.RemoteException;
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

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
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
    private static final Duration KEY_GENERATION_PAUSE = Duration.ofMillis(1000);

    private final Context mContext;
    private final ProvisionedKeyDao mKeyDao;

    public Provisioner(final Context applicationContext, ProvisionedKeyDao keyDao) {
        mContext = applicationContext;
        mKeyDao = keyDao;
    }

    /**
     * Generate, sign and store remotely provisioned keys.
     */
    public void provisionKeys(ProvisionerMetrics metrics, String serviceName,
            GeekResponse geekResponse)
            throws CborException, RemoteException, RkpdException, InterruptedException {
        ServiceManagerInterface serviceManagerInterface = new ServiceManagerInterface(serviceName);
        SystemInterface systemInterface = new SystemInterface(serviceManagerInterface);

        int keysRequired = calculateKeysRequired(serviceName);
        Log.i(TAG, "Requested number of keys for provisioning: " + keysRequired);
        if (keysRequired == 0) {
            return;
        }

        List<RkpKey> keysGenerated = generateKeys(metrics, keysRequired, systemInterface);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        List<byte[]> certChains = fetchCertificates(metrics, keysGenerated, systemInterface,
                geekResponse);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        List<ProvisionedKey> keys = associateCertsWithKeys(certChains, keysGenerated);

        mKeyDao.insertKeys(keys);
        Log.i(TAG, "Total provisioned keys: " + keys.size());
    }

    private List<RkpKey> generateKeys(ProvisionerMetrics metrics, int numKeysRequired,
            SystemInterface systemInterface)
            throws CborException, RkpdException, InterruptedException {
        List<RkpKey> keyArray = new ArrayList<>(numKeysRequired);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
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
            throw new RkpdException(RkpdException.Status.INTERNAL_ERROR,
                    "Request at least 1 key to be signed. Num requested: " + batch_size);
        }
        byte[] certRequest = systemInterface.generateCsr(metrics, response, keysGenerated);
        if (certRequest == null) {
            throw new RkpdException(RkpdException.Status.INTERNAL_ERROR,
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
            } catch (CertificateException e) {
                Log.e(TAG, "Unable to parse certificate chain."
                        + Base64.encodeToString(chain, Base64.DEFAULT), e);
                throw new RkpdException(RkpdException.Status.INTERNAL_ERROR,
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
    private int calculateKeysRequired(String serviceName) {
        int numExtraAttestationKeys = Settings.getExtraSignedKeysAvailable(mContext);
        Instant expirationTime = Settings.getExpirationTime(mContext);
        StatsProcessor.PoolStats poolStats = StatsProcessor.processPool(mKeyDao, serviceName,
                numExtraAttestationKeys, expirationTime);
        return poolStats.keysToGenerate;
    }
}
