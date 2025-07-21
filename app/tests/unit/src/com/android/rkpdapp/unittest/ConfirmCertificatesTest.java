/**
 * Copyright (C) 2025 The Android Open Source Project
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.rkpdapp.unittest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.utils.Settings;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ConfirmCertificates}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
public class ConfirmCertificatesTest {
    private static final String HAL_INSTANCE = "default";
    private static final String ERROR_REASON = "test error";
    private static final byte[] CBOR_CERT_CHAIN = new byte[] {0x01, 0x02, 0x03};

  private static Context sContext;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @BeforeClass
    public static void init() {
        sContext = ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Settings.clearPreferences(sContext);
    }

    @After
    public void tearDown() {
        Settings.clearPreferences(sContext);
    }

    @Test
    public void testCreateSuccessInstance() {
        ConfirmCertificates.createSuccessInstance(sContext, HAL_INSTANCE);
    }

    @Test
    public void testCreateErrorInstance() {
        ConfirmCertificates.createErrorInstance(
                sContext, HAL_INSTANCE, ERROR_REASON, CBOR_CERT_CHAIN);
    }

    @Test
    public void testCreateSuccessInstanceWithNullHal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfirmCertificates.createSuccessInstance(sContext, null));
    }

    @Test
    public void testCreateSuccessInstanceWithEmptyHal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfirmCertificates.createSuccessInstance(sContext, ""));
    }

    @Test
    public void testCreateErrorInstanceWithNullHal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, null, ERROR_REASON, CBOR_CERT_CHAIN));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyHal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, "", ERROR_REASON, CBOR_CERT_CHAIN));
    }

    @Test
    public void testCreateErrorInstanceWithNullErrorReason() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, HAL_INSTANCE, null, CBOR_CERT_CHAIN));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyErrorReason() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, HAL_INSTANCE, "", CBOR_CERT_CHAIN));
    }

    @Test
    public void testCreateErrorInstanceWithNullCertChain() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, HAL_INSTANCE, ERROR_REASON, null));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyCertChain() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                sContext, HAL_INSTANCE, ERROR_REASON, new byte[0]));
    }

    @Test
    public void testBuildConfirmCertificatesInfoSuccess() throws CborException {
        ConfirmCertificates success =
                ConfirmCertificates.createSuccessInstance(sContext, HAL_INSTANCE);

        byte[] encodedInfo = success.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertTrue(dataItems.get(0) instanceof Map);
        Map map = (Map) dataItems.get(0);

        assertEquals(2, map.getKeys().size());
        assertEquals(
                HAL_INSTANCE, ((UnicodeString) map.get(new UnicodeString("instance"))).getString());
        // The default environment maybe prod or preprod - we don't know.
        String environment =
                ((UnicodeString) map.get(new UnicodeString("environment"))).getString();
        assertTrue(environment.equals("prod") || environment.equals("preprod"));
    }

    @Test
    public void testBuildConfirmCertificatesInfoErrorUrlNotPreprod() throws CborException {
        Settings.setDeviceConfig(sContext, 0, null, "https://prod-some-url.com");

        ConfirmCertificates errorInstance =
                ConfirmCertificates.createErrorInstance(
                        sContext, HAL_INSTANCE, ERROR_REASON, CBOR_CERT_CHAIN);
        byte[] encodedInfo = errorInstance.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertTrue(dataItems.get(0) instanceof Map);
        Map map = (Map) dataItems.get(0);

        assertEquals(3, map.getKeys().size());
        assertEquals(
                HAL_INSTANCE, ((UnicodeString) map.get(new UnicodeString("instance"))).getString());
        assertEquals(
                "prod", ((UnicodeString) map.get(new UnicodeString("environment"))).getString());

        assertTrue(map.get(new UnicodeString("error_info")) instanceof Map);
        Map errorInfo = (Map) map.get(new UnicodeString("error_info"));
        assertEquals(2, errorInfo.getKeys().size());
        assertEquals(
                ERROR_REASON,
                ((UnicodeString) errorInfo.get(new UnicodeString("reason"))).getString());
        assertArrayEquals(
                CBOR_CERT_CHAIN,
                ((ByteString) errorInfo.get(new UnicodeString("signed_certificates"))).getBytes());
    }

    @Test
    public void testBuildConfirmCertificatesInfoPreprodEnvironment() throws CborException {
        Settings.setDeviceConfig(sContext, 0, null, "https://preprod-some-url.com");
        ConfirmCertificates success =
                ConfirmCertificates.createSuccessInstance(sContext, HAL_INSTANCE);
        byte[] encodedInfo = success.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertTrue(dataItems.get(0) instanceof Map);
        Map map = (Map) dataItems.get(0);

        assertEquals(2, map.getKeys().size());
        assertEquals(
                HAL_INSTANCE, ((UnicodeString) map.get(new UnicodeString("instance"))).getString());
        assertEquals(
                "preprod",
                ((UnicodeString) map.get(new UnicodeString("environment"))).getString());
    }

    @Test
    public void testBuildConfirmCertificatesInfoErrorPreprodEnvironment() throws CborException {
        Settings.setDeviceConfig(sContext, 0, null, "https://preprod-some-url.com");
        ConfirmCertificates error =
                ConfirmCertificates.createErrorInstance(
                        sContext, HAL_INSTANCE, ERROR_REASON, CBOR_CERT_CHAIN);
        byte[] encodedInfo = error.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertEquals(1, dataItems.size());
        assertTrue(dataItems.get(0) instanceof Map);
        Map map = (Map) dataItems.get(0);

        assertEquals(3, map.getKeys().size());
        assertEquals(
                HAL_INSTANCE, ((UnicodeString) map.get(new UnicodeString("instance"))).getString());
        assertEquals(
                "preprod",
                ((UnicodeString) map.get(new UnicodeString("environment"))).getString());

        assertTrue(map.get(new UnicodeString("error_info")) instanceof Map);
        Map errorInfo = (Map) map.get(new UnicodeString("error_info"));
        assertEquals(2, errorInfo.getKeys().size());
        assertEquals(
                ERROR_REASON,
                ((UnicodeString) errorInfo.get(new UnicodeString("reason"))).getString());
        assertArrayEquals(
                CBOR_CERT_CHAIN,
                ((ByteString) errorInfo.get(new UnicodeString("signed_certificates"))).getBytes());
    }
}
