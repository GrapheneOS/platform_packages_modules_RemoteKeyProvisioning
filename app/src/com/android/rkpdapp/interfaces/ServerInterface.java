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
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.SystemProperties;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.ConfirmCertificates.PayloadType;
import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.metrics.ProvisioningAttempt;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.NetworkUtils;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.StopWatch;
import com.android.rkpdapp.utils.X509Utils;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides convenience methods for interfacing with the remote provisioning server.
 */
public class ServerInterface {
    public static final int SYNC_CONNECT_TIMEOUT_RETRICTED_MS = 400;
    public static final int SYNC_CONNECT_TIMEOUT_OPEN_MS = 1000;
    public static final int TIMEOUT_MS = 20000;

    private static final int BACKOFF_TIME_MS = 100;

    private static final String TAG = "RkpdServerInterface";
    private static final String GEEK_URL = ":fetchEekChain";
    private static final String CERTIFICATE_SIGNING_URL = ":signCertificates";
    private static final String CONFIRM_CERTIFICATES_URL = ":confirmCertificates";
    private static final String REQUEST_ID_PARAMETER = "request_id";

    private final Context mContext;
    private final boolean mIsAsync;

    private enum Operation {
        FETCH_GEEK(1),
        SIGN_CERTS(2),
        CONFIRM_CERTIFICATES(3);

        private final int mTrafficTag;

        Operation(int trafficTag) {
            mTrafficTag = trafficTag;
        }

        public int getTrafficTag() {
            return mTrafficTag;
        }

        public ProvisioningAttempt.Status getHttpErrorStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_HTTP_ERROR;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_HTTP_ERROR;
            } else if (Objects.equals(name(), CONFIRM_CERTIFICATES.name())) {
                return ProvisioningAttempt.Status.CONFIRM_CERTIFICATES_HTTP_ERROR;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }

        public ProvisioningAttempt.Status getIoExceptionStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_IO_EXCEPTION;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_IO_EXCEPTION;
            } else if (Objects.equals(name(), CONFIRM_CERTIFICATES.name())) {
                return ProvisioningAttempt.Status.CONFIRM_CERTIFICATES_IO_EXCEPTION;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }

        public ProvisioningAttempt.Status getTimedOutStatus() {
            if (Objects.equals(name(), FETCH_GEEK.name())) {
                return ProvisioningAttempt.Status.FETCH_GEEK_TIMED_OUT;
            } else if (Objects.equals(name(), SIGN_CERTS.name())) {
                return ProvisioningAttempt.Status.SIGN_CERTS_TIMED_OUT;
            } else if (Objects.equals(name(), CONFIRM_CERTIFICATES.name())) {
                return ProvisioningAttempt.Status.CONFIRM_CERTIFICATES_TIMED_OUT;
            }
            throw new IllegalStateException("Please declare status for new operation.");
        }
    }

    public ServerInterface(Context context, boolean isAsync) {
        this.mContext = context;
        this.mIsAsync = isAsync;
    }

    /**
     * Gets the system property value for country code for network.
     */
    @VisibleForTesting
    public String getRegionalProperty() {
        return SystemProperties.get("gsm.operator.iso-country");
    }

    /**
     * Gets the server connection timeout in milliseconds.
     */
    @VisibleForTesting
    public int getConnectTimeoutMs() {
        if (mIsAsync) {
            return TIMEOUT_MS;
        }

        int timeout = SystemProperties.getInt("remote_provisioning.connect_timeout_millis", 0);

        // Setting a zero connection timeout doesn't work as it indicates that there is no timeout.
        // Hence, ignoring zero and negative values by default.
        if (timeout > 0) {
            return timeout;
        }

        String regionProperty = getRegionalProperty();
        if (regionProperty == null || regionProperty.isEmpty()) {
            Log.i(TAG, "Could not get regions from system property.");
            return SYNC_CONNECT_TIMEOUT_OPEN_MS;
        }
        String[] regions = regionProperty.split(",");
        if (Arrays.stream(regions).anyMatch(x -> x.equalsIgnoreCase("cn"))) {
            Log.i(TAG, "Possible restricted network. Taking a lower connect timeout");
            return SYNC_CONNECT_TIMEOUT_RETRICTED_MS;
        }
        return SYNC_CONNECT_TIMEOUT_OPEN_MS;
    }

    public void confirmCertificates(
            ConfirmCertificates confirmCertificates, String requestId, ProvisioningAttempt metrics)
            throws RkpdException, InterruptedException {
        if (!Flags.enableFeedbackLoop()) {
            return;
        }

        byte[] cborBytes;
        try {
            cborBytes = confirmCertificates.buildConfirmCertificatesInfo();
        } catch (CborException e) {
            Log.e(
                    TAG,
                    "Failed to build ConfirmCertificatesInfo to be sent to the server. Skipping"
                        + " feedback loop and resetting to defaults.",
                    e);
            Settings.resetDefaultConfig(mContext);
            return;
        }

        final byte[] response =
                connectAndGetData(
                        metrics,
                        generateConfirmCertificatesUrl(requestId),
                        cborBytes,
                        Operation.CONFIRM_CERTIFICATES);

        try {
            // We don't really evaluate the response for now, but do expect it to be an array.
            var unused =
                    CborUtils.decodeCbor(response, "ConfirmCertificatesResponse", MajorType.ARRAY);
        } catch (CborException e) {
            Log.e(
                    TAG,
                    "Failed to parse ConfirmCertificates response from the server. Resetting to"
                            + " defaults.",
                    e);
            Settings.resetDefaultConfig(mContext);
            return;
        }

        // Reset the device config if we successfully sent an error instance to the server.
        // Important to do this after confirmCertificates is called so that the appropriate server
        // instance receives the request.
        if (confirmCertificates.isErrorInstance()) {
            Log.i(TAG, "ConfirmCertificates is an error instance. Resetting to defaults.");
            Settings.resetDefaultConfig(mContext);
        }
    }

    /**
     * Ferries the CBOR blobs returned by KeyMint to the provisioning server. The data sent to the
     * provisioning server contains the MAC'ed CSRs and encrypted bundle containing the MAC key and
     * the hardware unique public key.
     *
     * @param csr The CBOR encoded data containing the relevant pieces needed for the server to sign
     *     the CSRs. The data encoded within comes from Keystore / KeyMint.
     * @return A List of byte arrays, where each array contains an entire DER-encoded certificate
     *     chain for one attestation key pair.
     */
    public List<byte[]> requestSignedCertificates(byte[] csr, ProvisioningAttempt metrics)
            throws RkpdException, InterruptedException {
        return requestSignedCertificates(csr, metrics, Optional.empty(), Optional.empty());
    }

    public List<byte[]> requestSignedCertificates(
            byte[] csr, ProvisioningAttempt metrics, String requestId)
            throws RkpdException, InterruptedException {
        return requestSignedCertificates(csr, metrics, Optional.of(requestId), Optional.empty());
    }

    public List<byte[]> requestSignedCertificates(
            byte[] csr,
            ProvisioningAttempt metrics,
            Optional<String> requestId,
            Optional<SystemInterface> systemInterface)
            throws RkpdException, InterruptedException {
        String reqId = requestId.orElseGet(() -> UUID.randomUUID().toString());
        Log.i(TAG, "request_id: " + reqId);

        final byte[] cborBytes =
                connectAndGetData(metrics, generateSignCertsUrl(reqId), csr, Operation.SIGN_CERTS);
        List<byte[]> certChains = CborUtils.parseSignedCertificates(cborBytes);
        if (certChains == null) {
            metrics.setStatus(ProvisioningAttempt.Status.INTERNAL_ERROR);
            throw new RkpdException(
                    RkpdException.ErrorCode.INTERNAL_ERROR, "Response failed to parse.");
        } else if (certChains.isEmpty()) {
            metrics.setCertChainLength(0);
            metrics.setRootCertFingerprint("");
        } else {
            try {
                X509Certificate[] certs = X509Utils.formatX509Certs(certChains.get(0));
                metrics.setCertChainLength(certs.length);
                byte[] pubKey = certs[certs.length - 1].getPublicKey().getEncoded();
                byte[] pubKeyDigest = MessageDigest.getInstance("SHA-256").digest(pubKey);
                metrics.setRootCertFingerprint(Base64.encodeToString(pubKeyDigest, Base64.DEFAULT));
            } catch (NoSuchAlgorithmException e) {
                throw new RkpdException(
                        RkpdException.ErrorCode.INTERNAL_ERROR, "Algorithm not found", e);
            } catch (RkpdException e) {
                if (Flags.enableFeedbackLoop()) {
                    ConfirmCertificates confirmCertificates =
                            ConfirmCertificates.createErrorInstance(
                                    systemInterface.get().getHalInstanceName(),
                                    e.getMessage(),
                                    certChains.get(0),
                                    PayloadType.DER_CERTIFICATE_CHAIN);
                    confirmCertificates(confirmCertificates, reqId, metrics);
                }
                throw e;
            }
        }
        return certChains;
    }

    private URL generateSignCertsUrl(String requestId) throws RkpdException {
        try {
            return new URL(
                    Uri.parse(Settings.getUrl(mContext))
                            .buildUpon()
                            .appendEncodedPath(CERTIFICATE_SIGNING_URL)
                            .appendQueryParameter(REQUEST_ID_PARAMETER, requestId)
                            .build()
                            .toString()
                            // Needed due to the `:` in the URL endpoint.
                            .replaceFirst("%3A", ":"));
        } catch (MalformedURLException e) {
            throw new RkpdException(RkpdException.ErrorCode.HTTP_CLIENT_ERROR, "Bad URL", e);
        }
    }

    private URL generateConfirmCertificatesUrl(String requestId) throws RkpdException {
        try {
            return new URL(
                    Uri.parse(Settings.getUrl(mContext))
                            .buildUpon()
                            .appendEncodedPath(CONFIRM_CERTIFICATES_URL)
                            .appendQueryParameter(REQUEST_ID_PARAMETER, requestId)
                            .build()
                            .toString()
                            // Needed due to the `:` in the URL endpoint.
                            .replaceFirst("%3A", ":"));
        } catch (MalformedURLException e) {
            throw new RkpdException(RkpdException.ErrorCode.HTTP_CLIENT_ERROR, "Bad URL", e);
        }
    }

    /**
     * Calls out to the specified backend servers to retrieve an Endpoint Encryption Key and
     * corresponding certificate chain to provide to KeyMint. This public key will be used to
     * perform an ECDH computation, using the shared secret to encrypt privacy-sensitive components
     * in the bundle that the server needs from the device in order to provision certificates.
     *
     * A challenge is also returned from the server so that it can check freshness of the follow-up
     * request to get keys signed.
     *
     * @return A GeekResponse object which optionally contains configuration data.
     */
    public GeekResponse fetchGeek(ProvisioningAttempt metrics)
            throws RkpdException, InterruptedException {
        if (!isNetworkConnected(mContext)) {
            throw new RkpdException(RkpdException.ErrorCode.NO_NETWORK_CONNECTIVITY,
                    "No network detected.");
        }
        // Since fetchGeek would be the first call for any sort of provisioning, we are okay
        // checking network consent here.
        if (!NetworkUtils.assumeNetworkConsent(mContext)) {
            throw new RkpdException(RkpdException.ErrorCode.NETWORK_COMMUNICATION_ERROR,
                    "Network communication consent not provided. Need to enable GMSCore app.");
        }

        String requestId = UUID.randomUUID().toString();
        Log.i(TAG, "request_id: " + requestId);

        byte[] input = CborUtils.buildProvisioningInfo(mContext);
        byte[] cborBytes =
                connectAndGetData(
                        metrics, generateFetchGeekUrl(requestId), input, Operation.FETCH_GEEK);
        GeekResponse resp = GeekResponse.parse(cborBytes);
        if (Flags.enableRequestIdReuse()) {
            resp.setRequestId(requestId);
        }
        if (resp == null) {
            metrics.setStatus(ProvisioningAttempt.Status.FETCH_GEEK_HTTP_ERROR);
            throw new RkpdException(
                    RkpdException.ErrorCode.HTTP_SERVER_ERROR,
                    "Response failed to parse.");
        }
        return resp;
    }

    private URL generateFetchGeekUrl(String requestId) throws RkpdException {
        Uri.Builder uriBuilder =
                Uri.parse(Settings.getUrl(mContext)).buildUpon().appendPath(GEEK_URL);
        if (Flags.enableRequestIdReuse()) {
            uriBuilder.appendQueryParameter(REQUEST_ID_PARAMETER, requestId);
        }
        try {
            return new URL(
                    uriBuilder
                            .build()
                            .toString()
                            // Needed due to the `:` in the URL endpoint.
                            .replaceFirst("%3A", ":"));
        } catch (MalformedURLException e) {
            throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR, "Bad URL", e);
        }
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
        if (isNetworkConnected(mContext)) {
            return new RkpdException(
                    RkpdException.ErrorCode.NETWORK_COMMUNICATION_ERROR, message);
        }
        metrics.setStatus(ProvisioningAttempt.Status.NO_NETWORK_CONNECTIVITY);
        return new RkpdException(
                RkpdException.ErrorCode.NO_NETWORK_CONNECTIVITY, message);
    }

    /**
     * Checks whether network is connected.
     * @return true if connected else false.
     */
    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Fetch a GEEK from the server and update SettingsManager appropriately with the return
     * values. This will also delete all keys in the attestation key pool if the server has
     * indicated that RKP should be turned off.
     */
    public GeekResponse fetchGeekAndUpdate(ProvisioningAttempt metrics)
            throws InterruptedException, RkpdException {
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

    private byte[] connectAndGetData(ProvisioningAttempt metrics, URL url, byte[] input,
            Operation operation) throws RkpdException, InterruptedException {
        final int oldTrafficTag = TrafficStats.getAndSetThreadStatsTag(operation.getTrafficTag());
        int backoff_time = BACKOFF_TIME_MS;
        int attempt = 1;
        RkpdException lastSeenRkpdException;
        try (StopWatch retryTimer = new StopWatch(TAG)) {
            retryTimer.start();
            // Retry logic.
            // Provide longer retries (up to 10s) for RkpdExceptions
            // Provide shorter retries (once) for everything else.
            while (true) {
                lastSeenRkpdException = null;
                checkDataBudget(metrics);
                try {
                    Log.v(TAG, "Requesting data from server. Attempt " + attempt);
                    return requestData(metrics, url, input);
                } catch (SocketTimeoutException e) {
                    metrics.setStatus(operation.getTimedOutStatus());
                    Log.e(TAG, "Server timed out. " + e.getMessage());
                } catch (IOException e) {
                    metrics.setStatus(operation.getIoExceptionStatus());
                    Log.e(TAG, "Failed to complete request from server. " + e.getMessage());
                } catch (RkpdException e) {
                    lastSeenRkpdException = e;
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
                // Only RkpdExceptions should get retries.
                if (retryTimer.getElapsedMillis() > Settings.getMaxRequestTime(mContext)
                        || lastSeenRkpdException == null) {
                    break;
                }
                Thread.sleep(backoff_time);
                backoff_time *= 2;
                attempt += 1;
            }
        } finally {
            TrafficStats.setThreadStatsTag(oldTrafficTag);
        }
        if (lastSeenRkpdException != null) {
            throw lastSeenRkpdException;
        }
        Settings.incrementFailureCounter(mContext);
        throw makeNetworkError("Error getting data from server.", metrics);
    }

    private byte[] requestData(ProvisioningAttempt metrics, URL url, byte[] input)
            throws IOException, RkpdException {
        int bytesTransacted = 0;
        HttpURLConnection con = null;
        try (StopWatch serverWaitTimer = metrics.startServerWait()) {
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(getConnectTimeoutMs());
            con.setReadTimeout(TIMEOUT_MS);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(input.length);

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
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }
}
