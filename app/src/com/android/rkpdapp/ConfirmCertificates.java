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
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpdapp.utils.CborUtils;
import java.util.List;
import java.util.Optional;

public class ConfirmCertificates {
    public static final UnicodeString HAL_INSTANCE_KEY = new UnicodeString("instance");
    public static final UnicodeString ERROR_INFO_KEY = new UnicodeString("error_info");
    public static final UnicodeString REASON_KEY = new UnicodeString("reason");
    public static final UnicodeString CHAINS_KEY = new UnicodeString("chains");
    public static final UnicodeString CERTIFICATE_BUNDLE = new UnicodeString("certificate_bundle");
    public static final UnicodeString DER_CERTIFICATE_CHAINS =
            new UnicodeString("der_certificate_chains");

    public abstract static class Payload {
        private final UnicodeString label;
        private final DataItem value;

        Payload(UnicodeString label, DataItem value) {
            this.label = label;
            this.value = value;
        }

        public UnicodeString getLabel() {
            return label;
        }
        public DataItem getValue() {
            return value;
        }
    }

    public static class DerCertificateChains extends Payload {
        public DerCertificateChains(List<byte[]> derCertificateChains) {
            super(DER_CERTIFICATE_CHAINS, encode(derCertificateChains));
        }

        public DerCertificateChains(byte[] derChain) {
            this(derChain == null ? List.of() : List.of(derChain));
        }

        private static Map encode(List<byte[]> derCertificateChains) {
            if (derCertificateChains == null) {
                derCertificateChains = List.of();
            }

            Array payloadArray = new Array();
            for (byte[] certChain : derCertificateChains) {
                payloadArray.add(new ByteString(certChain));
            }
            return new Map().put(CHAINS_KEY, payloadArray);
        }
    }

    public static class CertificateBundle extends Payload {
        public CertificateBundle(byte[] certificateBundle) {
            super(CERTIFICATE_BUNDLE, new ByteString(certificateBundle));
        }
    }

    /** The HAL instance that received the signed certificates. */
    private String halInstance;

    /** The error reason if any. */
    private Optional<String> errorReason;

    private Optional<Payload> payload;

    /** Whether the instance is an error instance. */
    private boolean isError;

    private ConfirmCertificates(
            String halInstance,
            Optional<String> errorReason,
            Optional<Payload> payload,
            boolean isError) {
        this.halInstance = (halInstance == null || halInstance.isEmpty()) ? "unknown" : halInstance;
        this.errorReason = errorReason;
        this.payload = payload;
        this.isError = isError;
    }

    public static ConfirmCertificates createSuccess(String halInstance) {
        return new ConfirmCertificates(
                halInstance,
                Optional.empty(),
                Optional.empty(),
                /* isError= */ false);
    }

    public static ConfirmCertificates createError(
            String halInstance, String reason, Payload payload) {
        // Maximum length of the reason allowed by the server is 256.
        if (reason != null && reason.length() > 256) {
            reason = reason.substring(0, 256);
        }
        return new ConfirmCertificates(
                halInstance,
                Optional.ofNullable(reason),
                Optional.ofNullable(payload),
                /* isError= */ true);
    }

    public byte[] buildCborBytes() throws RkpdException {
        Map confirmCertificatesInfo =
                new Map().put(HAL_INSTANCE_KEY, new UnicodeString(halInstance));
        if (isError) {
            Map errorInfo = new Map();
            errorReason.ifPresent(r -> errorInfo.put(REASON_KEY, new UnicodeString(r)));
            payload.ifPresent(p -> errorInfo.put(p.getLabel(), p.getValue()));
            confirmCertificatesInfo.put(ERROR_INFO_KEY, errorInfo);
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
