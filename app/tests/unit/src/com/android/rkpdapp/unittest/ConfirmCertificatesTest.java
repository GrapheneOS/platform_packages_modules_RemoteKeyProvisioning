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
import com.android.rkpdapp.RkpdException;
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
    private static final byte[] PAYLOAD = new byte[] {0x01, 0x02, 0x03};
    private static final PayloadType PAYLOAD_TYPE = PayloadType.CERTIFICATE_BUNDLE;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static Map decodeCbor(byte[] encodedInfo) throws CborException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertThat(dataItems.size()).isEqualTo(1);
        assertThat(dataItems.get(0)).isInstanceOf(Map.class);
        return (Map) dataItems.get(0);
    }

    private static void verifyErrorCertificateInfo(
            Map map, String halInstance, String reason, byte[] payload, PayloadType payloadType) {
        assertThat(map.getKeys().size()).isEqualTo(2);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo(halInstance);

        assertThat(map.get(new UnicodeString("error_info"))).isInstanceOf(Map.class);
        Map errorInfo = (Map) map.get(new UnicodeString("error_info"));
        if (!reason.isEmpty()) {
            assertThat(((UnicodeString) errorInfo.get(new UnicodeString("reason"))).getString())
                    .isEqualTo(reason);
        }
        if (payload != null && payload.length > 0) {
        assertArrayEquals(
                payload,
                ((ByteString) errorInfo.get(new UnicodeString(payloadType.getValue()))).getBytes());
        }
    }

    @Test
    public void buildCborBytesWithNullHalInstance() throws RkpdException, CborException {
        ConfirmCertificates success = ConfirmCertificates.createSuccess(null);
        byte[] encodedInfo = success.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        assertThat(map.getKeys().size()).isEqualTo(1);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo("unknown");
        assertThat(success.isError()).isFalse();
    }

    @Test
    public void buildCborBytesWithEmptyHalInstance() throws RkpdException, CborException {
        ConfirmCertificates success = ConfirmCertificates.createSuccess("");
        byte[] encodedInfo = success.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        assertThat(map.getKeys().size()).isEqualTo(1);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo("unknown");
        assertThat(success.isError()).isFalse();
    }

    @Test
    public void buildCborBytesErrorWithEmptyStringErrorReason()
            throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(
                        HAL_INSTANCE, "", PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(
                map, HAL_INSTANCE, "", PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithNullPayload() throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, null, PAYLOAD_TYPE);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(map, HAL_INSTANCE, ERROR_REASON, new byte[0], PAYLOAD_TYPE);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithEmptyPayload() throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(
                        HAL_INSTANCE, ERROR_REASON, new byte[0], PAYLOAD_TYPE);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(map, HAL_INSTANCE, ERROR_REASON, new byte[0], PAYLOAD_TYPE);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesSuccess() throws RkpdException, CborException {
        ConfirmCertificates success = ConfirmCertificates.createSuccess(HAL_INSTANCE);
        byte[] encodedInfo = success.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        assertThat(map.getKeys().size()).isEqualTo(1);
        assertThat(((UnicodeString) map.get(new UnicodeString("instance"))).getString())
                .isEqualTo(HAL_INSTANCE);
        assertThat(success.isError()).isFalse();
    }

    @Test
    public void buildCborBytesErrorWithCertBundle() throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(
                        HAL_INSTANCE, ERROR_REASON, PAYLOAD, PayloadType.CERTIFICATE_BUNDLE);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(
                map, HAL_INSTANCE, ERROR_REASON, PAYLOAD, PayloadType.CERTIFICATE_BUNDLE);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithDerChain() throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(
                        HAL_INSTANCE, ERROR_REASON, PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(
                map, HAL_INSTANCE, ERROR_REASON, PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithEmptyErrorReason() throws RkpdException, CborException {
        ConfirmCertificates error =
                ConfirmCertificates.createError(
                        HAL_INSTANCE, null, PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfo(
                map, HAL_INSTANCE, "", PAYLOAD, PayloadType.DER_CERTIFICATE_CHAIN);
        assertThat(error.isError()).isTrue();
    }
}
