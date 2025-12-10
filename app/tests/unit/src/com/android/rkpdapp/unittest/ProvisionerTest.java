/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.rkpdapp.unittest;

import static com.android.rkpdapp.unittest.Utils.generateEcdsaKeyPair;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpKey;
import com.android.rkpdapp.database.RkpdDatabase;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.metrics.ProvisioningAttempt;
import com.android.rkpdapp.provisioner.Provisioner;
import com.android.rkpdapp.testutil.FakeRkpServer;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.X509Utils;
import com.google.crypto.tink.subtle.Random;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProvisionerTest {
    private static final byte[] FAKE_RKP_KEY_BLOB_1 = Random.randBytes(10);
    private static final byte[] FAKE_RKP_KEY_BLOB_2 = Random.randBytes(10);
    private static final byte[] FAKE_RKP_KEY_BLOB_3 = Random.randBytes(10);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static final RkpKey FAKE_RKP_KEY = new RkpKey(FAKE_RKP_KEY_BLOB_1, new byte[2],
            new Array(), "hal", new byte[3]);

    // The RKP certificate chains returned by the fake RKP server.
    private static final KeyPair ROOT_KEY;
    private static final KeyPair TEST_KEY_1;
    private static final KeyPair TEST_KEY_2;
    private static final X509Certificate ROOT_CERT;
    private static final X509Certificate TEST_CERT_1; // RKP cert signed by ROOT_KEY.
    private static final X509Certificate TEST_CERT_2; // Another RKP cert signed by ROOT_KEY.
    private static final byte[] RAW_PUBLIC_KEY1;

    static {
        try {
            TEST_KEY_1 = generateEcdsaKeyPair();
            TEST_KEY_2 = generateEcdsaKeyPair();
            ROOT_KEY = generateEcdsaKeyPair();
            ROOT_CERT = Utils.signPublicKey(ROOT_KEY, ROOT_KEY.getPublic());
            TEST_CERT_1 = Utils.signPublicKey(ROOT_KEY, TEST_KEY_1.getPublic());
            TEST_CERT_2 = Utils.signPublicKey(ROOT_KEY, TEST_KEY_2.getPublic());
            RAW_PUBLIC_KEY1 = X509Utils.getAndFormatRawPublicKey(TEST_CERT_1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Context sContext;
    private Provisioner mProvisioner;
    private ProvisionedKeyDao mKeyDao;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @BeforeClass
    public static void init() {
        sContext = ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Settings.clearPreferences(sContext);

        mKeyDao = RkpdDatabase.getDatabase(sContext).provisionedKeyDao();
        mKeyDao.deleteAllKeys();

        mProvisioner = new Provisioner(sContext, mKeyDao, false);
    }

    @After
    public void tearDown() {
        Settings.clearPreferences(sContext);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testProvisionerUsesCorrectBatchSize() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 20, Duration.ofDays(1), server.getUrl());
            final int batchSize = 13;
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(batchSize).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("test-irpc").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);
            mProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            verify(mockSystem).generateCsr(any(), any(),
                    argThat(keysGenerated -> keysGenerated.size() == 13));
            verify(mockSystem).generateCsr(any(), any(),
                    argThat(keysGenerated -> keysGenerated.size() == 7));
        }
    }

    @Test
    public void testProvisionerHandlesExceptionOnGetBatchSize() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 20, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doThrow(new RemoteException()).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);

            assertThrows(RkpdException.class, () ->
                    mProvisioner.provisionKeys(atom, mockSystem, geekResponse));
        }
    }

    private byte[] generateCertificateChain(Instant rootCreationTime, Instant leafCreationTime)
            throws Exception {
        KeyPair rootKey = Utils.generateEcdsaKeyPair();
        KeyPair leafKey = Utils.generateEcdsaKeyPair();
        // Just so that we don't get expired certificates by default.
        Instant expirationTime = NOW.plus(Duration.ofDays(1));
        byte[] rootCertEncoded = Utils.signPublicKey(rootKey, rootKey.getPublic(), rootCreationTime,
                expirationTime).getEncoded();
        byte[] leafCertEncoded = Utils.signPublicKey(rootKey, leafKey.getPublic(), leafCreationTime,
                expirationTime).getEncoded();

        byte[] encodedCertChain = new byte[leafCertEncoded.length + rootCertEncoded.length];
        System.arraycopy(leafCertEncoded, 0, encodedCertChain, 0, leafCertEncoded.length);
        System.arraycopy(rootCertEncoded, 0, encodedCertChain, leafCertEncoded.length,
                rootCertEncoded.length);
        return encodedCertChain;
    }

    private void setUpClearAttestationKeyTests(Instant failureStart, Instant failureEnd)
            throws Exception {
        Instant expiration = NOW.plus(Duration.ofDays(1));
        Instant rootCreationTime = failureStart.minus(Duration.ofDays(10));

        // add a fake key to the database with certificate time that is in the bad cert range.
        ProvisionedKey keyBeforeFailure = new ProvisionedKey(
                FAKE_RKP_KEY_BLOB_1,
                "fakeHal1",
                new byte[0],
                generateCertificateChain(rootCreationTime, failureStart.minus(Duration.ofDays(1))),
                expiration);
        ProvisionedKey keyBadCert = new ProvisionedKey(
                FAKE_RKP_KEY_BLOB_2,
                "fakeHal2",
                new byte[0],
                generateCertificateChain(rootCreationTime, failureStart.plus(Duration.ofHours(1))),
                expiration);
        ProvisionedKey keyAfterFailure = new ProvisionedKey(
                FAKE_RKP_KEY_BLOB_3,
                "fakeHal3",
                new byte[0],
                generateCertificateChain(rootCreationTime, failureEnd.plus(Duration.ofDays(1))),
                expiration);
        mKeyDao.insertKeys(List.of(keyBeforeFailure, keyBadCert, keyAfterFailure));
    }

    @Test
    public void testProvisionerClearsAttestationKeysOnResponse() throws Exception {
        Instant failureTimeStart = NOW.minus(Duration.ofDays(5));
        Instant failureTimeEnd = NOW.minus(Duration.ofDays(2));

        setUpClearAttestationKeyTests(failureTimeStart, failureTimeEnd);

        assertThat(mKeyDao.getAllKeys()).hasSize(3);

        GeekResponse resp = new GeekResponse();
        resp.lastBadCertTimeStart = failureTimeStart;
        resp.lastBadCertTimeEnd = failureTimeEnd;

        mProvisioner.clearBadAttestationKeys(resp);

        assertThat(mKeyDao.getAllKeys()).hasSize(2);
    }

    @Test
    public void testProvisionerClearsAttestationKeysOnlyOnce() throws Exception {
        Instant failureTimeStart = NOW.minus(Duration.ofDays(5));
        Instant failureTimeEnd = NOW.minus(Duration.ofDays(2));

        setUpClearAttestationKeyTests(failureTimeStart, failureTimeEnd);

        assertThat(mKeyDao.getAllKeys()).hasSize(3);

        GeekResponse resp = new GeekResponse();
        resp.lastBadCertTimeStart = failureTimeStart;
        resp.lastBadCertTimeEnd = failureTimeEnd;
        Settings.setLastBadCertTimeRange(sContext, failureTimeStart, failureTimeEnd);

        mProvisioner.clearBadAttestationKeys(resp);

        assertThat(mKeyDao.getAllKeys()).hasSize(3);
    }

    @Test
    @RequiresFlagsEnabled(
            value = {Flags.FLAG_ENABLE_FEEDBACK_LOOP, Flags.FLAG_ENABLE_REQUEST_ID_REUSE})
    public void testProvisionerReusesRequestIdFromGeekResponse() throws Exception {
        // This is how the server would encode the response. The shared chain is the root,
        // and the unique chains are the leaf certs.
        Array cborCertChains =
                new Array()
                        .add(new ByteString(ROOT_CERT.getEncoded())) // shared chain
                        .add(
                                new Array() // unique chains
                                        .add(new ByteString(TEST_CERT_1.getEncoded()))
                                        .add(new ByteString(TEST_CERT_2.getEncoded())));
        String base64Encoded =
                Base64.encodeToString(CborUtils.encodeCbor(cborCertChains), Base64.DEFAULT);
        FakeRkpServer.Response signCertsResponse = new FakeRkpServer.Response(base64Encoded);

        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        signCertsResponse,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            // Need to provision 2 keys to match the 2 certs from the server
            Settings.setDeviceConfig(sContext, 2, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(13).when(mockSystem).getBatchSize();

            RkpKey rkpKey1 = new RkpKey(FAKE_RKP_KEY_BLOB_1, new byte[0], null, "hal",
                    RAW_PUBLIC_KEY1);
            doReturn(rkpKey1).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("default").when(mockSystem).getHalInstanceName();


            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);

            X509Certificate attestationLeafCert = Utils.signPublicKey(
                    TEST_KEY_1, generateEcdsaKeyPair().getPublic());
            Provisioner testProvisioner = new Provisioner(sContext, mKeyDao, false) {
                @Override
                protected Certificate[] generateAttestationCertificate(
                        KeyStore keystore, String keyAlias, String halInstanceName)
                        throws RkpdException {
                    return new Certificate[] { attestationLeafCert, TEST_CERT_1, ROOT_CERT };
                }
            };
            testProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            assertThat(server.getCapturedParams())
                    .containsEntry("request_id", geekResponse.requestId);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testProvisionerFailedSignedCertsX509ParingTriggersConfirmCertificates()
            throws Exception {
        // Create a signed certificate chain that is in the correct CBOR format expected by the
        // client, but fails to parse as a valid X509 certificate chain.
        Array certChains =
                new Array()
                        .add(new ByteString(new byte[1])) // shared chain
                        .add(
                                new Array() // unique chains
                                        .add(new ByteString(new byte[1]))
                                        .add(new ByteString(new byte[1])));
        String base64Encoded =
                Base64.encodeToString(CborUtils.encodeCbor(certChains), Base64.DEFAULT);
        FakeRkpServer.Response signCertsResponse = new FakeRkpServer.Response(base64Encoded);

        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        signCertsResponse,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 20, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(13).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("strongbox").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);

            RkpdException e =
                    assertThrows(
                            RkpdException.class,
                            () -> mProvisioner.provisionKeys(atom, mockSystem, geekResponse));
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.INTERNAL_ERROR);
            assertThat(e).hasMessageThat().contains("Certificate chain is empty");

            // Verify that confirmCertificates was called and device config was reset since we sent
            // an error instance of ConfirmCertificates.
            assertThat(server.getCapturedUri()).contains(":confirmCertificates");
            assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
            assertThat(Settings.getExpiringBy(sContext))
                    .isEqualTo(Duration.ofMillis(Settings.EXPIRING_BY_MS_DEFAULT));
            assertThat(Settings.getExtraSignedKeysAvailable(sContext))
                    .isEqualTo(Settings.EXTRA_SIGNED_KEYS_AVAILABLE_DEFAULT);
        }
    }

    @Test
    @RequiresFlagsEnabled(
            value = {Flags.FLAG_ENABLE_FEEDBACK_LOOP, Flags.FLAG_ENABLE_REQUEST_ID_REUSE})
    public void testProvisionerSuccessfulProvisioningTriggersConfirmCertificates()
            throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 1, Duration.ofDays(1), server.getUrl());
            String initialUrl = Settings.getUrl(sContext);

            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(13).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("default").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);
            X509Certificate attestationLeafCert = Utils.signPublicKey(
                    TEST_KEY_1, generateEcdsaKeyPair().getPublic());
            Provisioner testProvisioner = new Provisioner(sContext, mKeyDao, false) {
                @Override
                protected Certificate[] generateAttestationCertificate(
                        KeyStore keystore, String keyAlias, String halInstanceName) {
                    return new Certificate[] { attestationLeafCert, TEST_CERT_1, ROOT_CERT };
                }
            };
            testProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            assertThat(server.getCapturedUri()).contains(":confirmCertificates");
            assertThat(server.getCapturedParams())
                    .containsEntry("request_id", geekResponse.requestId);

            // Verify that the URL was NOT reset for a success instance.
            assertThat(server.getUrl()).isEqualTo(initialUrl);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testGenerateAttestationCertificateFailureTriggersConfirmCertificatesAndDeletesKeys()
            throws Exception {
        Array cborCertChains =
                new Array()
                        .add(new ByteString(ROOT_CERT.getEncoded())) // shared chain
                        .add(
                                new Array() // unique chains
                                        .add(new ByteString(TEST_CERT_1.getEncoded())));
        String base64Encoded =
                Base64.encodeToString(CborUtils.encodeCbor(cborCertChains), Base64.DEFAULT);
        FakeRkpServer.Response signCertsResponse = new FakeRkpServer.Response(base64Encoded);
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        signCertsResponse,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 1, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(1).when(mockSystem).getBatchSize();
            RkpKey rkpKey = new RkpKey(FAKE_RKP_KEY_BLOB_1, new byte[0], null, "hal",
                    RAW_PUBLIC_KEY1);
            doReturn(rkpKey).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("strongbox").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);

            Provisioner testProvisioner =
                    new Provisioner(sContext, mKeyDao, false) {
                        @Override
                        protected Certificate[] generateAttestationCertificate(
                                KeyStore keystore, String keyAlias,
                                String halInstanceName)
                                throws RkpdException {
                            // After inserting keys, we want to simulate a failure to check that
                            // the keys are rolled back.
                            assertThat(mKeyDao.getAllKeys()).isNotEmpty();
                            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                                    "Error generating attestation certificate",
                                    new InvalidAlgorithmParameterException("test exception"));
                        }
                    };

            RkpdException e =
                    assertThrows(
                            RkpdException.class,
                            () -> testProvisioner.provisionKeys(atom, mockSystem, geekResponse));

            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.INTERNAL_ERROR);
            assertThat(e).hasMessageThat().contains("Error generating attestation certificate");
            // Confirm that confirmCertificates was called.
            assertThat(server.getCapturedUri()).contains(":confirmCertificates");
            // Verify that the keys were deleted.
            assertThat(mKeyDao.getAllKeys()).isEmpty();
        }
    }


    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testProvisionKeysHalNotStrongBoxOrDefaultDoesNotTriggerConfirmCertificates()
            throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 1, Duration.ofDays(1), server.getUrl());
            String initialUrl = Settings.getUrl(sContext);

            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(13).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("some-other-hal").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);
            Provisioner testProvisioner = new Provisioner(sContext, mKeyDao, false) {
                @Override
                protected Certificate[] generateAttestationCertificate(
                        KeyStore keystore, String keyAlias, String halInstanceName)
                        throws RkpdException {
                    fail("generateAttestationCertificate should not have been called for HAL "
                            + halInstanceName);
                    return null;
                }
            };
            testProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            assertThat(server.getCapturedUri()).contains(":confirmCertificates");
            // Verify that the URL was NOT reset for a success instance.
            assertThat(server.getUrl()).isEqualTo(initialUrl);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testProvisionKeysConfirmCertificatesUnassignsKeyUsedForGeneratingAttestationCert()
            throws Exception {
        Array cborCertChains =
                new Array()
                        .add(new ByteString(ROOT_CERT.getEncoded())) // shared chain
                        .add(
                                new Array() // unique chains
                                        .add(new ByteString(TEST_CERT_1.getEncoded())));
        String base64Encoded =
                Base64.encodeToString(CborUtils.encodeCbor(cborCertChains), Base64.DEFAULT);
        FakeRkpServer.Response signCertsResponse = new FakeRkpServer.Response(base64Encoded);
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        signCertsResponse,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 1, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(1).when(mockSystem).getBatchSize();
            RkpKey rkpKey = new RkpKey(FAKE_RKP_KEY_BLOB_1, new byte[0], null, "default",
                    RAW_PUBLIC_KEY1);
            doReturn(rkpKey).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("default").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);

            X509Certificate attestationLeafCert = Utils.signPublicKey(
                    TEST_KEY_1, generateEcdsaKeyPair().getPublic());
            Provisioner testProvisioner =
                    new Provisioner(sContext, mKeyDao, false) {
                        @Override
                        protected Certificate[] generateAttestationCertificate(
                                KeyStore keystore, String keyAlias,
                                String halInstanceName)
                                throws RkpdException {
                            // Before generating the attestation certificate, assign the key to a
                            // fake client. This is to simulate the key being used by a client.
                            ProvisionedKey key = mKeyDao.getOrAssignKey(
                                    halInstanceName, Instant.now(), 123, 456);
                            assertThat(key).isNotNull();
                            assertThat(key.clientUid).isEqualTo(123);
                            assertThat(key.keyId).isEqualTo(456);

                            // Now, generate the attestation certificate. The second certificate
                            // in the chain is the RKP cert, which should be the same as the
                            // key that was just assigned.
                            return new Certificate[] {
                                    attestationLeafCert, TEST_CERT_1, ROOT_CERT };
                        }
                    };

            testProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            // After provisioning, the key should be unassigned.
            List<ProvisionedKey> keys = mKeyDao.getAllKeys();
            assertThat(keys).hasSize(1);
            assertThat(keys.get(0).clientUid).isNull();
            assertThat(keys.get(0).keyId).isNull();
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testGenerateAttestationCertificateNotCalledWhenFlagIsDisabled() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(sContext, 1, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(1).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());
            doReturn("strongbox").when(mockSystem).getHalInstanceName();

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);
            Provisioner testProvisioner = new Provisioner(sContext, mKeyDao, false) {
                @Override
                protected Certificate[] generateAttestationCertificate(
                        KeyStore keystore, String keyAlias, String halInstanceName) {
                    fail("generateAttestationCertificate should not be called when feedback loop"
                            + " is disabled.");
                    return null;
                }
            };

            // This should succeed without calling the failing method above.
            testProvisioner.provisionKeys(atom, mockSystem, geekResponse);
        }
    }
}
