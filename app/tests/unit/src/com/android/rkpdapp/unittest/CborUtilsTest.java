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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Build;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.Settings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CborUtilsTest {
    private ByteArrayOutputStream mBaos;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mBaos = new ByteArrayOutputStream();
    }

    @Presubmit
    @Test
    public void testParseSignedCertificatesFakeData() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(new byte[] {0x01, 0x02, 0x03})
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());
        byte[] encodedBytes = mBaos.toByteArray();
        ArrayList<byte[]> certChains =
                new ArrayList<>(CborUtils.parseSignedCertificates(encodedBytes));
        assertArrayEquals(new byte[] {0x04, 0x05, 0x06, 0x01, 0x02, 0x03}, certChains.get(0));
        assertArrayEquals(new byte[] {0x07, 0x08, 0x09, 0x01, 0x02, 0x03}, certChains.get(1));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testParseSignedCertificatesWrongSize() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(1)
                    .end()
                .build());

        RkpdException ex =
                assertThrows(
                        RkpdException.class,
                        () -> CborUtils.parseSignedCertificates(mBaos.toByteArray()));

        assertEquals(RkpdException.ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertThat(ex).hasMessageThat().isEqualTo("Failed to parse signed certificates");
        assertThat(ex).hasCauseThat().isInstanceOf(CborException.class);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testParseSignedCertificatesWrongTypeSharedCerts() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("Should be a bstr")
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());

        RkpdException ex =
                assertThrows(
                        RkpdException.class,
                        () -> CborUtils.parseSignedCertificates(mBaos.toByteArray()));

        assertEquals(RkpdException.ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertThat(ex).hasMessageThat().isEqualTo("Failed to parse signed certificates");
        assertThat(ex).hasCauseThat().isInstanceOf(CborException.class);
        assertThat(ex).hasCauseThat().hasMessageThat().contains("Expected BYTE_STRING");
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testParseSignedCertificatesWrongTypeUniqueCerts() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add(new byte[] {0x01, 0x02, 0x03})
                    .addArray()
                        .add(new byte[] {0x04, 0x05, 0x06})
                        .add("Every entry should be a bstr")
                        .add(new byte[] {0x07, 0x08, 0x09})
                        .end()
                    .end()
                .build());

        RkpdException ex =
                assertThrows(
                        RkpdException.class,
                        () -> CborUtils.parseSignedCertificates(mBaos.toByteArray()));

        assertEquals(RkpdException.ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertThat(ex).hasMessageThat().isEqualTo("Failed to parse signed certificates");
        assertThat(ex).hasCauseThat().isInstanceOf(CborException.class);
        assertThat(ex).hasCauseThat().hasMessageThat().contains("Expected BYTE_STRING");
    }

    @Test
    public void testCreateCertificateRequest() throws Exception {
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addMap()
                    .put("a", "b")
                    .put("cool", "yeah")
                    .put("testing", "123")
                    .put("str", "str")
                    .end()
                .build());
        byte[] deviceInfo = mBaos.toByteArray();
        mBaos.reset();
        byte[] challenge = new byte[] {0x01, 0x02, 0x03};
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("protected header")
                    .add("unprotected header")
                    .add("super secret payload")
                    .add("super not secret recipient")
                    .end()
                .build());
        byte[] protectedDataPayload = mBaos.toByteArray();
        mBaos.reset();
        new CborEncoder(mBaos).encode(new CborBuilder()
                .addArray()
                    .add("protected header")
                    .add("unprotected header")
                    .add("super not secret payload")
                    .add("mac tag")
                    .end()
                .build());
        byte[] macedKeysToSign = mBaos.toByteArray();
        byte[] certReq =
                CborUtils.buildCertificateRequest(deviceInfo,
                                                  challenge,
                                                  protectedDataPayload,
                                                  macedKeysToSign,
                                                  CborUtils.buildUnverifiedDeviceInfo());
        ByteArrayInputStream bais = new ByteArrayInputStream(certReq);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertEquals(MajorType.ARRAY, dataItems.get(0).getMajorType());
        dataItems = ((Array) dataItems.get(0)).getDataItems();
        assertEquals(4, dataItems.size());
        // Array: DeviceInfo
        //      Map: VerifiedDeviceInfo
        //      Map: UnverifiedDeviceInfo
        assertEquals(MajorType.ARRAY, dataItems.get(0).getMajorType());
        assertEquals(MajorType.MAP,
                ((Array) dataItems.get(0)).getDataItems().get(0).getMajorType());
        assertEquals(MajorType.MAP,
                ((Array) dataItems.get(0)).getDataItems().get(1).getMajorType());
        // Challenge
        assertEquals(MajorType.BYTE_STRING, dataItems.get(1).getMajorType());
        // ProtectedData
        assertEquals(MajorType.ARRAY, dataItems.get(2).getMajorType());
        // MacedKeysToSign
        assertEquals(MajorType.ARRAY, dataItems.get(3).getMajorType());
    }

    @Test
    public void testBuildUnverifiedDeviceInfo() {
        Map devInfo = CborUtils.buildUnverifiedDeviceInfo();
        assertEquals("Unverified device info only has one entry.", 1, devInfo.getKeys().size());
        DataItem fingerprint = devInfo.get(new UnicodeString("fingerprint"));
        assertNotNull("Device info doesn't contain fingerprint", fingerprint);
        assertEquals(MajorType.UNICODE_STRING, fingerprint.getMajorType());
        assertEquals(Build.FINGERPRINT, fingerprint.toString());
    }

    @Test
    public void testBuildProvisioningInfo() throws CborException {
        Context context = ApplicationProvider.getApplicationContext();
        Settings.generateAndSetId(context);

        byte[] cbor = CborUtils.buildProvisioningInfo(context);
        DataItem info = new CborDecoder(new ByteArrayInputStream(cbor)).decode().get(0);

        assertEquals(
                info,
                new CborBuilder()
                    .addMap()
                        .put("fingerprint", Build.FINGERPRINT)
                        .put("id", Settings.getId(context))
                        .put("version", context.getApplicationInfo().compileSdkVersion)
                        .end()
                    .build()
                    .get(0));
    }
}
