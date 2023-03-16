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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.rkpdapp.IGetRegistrationCallback;
import com.android.rkpdapp.IRegistration;
import com.android.rkpdapp.IRemoteProvisioning;
import com.android.rkpdapp.ThreadPool;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpdDatabase;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.interfaces.ServiceManagerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.provisioner.Provisioner;

/** Provides the implementation for IRemoteProvisioning.aidl */
public class RemoteProvisioningService extends Service {
    public static final String TAG = "com.android.rkpdapp";
    private final IRemoteProvisioning.Stub mBinder = new RemoteProvisioningBinder();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    final class RemoteProvisioningBinder extends IRemoteProvisioning.Stub {

        @Override
        public void getRegistration(int callerUid, String irpcName,
                IGetRegistrationCallback callback) {
            ProvisionedKeyDao dao = RkpdDatabase.getDatabase(
                    getApplicationContext()).provisionedKeyDao();
            Context context = getApplicationContext();
            Provisioner provisioner = new Provisioner(context, dao);
            SystemInterface systemInterface = ServiceManagerInterface.getInstance(irpcName);
            IRegistration.Stub registration = new RegistrationBinder(context, callerUid,
                    systemInterface, dao, new ServerInterface(context), provisioner,
                    ThreadPool.EXECUTOR);
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
