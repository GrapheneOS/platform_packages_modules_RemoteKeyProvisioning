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

import android.content.Context;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.Settings;
import java.util.Locale;
import java.util.Optional;

public class ConfirmCertificates {
    private static final String TAG = "RkpdConfirmCertificates";

    /** The HAL instance that received the signed certificates. */
    private String halInstance;

    /** The environment of the RKP server that issued the signed certificates. */
    private String environment;

    /** The error reason if any. */
    private Optional<String> errorReason;

    /**
     * The CBOR encoded certificate chain received from the server. Must be provided if errorReason
     * is provided.
     */
    private Optional<byte[]> cborCertChain;

    private ConfirmCertificates(
            Context context,
            String halInstance,
            Optional<String> errorReason,
            Optional<byte[]> cborCertChain) {
        if (halInstance == null || halInstance.isEmpty()) {
            throw new IllegalArgumentException("HAL instance must not be null or empty.");
        }
        this.halInstance = halInstance;
        this.environment = getEnvironment(context);
        this.errorReason = errorReason;
        this.cborCertChain = cborCertChain;
    }

    private static String getEnvironment(Context context) {
        String url = Settings.getUrl(context);
        if (url == null || url.isEmpty()) {
            return "prod";
        }
        return url.toLowerCase(Locale.ROOT).contains("preprod") ? "preprod" : "prod";
    }

    public static ConfirmCertificates createSuccessInstance(Context context, String halInstance) {
        return new ConfirmCertificates(context, halInstance, Optional.empty(), Optional.empty());
    }

    public static ConfirmCertificates createErrorInstance(
            Context context, String halInstance, String errorReason, byte[] cborCertChain) {
        if (errorReason == null || errorReason.isEmpty()) {
            throw new IllegalArgumentException("Error reason must not be null or empty.");
        }
        if (cborCertChain == null || cborCertChain.length == 0) {
            throw new IllegalArgumentException("CBOR certificate chain must not be null or empty.");
        }
        return new ConfirmCertificates(
                context, halInstance, Optional.of(errorReason), Optional.of(cborCertChain));
    }

    public byte[] buildConfirmCertificatesInfo() throws CborException {
        Map confirmCertificatesInfo =
                new Map()
                        .put(new UnicodeString("instance"), new UnicodeString(halInstance))
                        .put(new UnicodeString("environment"), new UnicodeString(environment));
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
