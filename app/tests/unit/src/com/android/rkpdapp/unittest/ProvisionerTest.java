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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
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
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.model.Array;
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
import com.android.rkpdapp.utils.Settings;
import com.google.crypto.tink.subtle.Random;
import java.security.KeyPair;
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
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_REQUEST_ID_REUSE)
    public void testProvisionerReusesRequestIdFromGeekResponse() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 20, Duration.ofDays(1), server.getUrl());
            ProvisioningAttempt atom = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            SystemInterface mockSystem = mock(SystemInterface.class);
            doReturn(13).when(mockSystem).getBatchSize();
            doReturn(FAKE_RKP_KEY).when(mockSystem).generateKey(eq(atom));
            doReturn(new byte[1]).when(mockSystem).generateCsr(eq(atom), notNull(), notNull());

            GeekResponse geekResponse = new GeekResponse();
            geekResponse.setChallenge(new byte[1]);
            mProvisioner.provisionKeys(atom, mockSystem, geekResponse);

            assertThat(server.getCapturedParams())
                    .containsEntry("request_id", geekResponse.requestId);
        }
    }
}
