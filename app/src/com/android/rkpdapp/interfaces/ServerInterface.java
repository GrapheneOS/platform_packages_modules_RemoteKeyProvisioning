/*
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

package com.android.rkpdapp.interfaces;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.util.Base64;
import android.util.Log;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.metrics.ProvisioningAttempt;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.StopWatch;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides convenience methods for interfacing with the remote provisioning server.
 */
public class ServerInterface {

    private static final int TIMEOUT_MS = 20000;
    private static final int BACKOFF_TIME_MS = 100;

    private static final String TAG = "RkpdServerInterface";
    private static final String GEEK_URL = ":fetchEekChain";
    private static final String CERTIFICATE_SIGNING_URL = ":signCertificates?";
    private static final String CHALLENGE_PARAMETER = "challenge=";
    private static final String REQUEST_ID_PARAMETER = "request_id=";
    private final Context mContext;

    private enum Operation {
        FETCH_GEEK,
        SIGN_CERTS;

        public ProvisioningAttempt.Status getHttpErrorStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_HTTP_ERROR;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_HTTP_ERROR;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }

        public ProvisioningAttempt.Status getIoExceptionStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_IO_EXCEPTION;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_IO_EXCEPTION;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }

        public ProvisioningAttempt.Status getTimedOutStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_TIMED_OUT;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_TIMED_OUT;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }
    }

    public ServerInterface(Context context) {
        this.mContext = context;
    }

    /**
     * Ferries the CBOR blobs returned by KeyMint to the provisioning server. The data sent to the
     * provisioning server contains the MAC'ed CSRs and encrypted bundle containing the MAC key and
     * the hardware unique public key.
     *
     * @param csr The CBOR encoded data containing the relevant pieces needed for the server to
     *                    sign the CSRs. The data encoded within comes from Keystore / KeyMint.
     * @param challenge The challenge that was sent from the server. It is included here even though
     *                    it is also included in `cborBlob` in order to allow the server to more
     *                    easily reject bad requests.
     * @return A List of byte arrays, where each array contains an entire DER-encoded certificate
     *                    chain for one attestation key pair.
     */
    public List<byte[]> requestSignedCertificates(byte[] csr, byte[] challenge,
            ProvisioningAttempt metrics) throws RkpdException {
        String connectionEndpoint = CERTIFICATE_SIGNING_URL
                + String.join("&",
                      CHALLENGE_PARAMETER + Base64.encodeToString(challenge, Base64.URL_SAFE),
                      REQUEST_ID_PARAMETER + generateAndLogRequestId());
        byte[] cborBytes = connectAndGetData(metrics, connectionEndpoint, csr,
                Operation.SIGN_CERTS);
        List<byte[]> certChains = CborUtils.parseSignedCertificates(cborBytes);
        if (certChains == null) {
            metrics.setStatus(ProvisioningAttempt.Status.INTERNAL_ERROR);
            throw new RkpdException(
                    RkpdException.ErrorCode.INTERNAL_ERROR,
                    "Response failed to parse.");
        }
        return certChains;
    }

    private String generateAndLogRequestId() {
        String reqId = UUID.randomUUID().toString();
        Log.i(TAG, "request_id: " + reqId);
        return reqId;
    }

    /**
     * Calls out to the specified backend servers to retrieve an Endpoint Encryption Key and
     * corresponding certificate chain to provide to KeyMint. This public key will be used to
     * perform an ECDH computation, using the shared secret to encrypt privacy sensitive components
     * in the bundle that the server needs from the device in order to provision certificates.
     *
     * A challenge is also returned from the server so that it can check freshness of the follow-up
     * request to get keys signed.
     *
     * @return A GeekResponse object which optionally contains configuration data.
     */
    public GeekResponse fetchGeek(ProvisioningAttempt metrics) throws RkpdException {
        byte[] input = CborUtils.buildProvisioningInfo(mContext);
        byte[] cborBytes = connectAndGetData(metrics, GEEK_URL, input, Operation.FETCH_GEEK);
        GeekResponse resp = CborUtils.parseGeekResponse(cborBytes);
        if (resp == null) {
            metrics.setStatus(ProvisioningAttempt.Status.FETCH_GEEK_HTTP_ERROR);
            throw new RkpdException(
                    RkpdException.ErrorCode.HTTP_SERVER_ERROR,
                    "Response failed to parse.");
        }
        return resp;
    }

    private void checkDataBudget(ProvisioningAttempt metrics)
            throws RkpdException {
        if (!Settings.hasErrDataBudget(mContext, null /* curTime */)) {
            metrics.setStatus(ProvisioningAttempt.Status.OUT_OF_ERROR_BUDGET);
            int bytesConsumed = Settings.getErrDataBudgetConsumed(mContext);
            throw makeNetworkError("Out of data budget due to repeated errors. Consumed "
                    + bytesConsumed + " bytes.", metrics);
        }
    }

    private RkpdException makeNetworkError(String message,
            ProvisioningAttempt metrics) {
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnected()) {
            return new RkpdException(
                    RkpdException.ErrorCode.NETWORK_COMMUNICATION_ERROR, message);
        }
        metrics.setStatus(ProvisioningAttempt.Status.NO_NETWORK_CONNECTIVITY);
        return new RkpdException(
                RkpdException.ErrorCode.NO_NETWORK_CONNECTIVITY, message);
    }

    /**
     * Fetch a GEEK from the server and update SettingsManager appropriately with the return
     * values. This will also delete all keys in the attestation key pool if the server has
     * indicated that RKP should be turned off.
     */
    public GeekResponse fetchGeekAndUpdate(ProvisioningAttempt metrics)
            throws RkpdException {
        GeekResponse resp = fetchGeek(metrics);

        Settings.setDeviceConfig(mContext,
                resp.numExtraAttestationKeys,
                resp.timeToRefresh,
                resp.provisioningUrl);
        return resp;
    }

    /**
     * Reads error data from the RKP server suitable for logging.
     * @param con The HTTP connection from which to read the error
     * @return The error string, or a description of why we couldn't read an error.
     */
    public static String readErrorFromConnection(HttpURLConnection con) {
        final String contentType = con.getContentType();
        if (!contentType.startsWith("text") && !contentType.startsWith("application/json")) {
            return "Unexpected content type from the server: " + contentType;
        }

        InputStream inputStream;
        try {
            inputStream = con.getInputStream();
        } catch (IOException exception) {
            inputStream = con.getErrorStream();
        }

        if (inputStream == null) {
            return "No error data returned by server.";
        }

        byte[] bytes;
        try {
            bytes = new byte[1024];
            final int read = inputStream.read(bytes);
            if (read <= 0) {
                return "No error data returned by server.";
            }
            bytes = java.util.Arrays.copyOf(bytes, read);
        } catch (IOException e) {
            return "Error reading error string from server: " + e;
        }

        final Charset charset = getCharsetFromContentTypeHeader(contentType);
        return new String(bytes, charset);
    }

    private static Charset getCharsetFromContentTypeHeader(String contentType) {
        final String[] contentTypeParts = contentType.split(";");
        if (contentTypeParts.length != 2) {
            Log.w(TAG, "Simple content type; defaulting to ASCII");
            return StandardCharsets.US_ASCII;
        }

        final String[] charsetParts = contentTypeParts[1].strip().split("=");
        if (charsetParts.length != 2 || !charsetParts[0].equals("charset")) {
            Log.w(TAG, "The charset is missing from content-type, defaulting to ASCII");
            return StandardCharsets.US_ASCII;
        }

        final String charsetString = charsetParts[1].strip();
        try {
            return Charset.forName(charsetString);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unsupported charset: " + charsetString + "; defaulting to ASCII");
            return StandardCharsets.US_ASCII;
        }
    }

    private byte[] connectAndGetData(ProvisioningAttempt metrics, String endpoint, byte[] input,
            Operation operation) throws RkpdException {
        TrafficStats.setThreadStatsTag(0);
        int backoff_time = BACKOFF_TIME_MS;
        int attempt = 1;
        try (StopWatch retryTimer = new StopWatch(TAG)) {
            retryTimer.start();
            while (true) {
                checkDataBudget(metrics);
                try {
                    Log.v(TAG, "Requesting data from server. Attempt " + attempt);
                    return requestData(metrics, new URL(Settings.getUrl(mContext) + endpoint),
                            input);
                } catch (SocketTimeoutException e) {
                    metrics.setStatus(operation.getTimedOutStatus());
                    Log.e(TAG, "Server timed out. " + e.getMessage());
                } catch (IOException e) {
                    metrics.setStatus(operation.getIoExceptionStatus());
                    Log.e(TAG, "Failed to complete request from server." + e.getMessage());
                } catch (RkpdException e) {
                    if (e.getErrorCode() == RkpdException.ErrorCode.DEVICE_NOT_REGISTERED) {
                        metrics.setStatus(
                                ProvisioningAttempt.Status.SIGN_CERTS_DEVICE_NOT_REGISTERED);
                        throw e;
                    } else {
                        metrics.setStatus(operation.getHttpErrorStatus());
                        if (e.getErrorCode() == RkpdException.ErrorCode.HTTP_CLIENT_ERROR) {
                            throw e;
                        }
                    }
                }
                if (retryTimer.getElapsedMillis() > Settings.getMaxRequestTime(mContext)) {
                    break;
                } else {
                    try {
                        Thread.sleep(backoff_time);
                    } catch (InterruptedException e) {
                        // skip wait time
                    }
                    backoff_time *= 2;
                    attempt += 1;
                }
            }
        }
        Settings.incrementFailureCounter(mContext);
        throw makeNetworkError("Error getting data from server.", metrics);
    }

    private byte[] requestData(ProvisioningAttempt metrics, URL url, byte[] input)
            throws IOException, RkpdException {
        int bytesTransacted = 0;
        try (StopWatch serverWaitTimer = metrics.startServerWait()) {
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(input, 0, input.length);
                bytesTransacted += input.length;
            }

            metrics.setHttpStatusError(con.getResponseCode());
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK) {
                int failures = Settings.incrementFailureCounter(mContext);
                Log.e(TAG, "Server connection failed for url: " + url + ", response code: "
                        + con.getResponseCode() + "\nRepeated failure count: " + failures);
                Log.e(TAG, readErrorFromConnection(con));
                throw RkpdException.createFromHttpError(con.getResponseCode());
            }
            serverWaitTimer.stop();
            Settings.clearFailureCounter(mContext);
            BufferedInputStream inputStream = new BufferedInputStream(con.getInputStream());
            ByteArrayOutputStream cborBytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            serverWaitTimer.start();
            while ((read = inputStream.read(buffer, 0, buffer.length)) != -1) {
                cborBytes.write(buffer, 0, read);
                bytesTransacted += read;
            }
            inputStream.close();
            Log.v(TAG, "Network request completed successfully.");
            return cborBytes.toByteArray();
        } catch (Exception e) {
            Settings.consumeErrDataBudget(mContext, bytesTransacted);
            throw e;
        }
    }
}
