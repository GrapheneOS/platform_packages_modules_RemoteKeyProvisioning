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

package com.android.rkpdapp.unittest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.utils.UnverifiedDeviceInfo;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UnverifiedDeviceInfoTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mContext.deleteFile("device_reset_sentinel");
    }

    @After
    public void tearDown() {
        mContext.deleteFile("device_reset_sentinel");
    }

    @Test
    public void testBuildUnverifiedDeviceInfo() {
        Map devInfo = new UnverifiedDeviceInfo(Optional.empty()).buildMap();
        assertEquals("Unverified device info only has one entry.", 1, devInfo.getKeys().size());
        DataItem fingerprint = devInfo.get(new UnicodeString("fingerprint"));
        assertNotNull("Device info doesn't contain fingerprint", fingerprint);
        assertEquals(MajorType.UNICODE_STRING, fingerprint.getMajorType());
        assertEquals(Build.FINGERPRINT, ((UnicodeString) fingerprint).getString());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_REPORT_DEVICE_RESET)
    public void testBuildUnverifiedDeviceInfo_withReset() {
        UnverifiedDeviceInfo udi = new UnverifiedDeviceInfo(Optional.of(mContext));

        // First time, we should report reset.
        Map devInfo = udi.buildMap();
        assertEquals(2, devInfo.getKeys().size());
        DataItem reportReset = devInfo.get(new UnicodeString("report_device_reset"));
        assertNotNull(reportReset);
        assertEquals(MajorType.SPECIAL, reportReset.getMajorType());
        assertEquals(SimpleValue.TRUE, reportReset);

        // Second time, we should not report reset.
        devInfo = udi.buildMap();
        assertEquals(1, devInfo.getKeys().size());
        assertNull(devInfo.get(new UnicodeString("report_device_reset")));
    }
}
