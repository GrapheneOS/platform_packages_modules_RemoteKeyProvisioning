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
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import co.nstant.in.cbor.CborException;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.ConfirmCertificates.DerCertificateChains;
import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.InstantConverter;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpKey;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.metrics.ProvisioningAttempt;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.StatsProcessor;
import com.android.rkpdapp.utils.X509Utils;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.ProviderException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Provides an easy package to run the provisioning process from start to finish, interfacing
 * with the system interface and the server backend in order to provision attestation certificates
 * to the device.
 */
public class Provisioner {
    private static final String TAG = "RkpdProvisioner";
    private static final Object provisionKeysLock = new Object();
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX = "rkpd_post_provisioning_test_key";

    private final Context mContext;
    private final ProvisionedKeyDao mKeyDao;
    private final boolean mIsAsync;

    public Provisioner(final Context applicationContext, ProvisionedKeyDao keyDao,
            boolean isAsync) {
        mContext = applicationContext;
        mKeyDao = keyDao;
        mIsAsync = isAsync;
    }

    /**
     * Check to see if we need to perform provisioning or not for the given
     * IRemotelyProvisionedComponent.
     * @param serviceName the name of the remotely provisioned component to be provisioned
     * @return true if the remotely provisioned component requires more keys, false if the pool
     *         of available keys is healthy.
     */
    public boolean isProvisioningNeeded(ProvisioningAttempt metrics, String serviceName) {
        return calculateKeysRequired(metrics, serviceName) > 0;
    }

    /**
     * Generate, sign and store remotely provisioned keys.
     */
    public void provisionKeys(ProvisioningAttempt metrics, SystemInterface systemInterface,
            GeekResponse geekResponse) throws CborException, RkpdException, InterruptedException {
        synchronized (provisionKeysLock) {
            int keysRequired = calculateKeysRequired(metrics, systemInterface.getServiceName());
            Log.i(TAG, "Requested number of keys for provisioning: " + keysRequired);
            if (keysRequired == 0) {
                metrics.setStatus(ProvisioningAttempt.Status.NO_PROVISIONING_NEEDED);
                return;
            }

            List<RkpKey> keysGenerated = generateKeys(metrics, keysRequired, systemInterface);
            checkForInterrupts();
            List<byte[]> certChains = fetchCertificates(metrics, keysGenerated, systemInterface,
                    geekResponse);
            checkForInterrupts();
            List<ProvisionedKey> keys =
                    associateCertsWithKeys(certChains, keysGenerated, systemInterface,
                            geekResponse.requestId, metrics);

            mKeyDao.insertKeys(keys);
            generateAttestationCertificate(certChains, keys, geekResponse.requestId, metrics,
                    systemInterface);
            Log.i(TAG, "Total provisioned keys: " + keys.size());
        }

        metrics.setStatus(ProvisioningAttempt.Status.KEYS_SUCCESSFULLY_PROVISIONED);
        new ServerInterface(mContext, mIsAsync)
            .confirmCertificates(
                ConfirmCertificates.createSuccess(systemInterface.getHalInstanceName()),
                geekResponse.requestId,
                metrics);
    }

    private void generateAttestationCertificate(
            List<byte[]> certChains, List<ProvisionedKey> keys, String requestId,
            ProvisioningAttempt metrics, SystemInterface systemInterface)
            throws RkpdException, InterruptedException {
        if (!Flags.enableFeedbackLoop()) {
            return;
        }
        if (!systemInterface.getHalInstanceName().equals("default")
                && !systemInterface.getHalInstanceName().equals("strongbox")) {
            return;
        }

        String keyAlias = KEY_ALIAS_PREFIX + "_" + systemInterface.getHalInstanceName();
        KeyStore keystore;
        try {
            keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keystore.load(null);
            if (keystore.containsAlias(keyAlias)) {
                keystore.deleteEntry(keyAlias);
            }
        } catch (KeyStoreException | CertificateException |
            IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "Error loading keystore or removing existing rkpd assigned keys. " +
                    "Skipping certificate confirmation.", e);
            return;
        }

        byte[] rawPublicKey;
        try {
            Certificate[] attestationCertChain = generateAttestationCertificate(
                    keystore, keyAlias, systemInterface.getHalInstanceName());
            rawPublicKey = getRkpRawPublicKeyFromAttestationCertChain(attestationCertChain);
        } catch (Exception e) {
            if (e instanceof RkpdException && ((RkpdException) e).getErrorCode() ==
                    RkpdException.ErrorCode.TRANSIENT_ERROR) {
                Log.i(TAG, "Transient error generating attestation certificate. Skipping "
                        + "certificate confirmation for now.", e);
                return;
            }
            Log.e(TAG, "Error generating attestation certificate. Reporting to the server"
                    + " and deleting provisioned keys from this batch.", e);
            mKeyDao.deleteKeys(keys);
            new ServerInterface(mContext, mIsAsync)
                .confirmCertificatesError(
                        Optional.of(systemInterface),
                        e,
                        new DerCertificateChains(certChains),
                        requestId,
                        metrics);
            throw e;
        }
        // Successfully generated attestation certificate, unassign and make available for reuse.
        mKeyDao.UnassignPublicKey(rawPublicKey);
    }

    /**
     * Generates an attestation certificate for the given keystore and key alias. This is a
     * protected method so that it can be overridden in unit tests. The reason we need to override
     * this method in unit tests is because RKPD is "locked" for access during the test execution,
     * so keystore is unable to generate an attestation using RKPD.
     */
    protected Certificate[] generateAttestationCertificate(
            KeyStore keystore, String keyAlias, String halInstanceName) throws RkpdException {
        KeyGenParameterSpec keyGenParameterSpec =
            new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setAttestationChallenge(new byte[] {1})
                    .setIsStrongBoxBacked(halInstanceName.contains("strongbox"))
                    .build();

        try {
            // One or more of the following keystore APIs may internally make a call to RKPD to use
            // the newly provisioned key, so a failure here is indicative of bad certs received.
            KeyPairGenerator keyPairGenerator =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
            keyPairGenerator.initialize(keyGenParameterSpec);
            keyPairGenerator.generateKeyPair();
            return keystore.getCertificateChain(keyAlias);
        } catch (
            KeyStoreException | InvalidAlgorithmParameterException |
            NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Error generating attestation certificate", e);
        } catch (ProviderException e) {
            if (e.getCause() instanceof android.security.KeyStoreException kse) {
                if (kse.isTransientFailure()) {
                    throw new RkpdException(RkpdException.ErrorCode.TRANSIENT_ERROR,
                            "Transient keystore error generating attestation certificate", e);
                }
            }
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Error generating attestation certificate", e);
        }
    }

    /**
     * Accepts an attestation certificate chain and returns the raw public key corresponding to the
     * RKP certificate.
     */
    private byte[] getRkpRawPublicKeyFromAttestationCertChain(Certificate[] attestationCertChain)
            throws RkpdException {
        if (attestationCertChain == null) {
            throw new RkpdException(
                RkpdException.ErrorCode.INTERNAL_ERROR, "Attestation certificate chain is null");
        }
        X509Certificate[] x509Certificates = Arrays.stream(attestationCertChain)
                .map(x -> (X509Certificate) x)
                .toList()
                .toArray(new X509Certificate[0]);
        if (x509Certificates.length < 2) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Attestation certificate chain is too short. Expected at least 2 certificates,"
                            + " but got "
                            + x509Certificates.length);
        }

        // Keymint certificate chain is ordered from leaf-to-root. The RKP certificate is the second
        // certificate in the chain (index 1).
        return X509Utils.getAndFormatRawPublicKey(x509Certificates[1]);
    }

    private List<RkpKey> generateKeys(ProvisioningAttempt metrics, int numKeysRequired,
            SystemInterface systemInterface)
            throws CborException, RkpdException, InterruptedException {
        List<RkpKey> keyArray = new ArrayList<>(numKeysRequired);
        checkForInterrupts();
        for (long i = 0; i < numKeysRequired; i++) {
            keyArray.add(systemInterface.generateKey(metrics));
        }
        return keyArray;
    }

    private List<byte[]> fetchCertificates(ProvisioningAttempt metrics, List<RkpKey> keysGenerated,
            SystemInterface systemInterface, GeekResponse geekResponse)
            throws RkpdException, CborException, InterruptedException {
        int provisionedSoFar = 0;
        List<byte[]> certChains = new ArrayList<>(keysGenerated.size());
        int maxBatchSize;
        try {
            maxBatchSize = systemInterface.getBatchSize();
        } catch (RemoteException e) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Error getting batch size from the system", e);
        }
        while (provisionedSoFar != keysGenerated.size()) {
            int batchSize = Math.min(keysGenerated.size() - provisionedSoFar, maxBatchSize);
            certChains.addAll(batchProvision(metrics, systemInterface, geekResponse,
                    keysGenerated.subList(provisionedSoFar, batchSize + provisionedSoFar)));
            provisionedSoFar += batchSize;
        }
        return certChains;
    }

    private List<byte[]> batchProvision(ProvisioningAttempt metrics,
            SystemInterface systemInterface,
            GeekResponse response, List<RkpKey> keysGenerated)
            throws RkpdException, CborException, InterruptedException {
        int batch_size = keysGenerated.size();
        if (batch_size < 1) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Request at least 1 key to be signed. Num requested: " + batch_size);
        }
        byte[] certRequest = Flags.reportDeviceReset() ?
            systemInterface.generateCsr(metrics, response, keysGenerated, mContext) :
            systemInterface.generateCsr(metrics, response, keysGenerated);
        if (certRequest == null) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Failed to serialize payload");
        }

        Optional<SystemInterface> systemInterfaceOptional =
                Flags.enableFeedbackLoop() ? Optional.of(systemInterface) : Optional.empty();
        return new ServerInterface(mContext, mIsAsync)
                .requestSignedCertificates(
                        certRequest,
                        metrics,
                        response.requestId,
                        systemInterfaceOptional);
    }

    private List<ProvisionedKey> associateCertsWithKeys(
            List<byte[]> certChains,
            List<RkpKey> keysGenerated,
            SystemInterface systemInterface,
            String requestId,
            ProvisioningAttempt metrics)
            throws RkpdException, InterruptedException {
        List<ProvisionedKey> provisionedKeys = new ArrayList<>();
        for (byte[] chain : certChains) {
            X509Certificate[] certChain;
            try {
                certChain = X509Utils.formatX509Certs(chain);
            } catch (Exception e) {
                new ServerInterface(mContext, mIsAsync)
                        .confirmCertificatesError(
                                Optional.of(systemInterface),
                                e,
                                new DerCertificateChains(chain),
                                requestId,
                                metrics);
                throw e;
            }
            X509Certificate leafCertificate = certChain[0];
            long expirationDate = X509Utils.getExpirationTimeForCertificateChain(certChain)
                    .toInstant().toEpochMilli();
            byte[] rawPublicKey = X509Utils.getAndFormatRawPublicKey(leafCertificate);
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
    private int calculateKeysRequired(ProvisioningAttempt metrics, String serviceName) {
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

    /**
     * Clears bad attestation keys on the basis of information provided in the FetchGeek response.
     */
    public void clearBadAttestationKeys(GeekResponse resp) {
        if (resp.lastBadCertTimeStart == null || resp.lastBadCertTimeEnd == null) {
            // if there is no time sent, no need to do anything.
            return;
        }
        if (resp.lastBadCertTimeStart.equals(Settings.getLastBadCertTimeStart(mContext))
                && resp.lastBadCertTimeEnd.equals(Settings.getLastBadCertTimeEnd(mContext))) {
            // if the time is same as already stored version, no need to do anything.
            return;
        }
        // clear the attestation keys on the basis of time.
        checkAndDeleteBadKeys(resp.lastBadCertTimeStart, resp.lastBadCertTimeEnd);

        // store the time.
        Settings.setLastBadCertTimeRange(mContext, resp.lastBadCertTimeStart,
                resp.lastBadCertTimeEnd);
    }

    private void checkAndDeleteBadKeys(Instant startTime, Instant endTime) {
        try {
            List<ProvisionedKey> allKeys = mKeyDao.getAllKeys();
            for (int i = 0; i < allKeys.size(); i++) {
                ProvisionedKey key = allKeys.get(i);
                X509Certificate[] certChain = X509Utils.formatX509Certs(key.certificateChain);
                X509Certificate leafCertificate = certChain[0];
                Instant creationTime = leafCertificate.getNotBefore().toInstant()
                        .truncatedTo(ChronoUnit.MILLIS);

                if (!creationTime.isBefore(startTime) && !creationTime.isAfter(endTime)) {
                    mKeyDao.deleteKey(key.keyBlob);
                }
            }
        } catch (RkpdException ex) {
            Log.e(TAG, "Could not convert certificate chain to X509 certificates.", ex);
        }
    }
}
