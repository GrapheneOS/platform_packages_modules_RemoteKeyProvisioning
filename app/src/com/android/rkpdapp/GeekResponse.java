/**
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

package com.android.rkpdapp;

import android.util.Log;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.android.rkpdapp.database.InstantConverter;
import com.android.rkpdapp.utils.CborUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Convenience class for packaging up the values returned by the server when initially requesting
 * an Endpoint Encryption Key for remote provisioning. Those values are described by the following
 * CDDL Schema:
 *    GeekResponse = [
 *        [+CurveAndEek],
 *        challenge : bstr,
 *        ? Config,
 *    ]
 *    CurveAndEek = [
 *        curve: uint,
 *        EekChain
 *    ]
 *    Config = {
 *        ? "num_extra_attestation_keys": uint,
 *        ? "time_to_refresh_hours" : uint,
 *        ? "provisioning_url": tstr,
 *    }
 *
 * The CDDL that defines EekChain is defined in the RemoteProvisioning HAL, but this app does not
 * require any semantic understanding of the format to perform its function.
 */
public class GeekResponse {
    public static final int NO_EXTRA_KEY_UPDATE = -1;
    private static final String TAG = "RkpdGeekResponse";

    public static final int EC_CURVE_P256 = 1;
    public static final int EC_CURVE_25519 = 2;

    private static final int EEK_AND_CURVE_INDEX = 0;
    private static final int CHALLENGE_INDEX = 1;
    private static final int CONFIG_INDEX = 2;

    private static final int CURVE_AND_EEK_CHAIN_LENGTH = 2;
    private static final int CURVE_INDEX = 0;
    private static final int EEK_CERT_CHAIN_INDEX = 1;

    private static final int EEK_ARRAY_ENTRIES_NO_CONFIG = 2;
    private static final int EEK_ARRAY_ENTRIES_WITH_CONFIG = 3;

    public static final String EXTRA_KEYS = "num_extra_attestation_keys";
    public static final String TIME_TO_REFRESH = "time_to_refresh_hours";
    public static final String PROVISIONING_URL = "provisioning_url";
    public static final String LAST_BAD_CERT_TIME_START_MILLIS = "bad_cert_start";
    public static final String LAST_BAD_CERT_TIME_END_MILLIS = "bad_cert_end";

    private byte[] challenge;
    private final java.util.Map<Integer, byte[]> curveToGeek;
    public final String requestId;
    public int numExtraAttestationKeys;
    public Duration timeToRefresh;
    public String provisioningUrl;
    public Instant lastBadCertTimeStart;
    public Instant lastBadCertTimeEnd;

    /**
     * Default initializer.
     */
    public GeekResponse() {
        curveToGeek = new HashMap<>();
        numExtraAttestationKeys = NO_EXTRA_KEY_UPDATE;
        lastBadCertTimeStart = null;
        lastBadCertTimeEnd = null;
        requestId = UUID.randomUUID().toString();
    }

    /**
     * Parses the Google Endpoint Encryption Key response provided by the server which contains a
     * Google signed EEK and a challenge for use by the underlying IRemotelyProvisionedComponent HAL
     */
    public static GeekResponse parse(byte[] serverResp) {
        try {
            GeekResponse resp = new GeekResponse();
            ByteArrayInputStream bais = new ByteArrayInputStream(serverResp);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            CborUtils.checkSize(dataItems, CborUtils.RESPONSE_ARRAY_SIZE, "GeekResponse");
            CborUtils.checkType(
                    dataItems.get(CborUtils.RESPONSE_CERT_ARRAY_INDEX),
                    MajorType.ARRAY,
                    "CborResponse");
            List<DataItem> respItems =
                    ((Array) dataItems.get(CborUtils.RESPONSE_CERT_ARRAY_INDEX)).getDataItems();
            if (respItems.size() != EEK_ARRAY_ENTRIES_NO_CONFIG
                    && respItems.size() != EEK_ARRAY_ENTRIES_WITH_CONFIG) {
                throw new CborException(
                        "Incorrect number of certificate array entries. Expected: "
                                + EEK_ARRAY_ENTRIES_NO_CONFIG
                                + " or "
                                + EEK_ARRAY_ENTRIES_WITH_CONFIG
                                + ". Actual: "
                                + respItems.size());
            }
            CborUtils.checkType(
                    respItems.get(EEK_AND_CURVE_INDEX), MajorType.ARRAY, "EekAndCurveArr");
            List<DataItem> curveAndEekChains =
                    ((Array) respItems.get(EEK_AND_CURVE_INDEX)).getDataItems();
            for (int i = 0; i < curveAndEekChains.size(); i++) {
                CborUtils.checkType(curveAndEekChains.get(i), MajorType.ARRAY, "EekAndCurve");
                List<DataItem> curveAndEekChain = ((Array) curveAndEekChains.get(i)).getDataItems();
                CborUtils.checkSize(
                        curveAndEekChain, CURVE_AND_EEK_CHAIN_LENGTH, "CurveAndEekChain");
                CborUtils.checkType(
                        curveAndEekChain.get(CURVE_INDEX), MajorType.UNSIGNED_INTEGER, "Curve");
                CborUtils.checkType(
                        curveAndEekChain.get(EEK_CERT_CHAIN_INDEX),
                        MajorType.ARRAY,
                        "EekCertChain");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                new CborEncoder(baos).encode(curveAndEekChain.get(EEK_CERT_CHAIN_INDEX));
                UnsignedInteger curve = (UnsignedInteger) curveAndEekChain.get(CURVE_INDEX);
                resp.addGeek(curve.getValue().intValue(), baos.toByteArray());
            }
            CborUtils.checkType(respItems.get(CHALLENGE_INDEX), MajorType.BYTE_STRING, "Challenge");
            resp.setChallenge(((ByteString) respItems.get(CHALLENGE_INDEX)).getBytes());
            if (respItems.size() == EEK_ARRAY_ENTRIES_WITH_CONFIG) {
                parseDeviceConfig(resp, respItems.get(CONFIG_INDEX));
            }
            return resp;
        } catch (CborException e) {
            Log.e(TAG, "CBOR parsing/serializing failed.", e);
            return null;
        }
    }

    private static void parseDeviceConfig(GeekResponse resp, DataItem deviceConfig)
            throws CborException {
        CborUtils.checkType(deviceConfig, MajorType.MAP, "DeviceConfig");
        Map deviceConfiguration = (Map) deviceConfig;
        DataItem extraKeys = deviceConfiguration.get(new UnicodeString(EXTRA_KEYS));
        DataItem timeToRefreshHours = deviceConfiguration.get(new UnicodeString(TIME_TO_REFRESH));
        DataItem newUrl = deviceConfiguration.get(new UnicodeString(PROVISIONING_URL));
        DataItem lastBadCertTimeStart =
                deviceConfiguration.get(new UnicodeString(LAST_BAD_CERT_TIME_START_MILLIS));
        DataItem lastBadCertTimeEnd =
                deviceConfiguration.get(new UnicodeString(LAST_BAD_CERT_TIME_END_MILLIS));
        if (extraKeys != null) {
            CborUtils.checkType(extraKeys, MajorType.UNSIGNED_INTEGER, "ExtraKeys");
            resp.numExtraAttestationKeys = ((UnsignedInteger) extraKeys).getValue().intValue();
        }
        if (timeToRefreshHours != null) {
            CborUtils.checkType(timeToRefreshHours, MajorType.UNSIGNED_INTEGER, "TimeToRefresh");
            resp.timeToRefresh =
                    Duration.ofHours(((UnsignedInteger) timeToRefreshHours).getValue().intValue());
        }
        if (newUrl != null) {
            String receivedUrl = ((UnicodeString) newUrl).getString();
            CborUtils.checkType(newUrl, MajorType.UNICODE_STRING, "ProvisioningURL");
            try {
                URI uri = new URI(receivedUrl);
                if (uri.isAbsolute()) {
                    resp.provisioningUrl = receivedUrl;
                } else {
                    Log.e(TAG, "Ignoring relative URI received from server: " + receivedUrl);
                }
            } catch (URISyntaxException e) {
                Log.e(TAG, "Ignoring invalid URL syntax received from server: " + receivedUrl, e);
            }
        }
        if (lastBadCertTimeStart != null) {
            CborUtils.checkType(
                    lastBadCertTimeStart, MajorType.UNSIGNED_INTEGER, "BadCertTimeStart");
            resp.lastBadCertTimeStart =
                    InstantConverter.fromTimestamp(
                            ((UnsignedInteger) lastBadCertTimeStart).getValue().longValue());
        }
        if (lastBadCertTimeEnd != null) {
            CborUtils.checkType(lastBadCertTimeEnd, MajorType.UNSIGNED_INTEGER, "BadCertTimeEnd");
            resp.lastBadCertTimeEnd =
                    InstantConverter.fromTimestamp(
                            ((UnsignedInteger) lastBadCertTimeEnd).getValue().longValue());
        }
    }

    /**
     * Add a CBOR encoded array containing a GEEK and the corresponding certificate chain, keyed
     * on the EC {@code curve}.
     *
     * @param curve an integer which represents an EC curve.
     * @param geekChain the encoded CBOR array containing an ECDH key and corresponding certificate
     *                  chain.
     */
    public void addGeek(int curve, byte[] geekChain) {
        curveToGeek.put(curve, geekChain);
    }

    /**
     * Returns the encoded CBOR array with an ECDH key corresponding to the provided {@code curve}.
     *
     * @param curve an integer which represents an EC curve.
     * @return the corresponding encoded CBOR array.
     */
    public byte[] getGeekChain(int curve) {
        return curveToGeek.get(curve);
    }

    /**
     * Sets the {@code challenge}.
     */
    public void setChallenge(byte[] challenge) {
        this.challenge = challenge;
    }

    /**
     * Returns the {@code challenge}.
     *
     * @return the challenge that will be embedded in the CSR sent to the server.
     */
    public byte[] getChallenge() {
        return challenge;
    }
}
