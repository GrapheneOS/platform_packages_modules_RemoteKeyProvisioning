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

package com.android.rkpdapp.wvtest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import com.android.compatibility.common.util.PropertyUtil;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.provisioner.WidevineProvisioner;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public class WidevineHostTestHelperTests {

    private static boolean sSupportsWidevine = true;
    private static final String TAG = "RkpdAppWidevineTest";
    private static MediaDrm sDrm;

    @BeforeClass
    public static void init() {
        try {
            sDrm = new MediaDrm(WidevineProvisioner.WIDEVINE_UUID);
        } catch (UnsupportedSchemeException e) {
            Log.i(TAG, "Device doesn't support widevine, all tests should pass.");
            sSupportsWidevine = false;
        }
    }

    @Before
    public void setUp() {
        assume()
                .withMessage("Widevine Integration tests rely on network availability.")
                .that(
                    ServerInterface.isNetworkConnected(ApplicationProvider.getApplicationContext()))
                .isTrue();
    }

    private boolean isProvisioning4() {
        try {
            String provisioningModel = sDrm.getPropertyString("provisioningModel");
            int oemCryptoApiVersion =
                    Integer.parseInt(sDrm.getPropertyString("oemCryptoApiVersion"));
            return provisioningModel.equals("BootCertificateChain") && oemCryptoApiVersion < 20;
        } catch (Exception e) {
            // Catch any exception thrown here to avoid crashing the test.
            Log.i(TAG, "Device doesn't support provisioning 4.0.");
            return false;
        }
    }

    private boolean isProvisioned() {
        int systemId = Integer.parseInt(sDrm.getPropertyString("systemId"));
        return systemId != Integer.MAX_VALUE;
    }

    @Test
    public void testIfProvisioningNeededIsConsistentWithSystemStatus() {
        assume().withMessage("Device does not support widevine.").that(sSupportsWidevine).isTrue();
        assertThat(isProvisioning4() && !isProvisioned())
                .isEqualTo(WidevineProvisioner.isWidevineProvisioningNeeded());
    }

    @Test
    public void testProvisionWidevine() {
        assume().withMessage("Device does not support widevine.").that(sSupportsWidevine).isTrue();
        assume().withMessage("Not a provisioning 4.0 device.").that(isProvisioning4()).isTrue();
        assume().that(isProvisioned()).isFalse();
        WidevineProvisioner prov = TestWorkerBuilder.from(
                ApplicationProvider.getApplicationContext(),
                WidevineProvisioner.class,
                Executors.newSingleThreadExecutor()).build();
        assertThat(prov.doWork()).isEqualTo(ListenableWorker.Result.success());
        assertThat(isProvisioned()).isTrue();
    }
}
