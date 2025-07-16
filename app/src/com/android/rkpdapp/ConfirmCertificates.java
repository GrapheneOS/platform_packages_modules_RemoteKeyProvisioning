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
import java.util.Optional;

public class ConfirmCertificates {
    private static final String TAG = "RkpdConfirmCertificates";

    /** The HAL instance that received the signed certificates. */
    private String halInstance;

    /** The error reason if any. */
    private Optional<String> errorReason;

    /**
     * The CBOR encoded certificate chain received from the server. Must be provided if errorReason
     * is provided.
     */
    private Optional<byte[]> cborCertChain;

    private ConfirmCertificates(
            String halInstance, Optional<String> errorReason, Optional<byte[]> cborCertChain) {
        if (halInstance == null || halInstance.isEmpty()) {
            throw new IllegalArgumentException("HAL instance must not be null or empty.");
        }
        this.halInstance = halInstance;
        this.errorReason = errorReason;
        this.cborCertChain = cborCertChain;
    }

    public static ConfirmCertificates createSuccessInstance(String halInstance) {
        return new ConfirmCertificates(halInstance, Optional.empty(), Optional.empty());
    }

    public static ConfirmCertificates createErrorInstance(
            String halInstance, String errorReason, byte[] cborCertChain) {
        if (errorReason == null || errorReason.isEmpty()) {
            throw new IllegalArgumentException("Error reason must not be null or empty.");
        }
        if (cborCertChain == null || cborCertChain.length == 0) {
            throw new IllegalArgumentException("CBOR certificate chain must not be null or empty.");
        }
        return new ConfirmCertificates(
                halInstance, Optional.of(errorReason), Optional.of(cborCertChain));
    }

    public byte[] buildConfirmCertificatesInfo() throws CborException {
        Map confirmCertificatesInfo =
                new Map().put(new UnicodeString("instance"), new UnicodeString(halInstance));
        if (errorReason.isPresent() && cborCertChain.isPresent()) {
            confirmCertificatesInfo.put(
                    new UnicodeString("error_info"),
                    new Map()
                            .put(new UnicodeString("reason"), new UnicodeString(errorReason.get()))
                            .put(
                                    new UnicodeString("signed_certificates"),
                                    new ByteString(cborCertChain.get())));
        }
        return CborUtils.encodeCbor(confirmCertificatesInfo);
    }
}
