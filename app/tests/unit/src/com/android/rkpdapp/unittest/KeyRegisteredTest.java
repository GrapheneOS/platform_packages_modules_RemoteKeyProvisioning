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

import android.content.Context;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.os.ServiceManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class KeyRegisteredTest {
    private static final String TAG = "KeyRegisteredTest";
    private final String mServiceName;
    private Context mContext;

    @Parameterized.Parameters(name = "{index}: instanceName={0}")
    public static String[] parameters() {
        return ServiceManager.getDeclaredInstances(IRemotelyProvisionedComponent.DESCRIPTOR);
    }

    public KeyRegisteredTest(String instanceName) {
        this.mServiceName = IRemotelyProvisionedComponent.DESCRIPTOR + "/" + instanceName;
    }

    @Before
    public void setUp() throws Exception {
        Log.i(TAG, "TODO: Delete all keys to ensure the test code really works");
        mContext = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() throws Exception {
        Log.i(TAG, "TODO: Delete all keys so we don't leave any test keys hanging around");
    }

    @Test
    public void testKeyRegistered() throws Exception {
        Log.i(TAG, "TODO: Verify we can fetch certificates for '" + mServiceName + "'");
    }
}
