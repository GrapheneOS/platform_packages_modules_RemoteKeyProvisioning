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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.testutil.SystemPropertySetter;
import com.android.rkpdapp.utils.NetworkUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class NetworkUtilsTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ALLOW_NETWORK_CONSENT_BYPASS)
    public void testConnectionConsent() throws Exception {
        String cnGmsFeature = "cn.google.services";
        PackageManager mockedPackageManager = Mockito.mock(PackageManager.class);
        Context mockedContext = Mockito.mock(Context.class);
        ApplicationInfo fakeApplicationInfo = new ApplicationInfo();

        Mockito.when(mockedContext.getPackageManager()).thenReturn(mockedPackageManager);
        Mockito.when(mockedPackageManager.hasSystemFeature(cnGmsFeature)).thenReturn(true);
        Mockito.when(mockedPackageManager.getApplicationInfo(Mockito.any(), Mockito.eq(0)))
                .thenReturn(fakeApplicationInfo);

        try (SystemPropertySetter check = SystemPropertySetter.setSkipNetworkConsentCheck(true)) {
            if (check != null) {
                fakeApplicationInfo.enabled = false;
                assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();
            }
        }

        try (SystemPropertySetter ignored =
                     SystemPropertySetter.setSkipNetworkConsentCheck(false)) {
            fakeApplicationInfo.enabled = false;
            assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isFalse();

            fakeApplicationInfo.enabled = true;
            assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();

            Mockito.when(mockedPackageManager.getApplicationInfo(Mockito.any(), Mockito.eq(0)))
                    .thenThrow(new PackageManager.NameNotFoundException());
            assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isFalse();

            Mockito.when(mockedPackageManager.hasSystemFeature(cnGmsFeature)).thenReturn(false);
            assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();

            fakeApplicationInfo.enabled = false;
            assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ALLOW_NETWORK_CONSENT_BYPASS)
    public void testConnectionConsentFlagDisabled() throws Exception {
        String cnGmsFeature = "cn.google.services";
        PackageManager mockedPackageManager = Mockito.mock(PackageManager.class);
        Context mockedContext = Mockito.mock(Context.class);
        ApplicationInfo fakeApplicationInfo = new ApplicationInfo();

        Mockito.when(mockedContext.getPackageManager()).thenReturn(mockedPackageManager);
        Mockito.when(mockedPackageManager.hasSystemFeature(cnGmsFeature)).thenReturn(true);
        Mockito.when(mockedPackageManager.getApplicationInfo(Mockito.any(), Mockito.eq(0)))
                .thenReturn(fakeApplicationInfo);

        fakeApplicationInfo.enabled = false;
        assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isFalse();

        fakeApplicationInfo.enabled = true;
        assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();

        Mockito.when(mockedPackageManager.getApplicationInfo(Mockito.any(), Mockito.eq(0)))
                .thenThrow(new PackageManager.NameNotFoundException());
        assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isFalse();

        Mockito.when(mockedPackageManager.hasSystemFeature(cnGmsFeature)).thenReturn(false);
        assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();

        fakeApplicationInfo.enabled = false;
        assertThat(NetworkUtils.assumeNetworkConsent(mockedContext)).isTrue();
    }
}
