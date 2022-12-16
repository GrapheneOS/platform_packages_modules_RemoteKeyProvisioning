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

package com.android.rkpdapp.unittest;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.answer;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.IGetKeyCallback;
import com.android.rkpdapp.IStoreUpgradedKeyCallback;
import com.android.rkpdapp.RemotelyProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.provisioner.Provisioner;
import com.android.rkpdapp.service.RegistrationBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RegistrationBinderTest {
    private static final Random RAND = new Random();
    private static final int CLIENT_UID = RAND.nextInt();
    private static final int KEY_ID = RAND.nextInt();
    private static final byte[] KEY_BLOB = randBytes();
    private static final byte[] PUBKEY = randBytes();
    private static final byte[] CERT_CHAIN = randBytes();
    private static final String IRPC_HAL = "fake remotely provisioned component";
    private static final ProvisionedKey FAKE_KEY = new ProvisionedKey(KEY_BLOB, IRPC_HAL, PUBKEY,
            CERT_CHAIN, Instant.now().plusSeconds(60));
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(2);

    private ProvisionedKeyDao mMockDao;
    private ServerInterface mRkpServer;
    private Provisioner mMockProvisioner;
    private RegistrationBinder mRegistration;

    private static byte[] randBytes() {
        byte[] bytes = new byte[RAND.nextInt(1024)];
        RAND.nextBytes(bytes);
        return bytes;
    }

    private RemotelyProvisionedKey matches(ProvisionedKey expected) {
        return argThat((RemotelyProvisionedKey key) ->
                Arrays.equals(key.keyBlob, expected.keyBlob)
                        && Arrays.equals(key.encodedCertChain, expected.certificateChain)
        );
    }

    @Before
    public void setUp() {
        mMockDao = mock(ProvisionedKeyDao.class);
        mRkpServer = mock(ServerInterface.class);
        mMockProvisioner = mock(Provisioner.class);
        mRegistration = new RegistrationBinder(mock(Context.class),
                CLIENT_UID, IRPC_HAL, mMockDao, mRkpServer, mMockProvisioner);
    }

    @Test
    public void getKeyReturnsAlreadyAssignedKey() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyAssignsAvailableKey() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyProvisionsKeysWhenEmpty() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        // The first call to assignKeys returns null, indicating that the provisioner needs to run,
        // then the second call returns a key, which signifies provision success.
        doReturn(null, FAKE_KEY)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        final GeekResponse fakeGeekResponse = new GeekResponse();
        doReturn(fakeGeekResponse)
                .when(mRkpServer)
                .fetchGeek(any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));
        verify(callback).onProvisioningNeeded();
        verify(mMockProvisioner).provisionKeys(any(), eq(IRPC_HAL), same(fakeGeekResponse));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesProvisioningFailure() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(null)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);
        doThrow(new RuntimeException("PROVISIONING FAIL"))
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onError("PROVISIONING FAIL");
        verify(callback).onProvisioningNeeded();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyNoKeysAreProvisioned() throws Exception {
        // This test ensures that getKey will handle the case in which provisioner doesn't error
        // out, but it also does not actually provision any keys. This shouldn't ever happen.
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(null)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis()))
                .onError("Provisioning failed, no keys available");
        verify(callback).onProvisioningNeeded();
        verify(mMockProvisioner).provisionKeys(any(), eq(IRPC_HAL), any());
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyKicksOffBackgroundProvisioningWhenNeeded() throws Exception {
        // TODO(b/262253838) - Implement this once we have WorkManager integration
    }

    @Test
    public void getKeyDoesNotKickOffBackgroundProvisioningWhenNotNeeded() throws Exception {
        // TODO(b/262253838) - Implement this once we have WorkManager integration
    }

    @Test
    public void getKeyHandlesCancelBeforeProvisioning() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doAnswer(
                answer((hal, uid, keyId) -> {
                    mRegistration.cancelGetKey(callback);
                    return null;
                }))
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);
        mRegistration.getKey(KEY_ID, callback);

        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onCancel();
        verifyNoMoreInteractions(mMockProvisioner);
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesCancelWhileProvisioning() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(null)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doAnswer(answerVoid((hal, dao, metrics) -> mRegistration.cancelGetKey(callback)))
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());
        mRegistration.getKey(KEY_ID, callback);

        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onCancel();
        verify(callback).onProvisioningNeeded();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesCancelOfInvalidCallback() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.cancelGetKey(callback);
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesInterruptedException() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(null)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doThrow(new InterruptedException())
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());
        mRegistration.getKey(KEY_ID, callback);

        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onCancel();
        verify(callback).onProvisioningNeeded();
        verifyNoMoreInteractions(callback);
    }

    //////////////////////////////////////////////////////////////////////////
    // Start of tests that verify reentrancy.
    @Test
    public void reentrantGetKeyRejectsDuplicateCallbacks() throws Exception {
        CountDownLatch getKeyBlocker = new CountDownLatch(1);
        doAnswer(
                answer((service, client, keyId) -> {
                    getKeyBlocker.await(MAX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    return FAKE_KEY;
                }))
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        assertThrows(IllegalArgumentException.class, () -> mRegistration.getKey(KEY_ID, callback));
        getKeyBlocker.countDown();
    }

    @Test
    public void reentrantGetKeyHandlesMultipleCallbacksSimultaneously() throws Exception {
        doReturn(null)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);
        doReturn(null)
                .when(mMockDao)
                .assignKey(IRPC_HAL, CLIENT_UID, KEY_ID);

        CountDownLatch getKeyEnteredTwice = new CountDownLatch(2);
        CountDownLatch getKeyBlocker = new CountDownLatch(1);
        doAnswer(
                answer((service, client, keyId) -> {
                    getKeyEnteredTwice.countDown();
                    getKeyBlocker.await(MAX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    return FAKE_KEY;
                }))
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback successfulCallback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, successfulCallback);

        IGetKeyCallback cancelMe = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, cancelMe);

        assertThat(getKeyEnteredTwice.await(MAX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        mRegistration.cancelGetKey(cancelMe);
        getKeyBlocker.countDown();

        verify(successfulCallback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(successfulCallback);

        verify(cancelMe, timeout(MAX_TIMEOUT.toMillis())).onCancel();
        verifyNoMoreInteractions(cancelMe);
    }

    @Test
    public void storeUpgradedKeySuccess() throws Exception {
        final byte[] oldKeyBlob = { 8, 6, 7, 5, 3, 0, 9};
        final byte[] newKeyBlob = { 3, 1, 4, 1, 5, 9};

        doReturn(1)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKey(oldKeyBlob, newKeyBlob, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyKeyNotFound() throws Exception {
        final byte[] oldKeyBlob = { 42 };
        final byte[] newKeyBlob = { 123 };

        doReturn(0)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKey(oldKeyBlob, newKeyBlob, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onError(contains("No keys"));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyInternalError() throws Exception {
        final byte[] oldKeyBlob = { 1, 2, 3, 4 };
        final byte[] newKeyBlob = { 4, 3, 2, 1 };

        doReturn(2)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKey(oldKeyBlob, newKeyBlob, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onError(contains("Internal error"));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyDatabaseException() throws Exception {
        final byte[] oldKeyBlob = { 101 };
        final byte[] newKeyBlob = { 5, 5, 5 };

        doThrow(new IllegalArgumentException("nope!!!"))
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKey(oldKeyBlob, newKeyBlob, callback);
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onError(contains("nope!!!"));
        verifyNoMoreInteractions(callback);
    }
}
