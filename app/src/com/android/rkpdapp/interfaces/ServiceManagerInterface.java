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

package com.android.rkpdapp.interfaces;

import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.os.ServiceManager;

import java.util.Arrays;

/**
 * Provides convenience methods for interfacing with ServiceManager class and its static functions.
 */
public class ServiceManagerInterface {
    private static final String TAG = "RkpdSvcManagerInterface";
    private final String mServiceName;
    private final IRemotelyProvisionedComponent mBinder;

    public ServiceManagerInterface(final String serviceName) {
        mServiceName = serviceName;
        mBinder = IRemotelyProvisionedComponent.Stub.asInterface(
                ServiceManager.waitForDeclaredService(serviceName));
        if (mBinder == null) {
            throw new RuntimeException("Cannot find any implementation for " + mServiceName);
        }
    }

    public String getServiceName() {
        return mServiceName;
    }

    public IRemotelyProvisionedComponent getBinder() {
        return mBinder;
    }

    /**
     * Gets all the declared instances for IRemotelyProvisionedComponent as a String array. The
     * returned values are fully qualified service names which can be directly accessed by using
     * {@link ServiceManager#waitForDeclaredService}
     */
    public static String[] getDeclaredInstances() {
        String irpcInterface = IRemotelyProvisionedComponent.DESCRIPTOR;
        return Arrays.stream(ServiceManager.getDeclaredInstances(irpcInterface))
                .map(x -> irpcInterface + "/" + x).toArray(String[]::new);
    }
}
