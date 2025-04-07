/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.rkpdapp.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;

import com.android.rkpd.flags.Flags;

public class NetworkUtils {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String CHINA_GMS_FEATURE = "cn.google.services";

    /**
     * Checks whether GMSCore is installed and enabled for restricted regions.
     * This lets us assume that user has consented to connecting to Google
     * servers to provide attestation service.
     * For all other regions, we assume consent by default since this is an
     * Android OS-level application.
     *
     * @return True if user consent can be assumed else false.
     */
    public static boolean assumeNetworkConsent(Context context) {
        if (Flags.allowNetworkConsentBypass() && SystemProperties.getBoolean(
                "remote_provisioning.skip_network_consent_check", false)) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        if (pm.hasSystemFeature(CHINA_GMS_FEATURE)) {
            // For china GMS, we can simply check whether GMS package is installed and enabled.
            try {
                return pm.getApplicationInfo(GMS_PACKAGE, 0).enabled;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }
}
