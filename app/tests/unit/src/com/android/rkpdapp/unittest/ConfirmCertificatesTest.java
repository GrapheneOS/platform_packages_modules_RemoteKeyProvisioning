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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.ConfirmCertificates.PayloadType;
import java.io.ByteArrayInputStream;
import java.util.List;
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

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void testCreateSuccessInstance() {
        ConfirmCertificates successInstance =
                ConfirmCertificates.createSuccessInstance(HAL_INSTANCE);
        assertThat(successInstance.isErrorInstance()).isFalse();
    }

    @Test
    public void testCreateErrorInstance() {
        ConfirmCertificates errorInstance =
                ConfirmCertificates.createErrorInstance(
                        HAL_INSTANCE,
                        ERROR_REASON,
                        CBOR_CERT_CHAIN,
                        PayloadType.CERTIFICATE_BUNDLE);
        assertThat(errorInstance.isErrorInstance()).isTrue();
    }

    @Test
    public void testCreateSuccessInstanceWithNullHal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfirmCertificates.createSuccessInstance(null));
    }

    @Test
    public void testCreateSuccessInstanceWithEmptyHal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfirmCertificates.createSuccessInstance(""));
    }

    @Test
    public void testCreateErrorInstanceWithNullHal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                null,
                                ERROR_REASON,
                                CBOR_CERT_CHAIN,
                                PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyHal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                "", ERROR_REASON, CBOR_CERT_CHAIN, PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testCreateErrorInstanceWithNullErrorReason() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                HAL_INSTANCE,
                                null,
                                CBOR_CERT_CHAIN,
                                PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyErrorReason() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                HAL_INSTANCE, "", CBOR_CERT_CHAIN, PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testCreateErrorInstanceWithNullCertChain() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                HAL_INSTANCE, ERROR_REASON, null, PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testCreateErrorInstanceWithEmptyCertChain() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfirmCertificates.createErrorInstance(
                                HAL_INSTANCE,
                                ERROR_REASON,
                                new byte[0],
                                PayloadType.CERTIFICATE_BUNDLE));
    }

    @Test
    public void testBuildConfirmCertificatesInfoSuccess() throws CborException {
        ConfirmCertificates success = ConfirmCertificates.createSuccessInstance(HAL_INSTANCE);
        byte[] encodedInfo = success.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertThat(dataItems.size()).isEqualTo(1);
        assertThat(dataItems.get(0)).isInstanceOf(Map.class);
        Map map = (Map) dataItems.get(0);

        assertThat(map.getKeys().size()).isEqualTo(1);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo(HAL_INSTANCE);
    }

    @Test
    public void testBuildConfirmCertificatesInfoErrorWithCertBundle() throws CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createErrorInstance(
                        HAL_INSTANCE,
                        ERROR_REASON,
                        CBOR_CERT_CHAIN,
                        PayloadType.CERTIFICATE_BUNDLE);
        byte[] encodedInfo = error.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertThat(dataItems.size()).isEqualTo(1);
        assertThat(dataItems.get(0)).isInstanceOf(Map.class);
        Map map = (Map) dataItems.get(0);

        assertThat(map.getKeys().size()).isEqualTo(2);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo(HAL_INSTANCE);

        assertThat(map.get(new UnicodeString("error_info"))).isInstanceOf(Map.class);
        Map errorInfo = (Map) map.get(new UnicodeString("error_info"));
        assertThat(errorInfo.getKeys().size()).isEqualTo(2);
        assertThat(((UnicodeString) errorInfo.get(new UnicodeString("reason"))).getString())
                .isEqualTo(ERROR_REASON);
        assertArrayEquals(
                CBOR_CERT_CHAIN,
                ((ByteString)
                                errorInfo.get(
                                        new UnicodeString(
                                                PayloadType.CERTIFICATE_BUNDLE.getValue())))
                        .getBytes());
    }

    @Test
    public void testBuildConfirmCertificatesInfoErrorWithDerChain() throws CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createErrorInstance(
                        HAL_INSTANCE,
                        ERROR_REASON,
                        CBOR_CERT_CHAIN,
                        PayloadType.DER_CERTIFICATE_CHAIN);
        byte[] encodedInfo = error.buildConfirmCertificatesInfo();

        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertThat(dataItems.size()).isEqualTo(1);
        assertThat(dataItems.get(0)).isInstanceOf(Map.class);
        Map map = (Map) dataItems.get(0);

        assertThat(map.getKeys().size()).isEqualTo(2);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo(HAL_INSTANCE);

        assertThat(map.get(new UnicodeString("error_info"))).isInstanceOf(Map.class);
        Map errorInfo = (Map) map.get(new UnicodeString("error_info"));
        assertThat(errorInfo.getKeys().size()).isEqualTo(2);
        assertThat(((UnicodeString) errorInfo.get(new UnicodeString("reason"))).getString())
                .isEqualTo(ERROR_REASON);
        assertArrayEquals(
                CBOR_CERT_CHAIN,
                ((ByteString)
                                errorInfo.get(
                                        new UnicodeString(
                                                PayloadType.DER_CERTIFICATE_CHAIN.getValue())))
                        .getBytes());
    }
}
