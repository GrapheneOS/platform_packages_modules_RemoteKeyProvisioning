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

import static com.android.rkpdapp.ConfirmCertificates.CHAINS_KEY;
import static com.android.rkpdapp.ConfirmCertificates.ERROR_INFO_KEY;
import static com.android.rkpdapp.ConfirmCertificates.HAL_INSTANCE_KEY;
import static com.android.rkpdapp.ConfirmCertificates.REASON_KEY;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.ConfirmCertificates.CertificateBundle;
import com.android.rkpdapp.ConfirmCertificates.DerCertificateChains;
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

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static Map decodeCbor(byte[] encodedInfo) throws CborException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedInfo);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        assertThat(dataItems.size()).isEqualTo(1);
        assertThat(dataItems.get(0)).isInstanceOf(Map.class);
        return (Map) dataItems.get(0);
    }

    private static void verifyErrorCertificateInfoWithBundle(
            Map confirmCertificatesInfo, String halInstance, String reason, byte[] payload) {
        assertThat(confirmCertificatesInfo.getKeys()).hasSize(2);
        assertThat(((UnicodeString) confirmCertificatesInfo.get(HAL_INSTANCE_KEY))
                .getString())
                .isEqualTo(halInstance);

        Map errorInfo = (Map) confirmCertificatesInfo.get(ERROR_INFO_KEY);
        int expectedErrorInfoSize = 0;
        if (reason != null) {
            expectedErrorInfoSize++;
            assertThat(((UnicodeString) errorInfo.get(REASON_KEY)).getString())
                    .isEqualTo(reason);
        }
        if (payload != null) {
            expectedErrorInfoSize++;
            assertArrayEquals(
                    payload,
                    ((ByteString) errorInfo.get(ConfirmCertificates.CERTIFICATE_BUNDLE))
                        .getBytes());
        }
        assertThat(errorInfo.getKeys()).hasSize(expectedErrorInfoSize);
    }

    private static void verifyErrorCertificateInfoWithChains(Map confirmCertificatesInfo,
            String halInstance, String reason, List<byte[]> derCertChains) {
        assertThat(confirmCertificatesInfo.getKeys()).hasSize(2);
        assertThat(((UnicodeString) confirmCertificatesInfo.get(HAL_INSTANCE_KEY))
                .getString())
                .isEqualTo(halInstance);

        Map errorInfo = (Map) confirmCertificatesInfo.get(ERROR_INFO_KEY);
        int expectedErrorInfoSize = 0;
        if (reason != null) {
            expectedErrorInfoSize++;
            assertThat(((UnicodeString) errorInfo.get(REASON_KEY)).getString())
                    .isEqualTo(reason);
        }
        if (derCertChains != null) {
            expectedErrorInfoSize++;
            Map chainsMap = (Map) errorInfo.get(ConfirmCertificates.DER_CERTIFICATE_CHAINS);
            Array chainsArray = (Array) chainsMap.get(CHAINS_KEY);
            assertThat(chainsArray.getDataItems()).hasSize(derCertChains.size());
            for (int i = 0; i < derCertChains.size(); ++i) {
                assertArrayEquals(
                        derCertChains.get(i),
                        ((ByteString) chainsArray.getDataItems().get(i)).getBytes());
            }
        }
        assertThat(errorInfo.getKeys()).hasSize(expectedErrorInfoSize);
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
        ConfirmCertificates error = ConfirmCertificates.createError(
                HAL_INSTANCE, "", new DerCertificateChains(PAYLOAD));
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, "", List.of(PAYLOAD));
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithNullPayload() throws RkpdException, CborException {
        ConfirmCertificates error = ConfirmCertificates.createError(
                HAL_INSTANCE, ERROR_REASON, null);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithBundle(map, HAL_INSTANCE, ERROR_REASON, null);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithEmptyPayload() throws RkpdException, CborException {
        CertificateBundle payload = new CertificateBundle(new byte[0]);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithBundle(map, HAL_INSTANCE, ERROR_REASON, new byte[0]);
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
        CertificateBundle payload = new CertificateBundle(PAYLOAD);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithBundle(map, HAL_INSTANCE, ERROR_REASON, PAYLOAD);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithDerChain() throws RkpdException, CborException {
        DerCertificateChains payload = new DerCertificateChains(PAYLOAD);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, ERROR_REASON, List.of(PAYLOAD));
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithNullErrorReason() throws RkpdException, CborException {
        DerCertificateChains payload = new DerCertificateChains(PAYLOAD);
        ConfirmCertificates error = ConfirmCertificates.createError(HAL_INSTANCE, null, payload);
        byte[] encodedInfo = error.buildCborBytes();

        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, null, List.of(PAYLOAD));
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithTruncatedReason() throws RkpdException, CborException {
        String longReason = new String(new char[300]).replace('\0', 'A');
        String truncatedReason = longReason.substring(0, 256);
        CertificateBundle payload = new CertificateBundle(PAYLOAD);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, longReason, payload);
        byte[] encodedInfo = error.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithBundle(map, HAL_INSTANCE, truncatedReason, PAYLOAD);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithMultipleDerChains() throws RkpdException, CborException {
        byte[] payload2 = new byte[] {0x04, 0x05, 0x06};
        List<byte[]> chains = List.of(PAYLOAD, payload2);
        DerCertificateChains payload = new DerCertificateChains(chains);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, ERROR_REASON, chains);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithEmptyDerChains() throws RkpdException, CborException {
        List<byte[]> chains = List.of();
        DerCertificateChains payload = new DerCertificateChains(chains);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, ERROR_REASON, chains);
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithNullDerChainsList() throws RkpdException, CborException {
        DerCertificateChains payload = new DerCertificateChains((List<byte[]>) null);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(
                map, HAL_INSTANCE, ERROR_REASON, List.of());
        assertThat(error.isError()).isTrue();
    }

    @Test
    public void buildCborBytesErrorWithNullDerChain() throws RkpdException, CborException {
        DerCertificateChains payload = new DerCertificateChains((byte[]) null);
        ConfirmCertificates error =
                ConfirmCertificates.createError(HAL_INSTANCE, ERROR_REASON, payload);
        byte[] encodedInfo = error.buildCborBytes();
        Map map = decodeCbor(encodedInfo);
        verifyErrorCertificateInfoWithChains(map, HAL_INSTANCE, ERROR_REASON, List.of());
        assertThat(error.isError()).isTrue();
    }
}
