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

package com.android.rkpdapp.utils;

import static android.content.pm.PackageManager.MATCH_APEX;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.security.keymint.MacedPublicKey;
import android.os.Build;
import android.util.Log;
import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.RkpKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CborUtils {
    public static final int RESPONSE_CERT_ARRAY_INDEX = 0;
    public static final int RESPONSE_ARRAY_SIZE = 1;

    private static final int SHARED_CERTIFICATES_INDEX = 0;
    private static final int UNIQUE_CERTIFICATES_INDEX = 1;
    private static final int CERT_ARRAY_ENTRIES = 2;

    private static final String TAG = "RkpdCborUtils";
    private static final byte[] EMPTY_MAP = new byte[] {(byte) 0xA0};
    private static final int KEY_PARAMETER_X = -2;
    private static final int KEY_PARAMETER_Y = -3;
    private static final int COSE_HEADER_ALGORITHM = 1;
    private static final int COSE_ALGORITHM_HMAC_256 = 5;

    /**
     * Parses the signed certificate chains returned by the server. In order to reduce data use over
     * the wire, shared certificate chain prefixes are separated from the remaining unique portions
     * of each individual certificate chain. This method first parses the shared prefix certificates
     * and then prepends them to each unique certificate chain. Each PEM-encoded certificate chain
     * is returned in a byte array.
     *
     * @param serverResp The CBOR blob received from the server which contains all signed
     *     certificate chains.
     * @return A List object where each byte[] entry is an entire DER-encoded certificate chain.
     */
    public static List<byte[]> parseSignedCertificates(byte[] serverResp) throws RkpdException {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(serverResp);
            List<DataItem> dataItems = new CborDecoder(bais).decode();
            checkSize(dataItems, RESPONSE_ARRAY_SIZE, "CborResponse");
            checkType(dataItems.get(RESPONSE_CERT_ARRAY_INDEX), MajorType.ARRAY, "CborResponse");
            dataItems = ((Array) dataItems.get(RESPONSE_CERT_ARRAY_INDEX)).getDataItems();
            checkSize(dataItems, CERT_ARRAY_ENTRIES, "CertificateArray");
            checkType(
                    dataItems.get(SHARED_CERTIFICATES_INDEX),
                    MajorType.BYTE_STRING,
                    "SharedCertificates");
            checkType(
                    dataItems.get(UNIQUE_CERTIFICATES_INDEX),
                    MajorType.ARRAY,
                    "UniqueCertificates");
            byte[] sharedCertificates =
                    ((ByteString) dataItems.get(SHARED_CERTIFICATES_INDEX)).getBytes();
            Array uniqueCertificates = (Array) dataItems.get(UNIQUE_CERTIFICATES_INDEX);
            List<byte[]> uniqueCertificateChains = new ArrayList<>();
            for (DataItem entry : uniqueCertificates.getDataItems()) {
                checkType(entry, MajorType.BYTE_STRING, "UniqueCertificate");
                ByteArrayOutputStream concat = new ByteArrayOutputStream();
                // DER encoding specifies certificate chains ordered from leaf to root.
                concat.write(((ByteString) entry).getBytes());
                concat.write(sharedCertificates);
                uniqueCertificateChains.add(concat.toByteArray());
            }
            return uniqueCertificateChains;
        } catch (CborException | IOException e) {
            Log.e(TAG, "Failed to parse signed certificates", e);
            throw new RkpdException(
                    RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Failed to parse signed certificates",
                    e);
        }
    }

    public static void checkType(DataItem item, MajorType majorType, String field)
            throws CborException {
        if (item.getMajorType() != majorType) {
            throw new CborException(
                    "Incorrect CBOR type for field: "
                            + field
                            + ". Expected "
                            + majorType.name()
                            + ". Actual: "
                            + item.getMajorType().name());
        }
    }

    public static void checkSize(List<DataItem> dataItems, int expectedSize, String field)
            throws CborException {
        if (dataItems.size() != expectedSize) {
            throw new CborException(
                    "Incorrect number of items for `"
                            + field
                            + "`. Expected size "
                            + expectedSize
                            + ". Actual: "
                            + dataItems.size());
        }
    }

    /**
     * Creates the bundle of data that the server needs in order to make a decision over what
     * device configuration values to return. In general, this boils down to if remote provisioning
     * is turned on at all or not.
     *
     * @return the CBOR encoded provisioning information relevant to th.
     */
    public static byte[] buildProvisioningInfo(Context context) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addMap()
                        .put("fingerprint", Build.FINGERPRINT)
                        .put("id", Settings.getId(context))
                        .put("version", getPackageVersion(context))
                        .end()
                    .build());
            return baos.toByteArray();
        } catch (CborException e) {
            Log.e(TAG, "CBOR serialization failed.", e);
            return EMPTY_MAP;
        }
    }

    private static long getPackageVersion(Context context) {
        String packageName = context.getPackageName();
        try {
            return context
                .getPackageManager()
                .getPackageInfo(packageName, MATCH_APEX)
                .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error looking up package " + packageName, e);
            return 0;
        }
    }

    /**
     * Takes the various fields fetched from the server and the remote provisioning service and
     * formats them in the CBOR blob the server is expecting as defined by the
     * IRemotelyProvisionedComponent HAL AIDL files.
     */
    public static byte[] buildCertificateRequest(byte[] deviceInfo, byte[] challenge,
            byte[] protectedData, byte[] macedKeysToSign, Map unverifiedDeviceInfo)
            throws RkpdException {
        // This CBOR library doesn't support adding already serialized CBOR structures into a
        // CBOR builder. Because of this, we have to first deserialize the provided parameters
        // back into the library's CBOR object types, and then reserialize them into the
        // desired structure.
        try {
            Array protectedDataArray = (Array) decodeCbor(protectedData, "ProtectedData",
                    MajorType.ARRAY);
            Array macedKeysToSignArray = (Array) decodeCbor(macedKeysToSign, "MacedKeysToSign",
                    MajorType.ARRAY);
            Map verifiedDeviceInfoMap = (Map) decodeCbor(deviceInfo, "DeviceInfo", MajorType.MAP);

            if (unverifiedDeviceInfo.get(new UnicodeString("fingerprint")) == null) {
                Log.e(TAG, "UnverifiedDeviceInfo is missing a fingerprint entry");
                throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                        "UnverifiedDeviceInfo missing fingerprint entry.");
            }
            // Serialize the actual CertificateSigningRequest structure
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addArray()
                        .addArray()
                            .add(verifiedDeviceInfoMap)
                            .add(unverifiedDeviceInfo)
                            .end()
                        .add(challenge)
                        .add(protectedDataArray)
                        .add(macedKeysToSignArray)
                        .end()
                    .build());
            return baos.toByteArray();
        } catch (CborException e) {
            Log.e(TAG, "Malformed CBOR", e);
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR, "Malformed CBOR", e);
        }
    }

    /**
     * Produce a CBOR Map object which contains the unverified device information for a certificate
     * signing request.
     *
     * @return the CBOR Map object.
     */
    public static Map buildUnverifiedDeviceInfo() {
        return new Map()
                .put(new UnicodeString("fingerprint"), new UnicodeString(Build.FINGERPRINT));
    }

    /**
     * Extracts provisioned key for storage from Maced key pair received from underlying binder
     * service.
     */
    public static RkpKey extractRkpKeyFromMacedKey(
            byte[] privKey, String serviceName, MacedPublicKey macedPublicKey)
            throws CborException {
        Array cborMessage = (Array) decodeCbor(macedPublicKey.macedKey, "MacedPublicKeys",
                MajorType.ARRAY);
        List<DataItem> messageArray = cborMessage.getDataItems();
        checkType(messageArray.get(2), MajorType.BYTE_STRING, "MacedPublicKey");
        byte[] macedMessage = ((ByteString) messageArray.get(2)).getBytes();

        Map keyMap = (Map) decodeCbor(macedMessage, "byte stream", MajorType.MAP);
        byte[] xCor = ((ByteString) keyMap.get(new NegativeInteger(KEY_PARAMETER_X))).getBytes();
        if (xCor.length != 32) {
            throw new IllegalStateException("COSE_Key x-coordinate is not correct.");
        }
        byte[] yCor = ((ByteString) keyMap.get(new NegativeInteger(KEY_PARAMETER_Y))).getBytes();
        if (yCor.length != 32) {
            throw new IllegalStateException("COSE_Key y-coordinate is not correct.");
        }
        byte[] rawKey = concatenateByteArrays(xCor, yCor);
        return new RkpKey(privKey, macedPublicKey.macedKey, keyMap, serviceName, rawKey);
    }

    /**
     * Decodes and returns the CBOR encoded DataItem in encodedBytes. Also verifies that the
     * majorType actually matches what is being assumed.
     */
    public static DataItem decodeCbor(byte[] encodedBytes, String debugName, MajorType majorType)
            throws CborException {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedBytes);
        List<DataItem> dataItems = new CborDecoder(bais).decode();
        checkSize(dataItems, RESPONSE_ARRAY_SIZE, debugName);
        checkType(dataItems.get(RESPONSE_CERT_ARRAY_INDEX), majorType, debugName);
        return dataItems.get(0);
    }

    private static byte[] concatenateByteArrays(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Make protected headers for certificate request.
     */
    public static Map makeProtectedHeaders() throws CborException {
        return new Map()
                .put(
                        new UnsignedInteger(COSE_HEADER_ALGORITHM),
                        new UnsignedInteger(COSE_ALGORITHM_HMAC_256));
    }

    /**
     * Encodes CBOR to byte array.
     */
    public static byte[] encodeCbor(final DataItem dataItem) throws CborException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CborEncoder encoder = new CborEncoder(baos);
        encoder.encode(dataItem);
        return baos.toByteArray();
    }
}
