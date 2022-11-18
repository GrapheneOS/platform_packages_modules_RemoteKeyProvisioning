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

package com.android.rkpdapp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/** Provides the implementation for IRemoteProvisioning.aidl */
public class RemoteProvisioningService extends Service {
    static final String TAG = "com.android.rkpdapp";
    private final IRemoteProvisioning.Stub mBinder = new RemoteProvisioningBinder();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    final class RegistrationBinder extends IRegistration.Stub {
        RegistrationBinder(String irpcName) {
            if (!ServiceManager.isDeclared(irpcName)) {
                throw new IllegalArgumentException(
                    "The given IRemotelyProvisionedComponent is not declared: " + irpcName);
            }
            IBinder binder = ServiceManager.waitForDeclaredService(irpcName);
        }

        @Override
        public void getKey(int keyId, IGetKeyCallback callback) throws RemoteException {
            Log.i(TAG, "getKey");
        }

        @Override
        public void cancelGetKey(IGetKeyCallback callback) throws RemoteException {
            Log.i(TAG, "cancelGetKey");
        }

        @Override
        public void storeUpgradedKey(int keyId, byte[] newKeyBlob) throws RemoteException {
            Log.i(TAG, "storeUpgradedKey");
        }
    }

    final class RemoteProvisioningBinder extends IRemoteProvisioning.Stub {
        @Override
        public void getRegistration(String irpcName, IGetRegistrationCallback callback) {
            IRegistration.Stub registration = new RegistrationBinder(irpcName);
            try {
                callback.onSuccess(registration);
            } catch (RemoteException e) {
                Log.e(TAG, "error sending registration to callback", e);
                throw e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void cancelGetRegistration(IGetRegistrationCallback callback) {
            // Not actually supported on this end of the transaction, because we always
            // complete, and there's no way to win the race.
            Log.i(TAG, "cancelGetRegistration");
        }
    }
}
