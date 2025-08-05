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
    /** The type of payload to be sent to the server. */
    public enum PayloadType {
        CERTIFICATE_BUNDLE,
        DER_CERTIFICATE_CHAIN;

        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The payload to be sent to the server. */
    private static class Payload {
        /** The data to be sent to the server. */
        private final byte[] data;

        /** The type of payload to be sent to the server. */
        private final PayloadType payloadType;

        public Payload(byte[] data, PayloadType payloadType) {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("Payload must not be null or empty.");
            }

            this.data = data;
            this.payloadType = payloadType;
        }
    }

    private static final String TAG = "RkpdConfirmCertificates";

    /** The HAL instance that received the signed certificates. */
    private String halInstance;

    /** The error reason if any. */
    private Optional<String> errorReason;

    /** The payload to be sent to the server. */
    private Optional<Payload> payload;

    private ConfirmCertificates(
            String halInstance, Optional<String> errorReason, Optional<Payload> payload) {
        if (halInstance == null || halInstance.isEmpty()) {
            throw new IllegalArgumentException("HAL instance must not be null or empty.");
        }
        this.halInstance = halInstance;
        this.errorReason = errorReason;
        this.payload = payload;
    }

    public static ConfirmCertificates createSuccessInstance(String halInstance) {
        return new ConfirmCertificates(halInstance, Optional.empty(), Optional.empty());
    }

    public static ConfirmCertificates createErrorInstance(
            String halInstance, String errorReason, byte[] payload, PayloadType payloadType) {
        if (errorReason == null || errorReason.isEmpty()) {
            throw new IllegalArgumentException("Error reason must not be null or empty.");
        }

        return new ConfirmCertificates(
                halInstance,
                Optional.of(errorReason),
                Optional.of(new Payload(payload, payloadType)));
    }

    public byte[] buildConfirmCertificatesInfo() throws CborException {
        Map confirmCertificatesInfo =
                new Map().put(new UnicodeString("instance"), new UnicodeString(halInstance));
        if (errorReason.isPresent() && payload.isPresent()) {
            confirmCertificatesInfo.put(
                    new UnicodeString("error_info"),
                    new Map()
                            .put(new UnicodeString("reason"), new UnicodeString(errorReason.get()))
                            .put(
                                    new UnicodeString(payload.get().payloadType.getValue()),
                                    new ByteString(payload.get().data)));
        }
        return CborUtils.encodeCbor(confirmCertificatesInfo);
    }

    /** Returns true if the instance is an error instance. */
    public boolean isErrorInstance() {
        return errorReason.isPresent();
    }
}
