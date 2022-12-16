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

package com.android.rkpdapp.service;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.IGetKeyCallback;
import com.android.rkpdapp.IRegistration;
import com.android.rkpdapp.IStoreUpgradedKeyCallback;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RemotelyProvisionedKey;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.provisioner.Provisioner;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import co.nstant.in.cbor.CborException;

/**
 * Implementation of com.android.rkpdapp.IRegistration, which fetches keys for a (caller UID,
 * IRemotelyProvisionedComponent) tuple.
 */
public final class RegistrationBinder extends IRegistration.Stub {
    static final String TAG = "RkpdRegistrationBinder";

    private final Context mContext;
    private final int mClientUid;
    private final String mServiceName;
    private final ProvisionedKeyDao mProvisionedKeyDao;
    private final ServerInterface mRkpServer;
    private final Provisioner mProvisioner;
    private final ExecutorService mThreadPool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<IGetKeyCallback, TaskHolder> mTasks =
            new ConcurrentHashMap<>();

    private static final class TaskHolder {
        public Future<?> task;
    }

    public RegistrationBinder(Context context, int clientUid, String irpcName,
            ProvisionedKeyDao provisionedKeyDao, ServerInterface rkpServer,
            Provisioner provisioner) {
        mContext = context;
        mClientUid = clientUid;
        mServiceName = irpcName;
        mProvisionedKeyDao = provisionedKeyDao;
        mRkpServer = rkpServer;
        mProvisioner = provisioner;
    }

    private void getKeyWorker(int keyId, IGetKeyCallback callback)
            throws CborException, InterruptedException, RkpdException, RemoteException {
        Log.i(TAG, "Key requested for service: " + mServiceName + ", clientUid: " + mClientUid
                + ", keyId: " + keyId + ", callback: " + callback.hashCode());
        ProvisionedKey assignedKey = mProvisionedKeyDao.getKeyForClientAndIrpc(
                mServiceName, mClientUid, keyId);

        if (assignedKey == null) {
            Log.i(TAG, "No key assigned, looking for an available key");
            assignedKey = mProvisionedKeyDao.assignKey(mServiceName, mClientUid, keyId);
            // TODO(b/262253838): check to see if we should kick off provisioning in the background
        }

        if (assignedKey == null) {
            // Since provisionKeys goes over the network, this represents our last chancel to cancel
            // before we go off and hit the network. It's not worth checking for interruption prior
            // to this, because none of the prior work is long-running.
            checkForCancel();

            Log.i(TAG, "No keys are available, kicking off provisioning");
            checkedCallback(callback::onProvisioningNeeded);
            try (ProvisionerMetrics metrics = ProvisionerMetrics.createOutOfKeysAttemptMetrics(
                    mContext, mServiceName)) {
                GeekResponse geekResponse = mRkpServer.fetchGeek(metrics);
                mProvisioner.provisionKeys(metrics, mServiceName, geekResponse);
            }
            assignedKey = mProvisionedKeyDao.assignKey(mServiceName, mClientUid, keyId);
        }

        // Now that we've gotten back from our network round-trip, it's possible an interrupt came
        // in, so deal with it. However, it's most likely that an InterruptedException came from
        // the SDK while we were sitting on the socket down in Provisioner.provisionKeys.
        checkForCancel();

        if (assignedKey == null) {
            // This should never happen...
            Log.e(TAG, "Unable to provision keys");
            checkedCallback(() -> callback.onError("Provisioning failed, no keys available"));
        } else {
            Log.i(TAG, "Key successfully assigned to client");
            RemotelyProvisionedKey key = new RemotelyProvisionedKey();
            key.keyBlob = assignedKey.keyBlob;
            key.encodedCertChain = assignedKey.certificateChain;
            checkedCallback(() -> callback.onSuccess(key));
        }
    }

    private void checkForCancel() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private interface CallbackWrapper {
        void run() throws RemoteException;
    }

    private void checkedCallback(CallbackWrapper wrapper) {
        try {
            wrapper.run();
        } catch (RemoteException e) {
            // This should only ever happen if there's a binder issue invoking the callback
            Log.e(TAG, "Error performing client callback", e);
        }
    }

    @Override
    public void getKey(int keyId, IGetKeyCallback callback) {
        TaskHolder newTask = new TaskHolder();
        TaskHolder existingTask = mTasks.putIfAbsent(callback, newTask);
        if (existingTask != null) {
            throw new IllegalArgumentException("Callback " + callback.hashCode()
                    + " is already associated with a getKey operation that is in-progress");
        }

        newTask.task = mThreadPool.submit(() -> {
            try {
                getKeyWorker(keyId, callback);
            } catch (InterruptedException e) {
                Log.i(TAG, "getKey was interrupted");
                checkedCallback(callback::onCancel);
            } catch (Exception e) {
                // Do our best to inform the callback when even the unexpected happens. Otherwise,
                // the caller is going to wait until they timeout without knowing something like a
                // RuntimeException occurred.
                Log.e(TAG, "Error provisioning keys", e);
                checkedCallback(() -> callback.onError(e.getMessage()));
            } finally {
                mTasks.remove(callback);
            }
        });
    }

    @Override
    public void cancelGetKey(IGetKeyCallback callback) throws RemoteException {
        Log.i(TAG, "cancelGetKey(" + callback.hashCode() + ")");
        TaskHolder holder = mTasks.get(callback);

        if (holder == null) {
            Log.w(TAG, "callback not found, task may have already completed");
        } else if (holder.task.isDone()) {
            Log.w(TAG, "task already completed, not cancelling");
        } else if (holder.task.isCancelled()) {
            Log.w(TAG, "task already cancelled, cannot cancel it any further");
        } else {
            holder.task.cancel(true);
        }
    }

    @Override
    public void storeUpgradedKey(byte[] oldKeyBlob, byte[] newKeyBlob,
            IStoreUpgradedKeyCallback callback) throws RemoteException {
        Log.i(TAG, "storeUpgradedKey");
        mThreadPool.submit(() -> {
            try {
                int keysUpgraded = mProvisionedKeyDao.upgradeKeyBlob(oldKeyBlob, newKeyBlob);
                if (keysUpgraded == 1) {
                    checkedCallback(callback::onSuccess);
                } else if (keysUpgraded == 0) {
                    checkedCallback(() -> callback.onError("No keys matching oldKeyBlob found"));
                } else {
                    Log.e(TAG, "Multiple keys matched the upgrade (" + keysUpgraded
                            + "). This should be impossible!");
                    checkedCallback(() -> callback.onError("Internal error"));
                }
            } catch (Exception e) {
                checkedCallback(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
