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

package com.android.rkpdapp.provisioner;

import android.content.Context;
import android.ext.settings.WidevineProvisioningSettings;
import android.media.DeniedByServerException;
import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.android.rkpd.flags.Flags;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Provides the functionality necessary to provision a Widevine instance running Provisioning 4.0.
 * This class extends the Worker class so that it can be scheduled as a one time work request
 * in the BootReceiver if the device does need to be provisioned. This can technically be handled
 * by any application, but is done within this application for convenience purposes.
 */
public class WidevineProvisioner extends Worker {

    private static final int MAX_RETRIES = 3;
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_PROVISIONING_ROUNDS = 2;

    private static final String TAG = "RkpdWidevine";
    private static final String PROVISIONING_MODEL_PROPERTY = "provisioningModel";
    private static final String PROVISIONING_MODEL_PROV4 = "BootCertificateChain";
    private static final String PROVISIONING_MODEL_SIGMA = "Sigma";

    private static final Map<String, String> REQ_PROPERTIES = new HashMap<>();
    static {
        REQ_PROPERTIES.put("Accept", "*/*");
        REQ_PROPERTIES.put("User-Agent", buildUserAgentString());
        REQ_PROPERTIES.put("Content-Type", "application/json");
        REQ_PROPERTIES.put("Connection", "close");
    }

    public static final UUID WIDEVINE_UUID = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    public WidevineProvisioner(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static String buildUserAgentString() {
        ArrayList<String> parts = new ArrayList<>();
        parts.add("Linux");
        parts.add("Android " + Build.VERSION.RELEASE);
        parts.add(Build.MODEL);
        parts.add(Build.ID);
        parts.add(Build.TYPE);
        return "AndroidRemoteProvisioner (" + String.join("; ", parts) + ")";
    }

    private Result retryOrFail() {
        if (getRunAttemptCount() < MAX_RETRIES) {
            return Result.retry();
        } else {
            return Result.failure();
        }
    }

    /**
     * Overrides the default doWork method to handle checking and provisioning the device's
     * widevine certificate.
     */
    @Override
    public Result doWork() {
        if (isWidevineProvisioningNeeded()) {
            Log.i(TAG, "Starting WV provisioning. Current attempt: " + getRunAttemptCount());
            return provisionWidevine();
        }
        return Result.success();
    }

    /**
     * Helper method to check if provisioning is needed for a given MediaDrm instance.
     * This avoids creating a new MediaDrm object for each check.
     */
    private static boolean isProvisioningNeeded(@NonNull MediaDrm drm) {
        final String provisioningModel = drm.getPropertyString(PROVISIONING_MODEL_PROPERTY);
        final boolean isProv4 = provisioningModel.equals(PROVISIONING_MODEL_PROV4);
        final boolean isSigma = provisioningModel.equals(PROVISIONING_MODEL_SIGMA);
        if (!isProv4 && !isSigma) {
            Log.i(TAG, "Not a WV provisioning 4.0 or Sigma device. No provisioning required.");
            return false;
        }
        try {
            int systemId = Integer.parseInt(drm.getPropertyString("systemId"));
            if (systemId != Integer.MAX_VALUE) {
                Log.i(TAG, "This device has already been provisioned with its WV cert.");
                return false;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed to parse systemId", e);
        }
        return true;
    }

    /**
     * Checks the status of the system in order to determine if stage 1 certificate provisioning
     * for Provisioning 4.0 or Sigma needs to be performed.
     *
     * @return true if the device supports Provisioning 4.0 or Sigma, and the system ID indicates
     *         it has not yet been provisioned.
     */
    public static boolean isWidevineProvisioningNeeded() {
        try (MediaDrm drm = new MediaDrm(WIDEVINE_UUID)) {
            return isProvisioningNeeded(drm);
        } catch (UnsupportedSchemeException e) {
            // Suppress the exception. It isn't particularly informative and may confuse anyone
            // reading the logs.
            Log.i(TAG, "Widevine not supported. No need to provision widevine certificates.");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Something went wrong. Will not provision widevine certificates.", e);
            return false;
        }
    }

    /**
     * Performs the full roundtrip necessary to provision widevine with the first stage cert
     * for Provisioning 4.0, or two roundtrips with the first stage cert for Sigma provisioning.
     *
     * @return A Result indicating whether the attempt succeeded, failed, or should be retried.
     */
    public Result provisionWidevine() {
        try {
            final MediaDrm drm = new MediaDrm(WIDEVINE_UUID);
            final String provisioningModel = drm.getPropertyString(PROVISIONING_MODEL_PROPERTY);
            final boolean isSigma = provisioningModel.equals(PROVISIONING_MODEL_SIGMA);
            if (isSigma && !Flags.enableWidevineMultipleRoundTrips()) {
                Log.e(TAG, "Device is Sigma, but RKPD is not enabled for multiple provisioning "
                        + "round trips.");
                return Result.failure();
            }
            for (int round = 1; round <= MAX_PROVISIONING_ROUNDS; round++) {
                Log.i(TAG, "WV provisioning model: " + provisioningModel + ", round: " + round);
                final MediaDrm.ProvisionRequest request = drm.getProvisionRequest();
                drm.provideProvisionResponse(fetchWidevineCertificate(request));
                if (!isProvisioningNeeded(drm)) {
                    Log.i(TAG, "Widevine provisioning successful.");
                    return Result.success();
                }
            }
            Log.e(TAG, "Widevine provisioning did not complete after "
                    + MAX_PROVISIONING_ROUNDS + " rounds.");
            return Result.failure();
        } catch (UnsupportedSchemeException e) {
            Log.e(TAG, "WV provisioning unsupported. Should not have been able to get here.", e);
            return Result.success();
        } catch (DeniedByServerException e) {
            Log.e(TAG, "WV server denied the provisioning request.", e);
            return Result.failure();
        } catch (IOException e) {
            Log.e(TAG, "WV Provisioning failed.", e);
            return retryOrFail();
        } catch (Exception e) {
            Log.e(TAG, "Safety catch-all in case of an unexpected run time exception:", e);
            return retryOrFail();
        }
    }

    private byte[] fetchWidevineCertificate(MediaDrm.ProvisionRequest req) throws IOException {
        final String origUrlString = req.getDefaultUrl();
        final String urlString;

        final String hostnameOverride = WidevineProvisioningSettings.getServerHostnameOverride(getApplicationContext());
        if (hostnameOverride != null) {
            URL origUrl = new URL(origUrlString);
            urlString = new URL("https", hostnameOverride, origUrl.getFile()).toString();
            Log.d(TAG, "fetchWidevineCertificate: overridden url from " + origUrlString + " to " + urlString);
        } else {
            urlString = origUrlString;
        }

        Log.d(TAG, "fetchWidevineCertificate: urlString: " + urlString);

        return sendNetworkRequest(urlString, req.getData());
    }

    private byte[] sendNetworkRequest(String url, byte[] data) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setDoInput(true);
        con.setConnectTimeout(TIMEOUT_MS);
        con.setReadTimeout(TIMEOUT_MS);

        final byte[] jsonBodyBytes = packageSignedRequestJson(data);
        con.setFixedLengthStreamingMode(jsonBodyBytes.length);

        for (Map.Entry<String, String> prop : REQ_PROPERTIES.entrySet()) {
            con.setRequestProperty(prop.getKey(), prop.getValue());
        }

        try (OutputStream os = con.getOutputStream()) {
            os.write(jsonBodyBytes);
        }
        if (con.getResponseCode() != 200) {
            Log.e(TAG, "Server request for WV certs failed. Error: " + con.getResponseCode());
            throw new IOException("Failed to request WV certs. Error: " + con.getResponseCode());
        }

        BufferedInputStream inputStream = new BufferedInputStream(con.getInputStream());
        ByteArrayOutputStream respBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer, 0, buffer.length)) != -1) {
            respBytes.write(buffer, 0, read);
        }
        byte[] respData = respBytes.toByteArray();
        if (respData.length == 0) {
            Log.e(TAG, "WV server returned an empty response.");
            throw new IOException("WV server returned an empty response.");
        }
        return respData;
    }

    private byte[] packageSignedRequestJson(byte[] requestData) {
        String payload = "{ \"signedRequest\": \""
                + new String(requestData, StandardCharsets.US_ASCII)
                + "\"}";
        return payload.getBytes(StandardCharsets.US_ASCII);
    }
}
