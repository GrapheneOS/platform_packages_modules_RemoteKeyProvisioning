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
package com.android.rkpdapp;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpdapp.utils.CborUtils;
import java.util.Locale;
import java.util.Optional;

public class ConfirmCertificates {
    /** Defines the type of payload that can be sent to the server. */
    public enum PayloadType {
        CERTIFICATE_BUNDLE,
        DER_CERTIFICATE_CHAIN;

        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The HAL instance that received the signed certificates. */
    private String halInstance;

    /** The error reason if any. */
    private Optional<String> errorReason;

    /** The payload to be sent to the server. */
    private Optional<byte[]> payload;

    /** The payload type to be sent to the server. */
    private Optional<PayloadType> payloadType;

    /** Whether the instance is an error instance. */
    private boolean isError;

    private ConfirmCertificates(
            String halInstance,
            Optional<String> errorReason,
            Optional<byte[]> payload,
            Optional<PayloadType> payloadType,
            boolean isError) {
        this.halInstance = (halInstance == null || halInstance.isEmpty()) ? "unknown" : halInstance;
        this.errorReason = errorReason;
        this.payload = payload;
        this.payloadType = payloadType;
        this.isError = isError;
    }

    public static ConfirmCertificates createSuccess(String halInstance) {
        return new ConfirmCertificates(
                halInstance,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                /* isError= */ false);
    }

    public static ConfirmCertificates createError(
            String halInstance, String reason, byte[] payload, PayloadType payloadType) {
        // Maximum length of the reason allowed by the server is 256.
        if (reason != null && reason.length() > 256) {
            reason = reason.substring(0, 256);
        }
        return new ConfirmCertificates(
                halInstance,
                Optional.ofNullable(reason),
                Optional.ofNullable(payload),
                Optional.of(payloadType),
                /* isError= */ true);
    }

    public byte[] buildCborBytes() throws RkpdException {
        Map errorInfo = new Map();
        errorReason.ifPresent(
                r -> errorInfo.put(new UnicodeString("reason"), new UnicodeString(r)));
        payloadType.ifPresent(
                pt ->
                        errorInfo.put(
                                new UnicodeString(pt.getValue()),
                                new ByteString(payload.orElse(new byte[]{}))));

        Map confirmCertificatesInfo =
                new Map().put(new UnicodeString("instance"), new UnicodeString(halInstance));
        if (!errorInfo.getKeys().isEmpty()) {
            confirmCertificatesInfo.put(new UnicodeString("error_info"), errorInfo);
        }
        try {
            return CborUtils.encodeCbor(confirmCertificatesInfo);
        } catch (CborException e) {
            throw new RkpdException(
                    RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Failed to CBOR encode ConfirmCertificatesInfo to bytes",
                    e);
        }
    }

    public boolean isError() {
        return isError;
    }
}
