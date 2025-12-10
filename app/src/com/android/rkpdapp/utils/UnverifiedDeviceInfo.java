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
import android.os.Build;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Builds the unverified device info map for use in certificate signing requests.
 */
public class UnverifiedDeviceInfo {
    private static final String DEVICE_RESET_SENTINEL_FILE = "device_reset_sentinel";

    private final Optional<Context> mContext;

    public UnverifiedDeviceInfo(Optional<Context> context) {
        mContext = context;
    }

    /**
     * Produce a CBOR Map object which contains the unverified device information for a certificate
     * signing request.
     *
     * @return the CBOR Map object.
     */
    public Map buildMap() {
        Map unverifiedDeviceInfo = new Map();
        unverifiedDeviceInfo
                .put(new UnicodeString("fingerprint"), new UnicodeString(Build.FINGERPRINT));
        if (Flags.reportDeviceReset() &&
                mContext.isPresent() && deviceResetReported(mContext.get())) {
            unverifiedDeviceInfo.put(new UnicodeString("report_device_reset"), SimpleValue.TRUE);
        }
        return unverifiedDeviceInfo;
    }

    /**
     * Checks if the device has been reset or app storage has been cleared.
     * It does this by checking for the presence of a sentinel file in app storage.
     * If the file is absent, it creates one and returns true.
     *
     * @param context The application context.
     * @return true if the device was reset (or app data cleared), false otherwise.
     */
    private boolean deviceResetReported(Context context) {
        File sentinelFile = new File(context.getFilesDir(), DEVICE_RESET_SENTINEL_FILE);
        try {
            return sentinelFile.createNewFile();
        } catch (IOException e) {
            // Don't assume a reset if there was an IO error.
            return false;
        }
    }
}
