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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.answer;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.notNull;
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

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.IGetKeyCallback;
import com.android.rkpdapp.IStoreUpgradedKeyCallback;
import com.android.rkpdapp.RemotelyProvisionedKey;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.provisioner.Provisioner;
import com.android.rkpdapp.service.RegistrationBinder;
import com.android.rkpdapp.utils.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            CERT_CHAIN, Instant.now().plus(5, ChronoUnit.DAYS));
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(2);

    private Context mContext;
    private ProvisionedKeyDao mMockDao;
    private ServerInterface mRkpServer;
    private Provisioner mMockProvisioner;
    private ExecutorService mThreadPool;
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

    private Instant isInRange(Instant min, Instant max) {
        return argThat(new ArgumentMatcher<>() {
            public boolean matches(Instant actual) {
                return (actual.equals(min) || actual.isAfter(min))
                        && (actual.equals(max) || actual.isBefore(max));
            }

            public String toString() {
                return "[value between " + min + " and " + max + "]";
            }
        });
    }

    private void completeAllTasks() throws InterruptedException {
        mThreadPool.shutdown();
        assertWithMessage("Background tasks failed to complete in " + MAX_TIMEOUT)
                .that(mThreadPool.awaitTermination(MAX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
    }

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mMockDao = mock(ProvisionedKeyDao.class);
        mRkpServer = mock(ServerInterface.class);
        mMockProvisioner = mock(Provisioner.class);
        mThreadPool = Executors.newCachedThreadPool();
        mRegistration = new RegistrationBinder(mContext, CLIENT_UID, IRPC_HAL, mMockDao,
                mRkpServer, mMockProvisioner, mThreadPool);
    }

    @Test
    public void getKeyReturnsAlreadyAssignedKey() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        completeAllTasks();
        verify(callback).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyAssignsAvailableKey() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        completeAllTasks();
        verify(callback).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyAssignsLongExpiringKey() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));

        Instant minExpiry = Instant.now().plus(Settings.getExpiringBy(mContext));
        mRegistration.getKey(KEY_ID, mock(IGetKeyCallback.class));
        completeAllTasks();
        Instant maxExpiry = Instant.now().plus(Settings.getExpiringBy(mContext));

        verify(mMockDao)
                .getOrAssignKey(notNull(), isInRange(minExpiry, maxExpiry), anyInt(), anyInt());
    }

    @Test
    public void getKeyAssignmentFallsBackToShorterLivedKeys() throws Exception {
        doReturn(null, FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));

        Instant minExpiry = Instant.now().plus(Settings.getExpiringBy(mContext));
        Instant minFallbackExpiry = Instant.now().plus(RegistrationBinder.MIN_KEY_LIFETIME);
        mRegistration.getKey(KEY_ID, mock(IGetKeyCallback.class));
        completeAllTasks();
        Instant maxExpiry = Instant.now().plus(Settings.getExpiringBy(mContext));
        Instant maxFallbackExpiry = Instant.now().plus(RegistrationBinder.MIN_KEY_LIFETIME);

        verify(mMockDao)
                .getOrAssignKey(notNull(), isInRange(minExpiry, maxExpiry), anyInt(), anyInt());
        verify(mMockDao)
                .getOrAssignKey(notNull(), isInRange(minFallbackExpiry, maxFallbackExpiry),
                        anyInt(), anyInt());
    }

    @Test
    public void getKeyProvisionsKeysWhenEmpty() throws Exception {
        // The first two calls to assignKey returns null, indicating that the provisioner needs
        // to run, then the last call returns a key, which signifies provision success.
        doReturn(null, null, FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));

        final GeekResponse fakeGeekResponse = new GeekResponse();
        doReturn(fakeGeekResponse)
                .when(mRkpServer)
                .fetchGeek(any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        completeAllTasks();
        verify(callback).onSuccess(matches(FAKE_KEY));
        verify(callback).onProvisioningNeeded();
        verify(mMockProvisioner).provisionKeys(any(), eq(IRPC_HAL), same(fakeGeekResponse));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesUnexpectedProvisioningFailure() throws Exception {
        doThrow(new RuntimeException("PROVISIONING FAIL"))
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        completeAllTasks();
        verify(callback).onError(IGetKeyCallback.Error.ERROR_UNKNOWN, "PROVISIONING FAIL");
        verify(callback).onProvisioningNeeded();
        verifyNoMoreInteractions(callback);
    }

    private static byte getExpectedGetKeyError(RkpdException.ErrorCode errorCode) {
        switch (errorCode) {
            case NO_NETWORK_CONNECTIVITY:
                return IGetKeyCallback.Error.ERROR_PENDING_INTERNET_CONNECTIVITY;
            case DEVICE_NOT_REGISTERED:
                return IGetKeyCallback.Error.ERROR_PERMANENT;
            case NETWORK_COMMUNICATION_ERROR:
            case HTTP_CLIENT_ERROR:
            case HTTP_SERVER_ERROR:
            case HTTP_UNKNOWN_ERROR:
            case INTERNAL_ERROR:
                return IGetKeyCallback.Error.ERROR_UNKNOWN;
        }
        throw new RuntimeException("Unexpected error code: " + errorCode);
    }

    @Test
    public void getKeyMapsRkpdExceptionsCorrectly() throws Exception {
        for (RkpdException.ErrorCode errorCode: RkpdException.ErrorCode.values()) {
            doThrow(new RkpdException(errorCode, errorCode.toString()))
                    .when(mMockProvisioner)
                    .provisionKeys(any(), eq(IRPC_HAL), any());

            IGetKeyCallback callback = mock(IGetKeyCallback.class);
            mRegistration.getKey(KEY_ID, callback);
            // We cannot use completeAllTasks here because that shuts down the thread pool,
            // so use a timeout on verifying the callback instead.
            verify(callback, timeout(MAX_TIMEOUT.toMillis()))
                    .onError(getExpectedGetKeyError(errorCode), errorCode.toString());
            verify(callback).onProvisioningNeeded();
            verifyNoMoreInteractions(callback);
        }
    }

    @Test
    public void getKeyDeletesSoonToExpireKeys() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getKeyForClientAndIrpc(IRPC_HAL, CLIENT_UID, KEY_ID);

        Instant minExpiry = Instant.now().plus(RegistrationBinder.MIN_KEY_LIFETIME);
        mRegistration.getKey(KEY_ID, mock(IGetKeyCallback.class));
        completeAllTasks();
        Instant maxExpiry = Instant.now().plus(RegistrationBinder.MIN_KEY_LIFETIME);

        verify(mMockDao).deleteExpiringKeys(isInRange(minExpiry, maxExpiry));
    }

    @Test
    public void getKeyNoKeysAreProvisioned() throws Exception {
        // This test ensures that getKey will handle the case in which provisioner doesn't error
        // out, but it also does not actually provision any keys. This shouldn't ever happen.
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);
        completeAllTasks();
        verify(callback).onError(IGetKeyCallback.Error.ERROR_UNKNOWN,
                "Provisioning failed, no keys available");
        verify(callback).onProvisioningNeeded();
        verify(mMockProvisioner).provisionKeys(any(), eq(IRPC_HAL), any());
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyKicksOffBackgroundProvisioningWhenNeeded() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));
        doReturn(true)
                .when(mMockProvisioner)
                .isProvisioningNeeded(IRPC_HAL);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);

        // We cannot complete all tasks until after the get key worker task completes, because
        // that worker in turn schedules the background provisioning job
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));

        completeAllTasks();
        verify(mMockProvisioner).isProvisioningNeeded(eq(IRPC_HAL));
        verify(mMockProvisioner).provisionKeys(any(), eq(IRPC_HAL), any());
        verifyNoMoreInteractions(mMockProvisioner);
    }

    @Test
    public void getKeyDoesNotKickOffBackgroundProvisioningWhenNotNeeded() throws Exception {
        doReturn(FAKE_KEY)
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));
        doReturn(false)
                .when(mMockProvisioner)
                .isProvisioningNeeded(IRPC_HAL);

        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        mRegistration.getKey(KEY_ID, callback);

        // We cannot complete all tasks until after the get key worker task completes, because
        // that worker in turn schedules the background provisioning job
        verify(callback, timeout(MAX_TIMEOUT.toMillis())).onSuccess(matches(FAKE_KEY));

        completeAllTasks();
        verify(mMockProvisioner).isProvisioningNeeded(eq(IRPC_HAL));
        verifyNoMoreInteractions(mMockProvisioner);
    }

    @Test
    public void getKeyHandlesCancelBeforeProvisioning() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doAnswer(
                answer((hal, minExpiry, uid, keyId) -> {
                    mRegistration.cancelGetKey(callback);
                    return null;
                }))
                .when(mMockDao)
                .getOrAssignKey(eq(IRPC_HAL), notNull(), eq(CLIENT_UID), eq(KEY_ID));
        mRegistration.getKey(KEY_ID, callback);

        completeAllTasks();
        verify(callback).onCancel();
        verifyNoMoreInteractions(mMockProvisioner);
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void getKeyHandlesCancelWhileProvisioning() throws Exception {
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doAnswer(answerVoid((hal, dao, metrics) -> mRegistration.cancelGetKey(callback)))
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());
        mRegistration.getKey(KEY_ID, callback);

        completeAllTasks();
        verify(callback).onCancel();
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
        IGetKeyCallback callback = mock(IGetKeyCallback.class);
        doThrow(new InterruptedException())
                .when(mMockProvisioner)
                .provisionKeys(any(), eq(IRPC_HAL), any());
        mRegistration.getKey(KEY_ID, callback);

        completeAllTasks();
        verify(callback).onCancel();
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

        completeAllTasks();
        verify(successfulCallback).onSuccess(matches(FAKE_KEY));
        verifyNoMoreInteractions(successfulCallback);

        verify(cancelMe).onCancel();
        verifyNoMoreInteractions(cancelMe);
    }

    @Test
    public void storeUpgradedKeyAsyncSuccess() throws Exception {
        final byte[] oldKeyBlob = { 8, 6, 7, 5, 3, 0, 9};
        final byte[] newKeyBlob = { 3, 1, 4, 1, 5, 9};

        doReturn(1)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKeyAsync(oldKeyBlob, newKeyBlob, callback);
        completeAllTasks();
        verify(callback).onSuccess();
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyAsyncKeyNotFound() throws Exception {
        final byte[] oldKeyBlob = { 42 };
        final byte[] newKeyBlob = { 123 };

        doReturn(0)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKeyAsync(oldKeyBlob, newKeyBlob, callback);
        completeAllTasks();
        verify(callback).onError(contains("No keys"));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyAsyncInternalError() throws Exception {
        final byte[] oldKeyBlob = { 1, 2, 3, 4 };
        final byte[] newKeyBlob = { 4, 3, 2, 1 };

        doReturn(2)
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKeyAsync(oldKeyBlob, newKeyBlob, callback);
        completeAllTasks();
        verify(callback).onError(contains("Internal error"));
        verifyNoMoreInteractions(callback);
    }

    @Test
    public void storeUpgradedKeyAsyncDatabaseException() throws Exception {
        final byte[] oldKeyBlob = { 101 };
        final byte[] newKeyBlob = { 5, 5, 5 };

        doThrow(new IllegalArgumentException("nope!!!"))
                .when(mMockDao)
                .upgradeKeyBlob(oldKeyBlob, newKeyBlob);

        IStoreUpgradedKeyCallback callback = mock(IStoreUpgradedKeyCallback.class);
        mRegistration.storeUpgradedKeyAsync(oldKeyBlob, newKeyBlob, callback);
        completeAllTasks();
        verify(callback).onError(contains("nope!!!"));
        verifyNoMoreInteractions(callback);
    }
}
