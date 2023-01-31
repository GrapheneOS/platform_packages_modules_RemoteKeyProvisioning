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

package com.android.rkpdapp.unittest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.NetworkInfo;
import android.security.NetworkSecurityPolicy;
import android.util.Base64;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.utils.CborUtils;
import com.android.rkpdapp.utils.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

public class ServerInterfaceTest {
    private static final String TAG = "RkpdServerInterfaceTest";
    private static final Duration TIME_TO_REFRESH_HOURS = Duration.ofHours(2);
    private static final byte[] GEEK_RESPONSE = Base64.decode(
            "g4KCAYOEQ6EBJqBYTaUBAgMmIAEhWCD3FIrbl/TMU+/SZBHE43UfZh+kcQxsz/oJRoB0h1TyrSJY"
                    + "IF5/W/bs5PYZzP8TN/0PociT2xgGdsRd5tdqd4bDLa+PWEAvl45C+74HLZVHhUeTQLAf1JtHpMRE"
                    + "qfKhB4cQx5/LEfS/n+g74Oc0TBX8e8N+MwX00TQ87QIEYHoV4HnTiv8khEOhASagWE2lAQIDJiAB"
                    + "IVggUYCsz4+WjOwPUOGpG7eQhjSL48OsZQJNtPYxDghGMjkiWCBU65Sd/ra05HM6JU4vH52dvfpm"
                    + "wRGL6ZaMQ+Qw9tp2q1hAmDj7NDpl23OYsSeiFXTyvgbnjSJO3fC/wgF0xLcpayQctdjSZvpE7/Uw"
                    + "LAR07ejGYNrOn1ZXJ3Qh096Tj+O4zYRDoQEmoFhxpgECAlggg5/4/RAcEp+SQcdbjeRO9BkTmscb"
                    + "bacOlfJkU12nHcEDOBggASFYIBakUhJjs4ZWUNjf8qCofbzZbqdoYOqMXPGT5ZcZDazeIlggib7M"
                    + "bD9esDk0r5e6ONEWHaHMHWTTjEhO+HKBGzs+Me5YQPrazy2rpTAMc8Xlq0mSWWBE+sTyM+UEsmwZ"
                    + "ZOkc42Q7NIYAZS313a+qAcmvg8lO+FqU6GWTUeMYHjmAp2lLM82CAoOEQ6EBJ6BYKqQBAQMnIAYh"
                    + "WCCZue7dXuRS9oXGTGLcPmGrV0h9dTcprXaAMtKzy2NY2VhAHiIIS6S3pMjXTgMO/rivFEynO2+l"
                    + "zdzaecYrZP6ZOa9254D6ZgCFDQeYKqyRXKclFEkGNHXKiid62eNaSesCA4RDoQEnoFgqpAEBAycg"
                    + "BiFYIOovhQ6eagxc973Z+igyv9pV6SCiUQPJA5MYzqAVKezRWECCa8ddpjZXt8dxEq0cwmqzLCMq"
                    + "3RQwy4IUtonF0x4xu7hQIUpJTbqRDG8zTYO8WCsuhNvFWQ+YYeLB6ony0K4EhEOhASegWE6lAQEC"
                    + "WCBvktEEbXHYp46I2NFWgV+W0XiD5jAbh+2/INFKO/5qLgM4GCAEIVggtl0cS5qDOp21FVk3oSb7"
                    + "D9/nnKwB1aTsyDopAIhYJTlYQICyn9Aynp1K/rAl8sLSImhGxiCwqugWrGShRYObzElUJX+rFgVT"
                    + "8L01k/PGu1lOXvneIQcUo7ako4uPgpaWugNYHQAAAYBINcxrASC0rWP9VTSO7LdABvcdkv7W2vh+"
                    + "onV0aW1lX3RvX3JlZnJlc2hfaG91cnMYSHgabnVtX2V4dHJhX2F0dGVzdGF0aW9uX2tleXMU",
            Base64.DEFAULT);

    // Same as GEEK_RESPONSE, but the "num_extra_attestation_keys" value is 0, disabling RKP.
    private static final byte[] GEEK_RESPONSE_RKP_DISABLED = Base64.decode(
            "g4KCAYOEQ6EBJqBYTaUBAgMmIAEhWCD3FIrbl/TMU+/SZBHE43UfZh+kcQxsz/oJRoB0h1TyrSJY"
                    + "IF5/W/bs5PYZzP8TN/0PociT2xgGdsRd5tdqd4bDLa+PWEAvl45C+74HLZVHhUeTQLAf1JtHpMRE"
                    + "qfKhB4cQx5/LEfS/n+g74Oc0TBX8e8N+MwX00TQ87QIEYHoV4HnTiv8khEOhASagWE2lAQIDJiAB"
                    + "IVggUYCsz4+WjOwPUOGpG7eQhjSL48OsZQJNtPYxDghGMjkiWCBU65Sd/ra05HM6JU4vH52dvfpm"
                    + "wRGL6ZaMQ+Qw9tp2q1hAmDj7NDpl23OYsSeiFXTyvgbnjSJO3fC/wgF0xLcpayQctdjSZvpE7/Uw"
                    + "LAR07ejGYNrOn1ZXJ3Qh096Tj+O4zYRDoQEmoFhxpgECAlggg5/4/RAcEp+SQcdbjeRO9BkTmscb"
                    + "bacOlfJkU12nHcEDOBggASFYIBakUhJjs4ZWUNjf8qCofbzZbqdoYOqMXPGT5ZcZDazeIlggib7M"
                    + "bD9esDk0r5e6ONEWHaHMHWTTjEhO+HKBGzs+Me5YQPrazy2rpTAMc8Xlq0mSWWBE+sTyM+UEsmwZ"
                    + "ZOkc42Q7NIYAZS313a+qAcmvg8lO+FqU6GWTUeMYHjmAp2lLM82CAoOEQ6EBJ6BYKqQBAQMnIAYh"
                    + "WCCZue7dXuRS9oXGTGLcPmGrV0h9dTcprXaAMtKzy2NY2VhAHiIIS6S3pMjXTgMO/rivFEynO2+l"
                    + "zdzaecYrZP6ZOa9254D6ZgCFDQeYKqyRXKclFEkGNHXKiid62eNaSesCA4RDoQEnoFgqpAEBAycg"
                    + "BiFYIOovhQ6eagxc973Z+igyv9pV6SCiUQPJA5MYzqAVKezRWECCa8ddpjZXt8dxEq0cwmqzLCMq"
                    + "3RQwy4IUtonF0x4xu7hQIUpJTbqRDG8zTYO8WCsuhNvFWQ+YYeLB6ony0K4EhEOhASegWE6lAQEC"
                    + "WCBvktEEbXHYp46I2NFWgV+W0XiD5jAbh+2/INFKO/5qLgM4GCAEIVggtl0cS5qDOp21FVk3oSb7"
                    + "D9/nnKwB1aTsyDopAIhYJTlYQICyn9Aynp1K/rAl8sLSImhGxiCwqugWrGShRYObzElUJX+rFgVT"
                    + "8L01k/PGu1lOXvneIQcUo7ako4uPgpaWugNYHQAAAYBINcxrASC0rWP9VTSO7LdABvcdkv7W2vh+"
                    + "onV0aW1lX3RvX3JlZnJlc2hfaG91cnMYSHgabnVtX2V4dHJhX2F0dGVzdGF0aW9uX2tleXMA",
            Base64.DEFAULT);

    private static Context sContext;
    private ServerInterface mServerInterface;
    private boolean mCleartextPolicy;

    @BeforeClass
    public static void init() {
        sContext = ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Settings.clearPreferences(sContext);
        mServerInterface = new ServerInterface(sContext);
        mCleartextPolicy =
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted();
        NetworkSecurityPolicy.getInstance().setCleartextTrafficPermitted(true);
    }

    @After
    public void tearDown() {
        Settings.clearPreferences(sContext);
        NetworkSecurityPolicy.getInstance().setCleartextTrafficPermitted(mCleartextPolicy);
    }

    @Test
    public void testFetchGeekRkpDisabled() throws IOException, RkpdException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE_RKP_DISABLED,
                HttpResponse.HTTP_OK_VALID_CBOR);
        Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        GeekResponse response = mServerInterface.fetchGeek(
                ProvisionerMetrics.createScheduledAttemptMetrics(sContext));

        assertThat(response.numExtraAttestationKeys).isEqualTo(0);
        assertThat(response.getChallenge()).isNotNull();
        assertThat(response.getGeekChain(2)).isNotNull();
    }

    @Test
    public void testFetchGeekRkpEnabled() throws IOException, RkpdException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_OK_VALID_CBOR);
        Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        GeekResponse response = mServerInterface.fetchGeek(
                ProvisionerMetrics.createScheduledAttemptMetrics(sContext));

        assertThat(response.numExtraAttestationKeys).isEqualTo(20);
        assertThat(response.getChallenge()).isNotNull();
        byte[] challenge = Base64.decode("AAABgEg1zGsBILStY/1VNI7st0AG9x2S/tba+H4=",
                Base64.DEFAULT);
        assertThat(response.getChallenge()).isEqualTo(challenge);
        byte[] ed25519GeekChain = Base64.decode("g4RDoQEnoFgqpAEBAycgBiFYIJm57t1e5FL2hcZMYtw+YatXS"
                + "H11NymtdoAy0rPLY1jZWEAeIghLpLekyNdOAw7+uK8UTKc7b6XN3Np5xitk/pk5r3bngPpmAIUNB5gq"
                + "rJFcpyUUSQY0dcqKJ3rZ41pJ6wIDhEOhASegWCqkAQEDJyAGIVgg6i+FDp5qDFz3vdn6KDK/2lXpIKJ"
                + "RA8kDkxjOoBUp7NFYQIJrx12mNle3x3ESrRzCarMsIyrdFDDLghS2icXTHjG7uFAhSklNupEMbzNNg7"
                + "xYKy6E28VZD5hh4sHqifLQrgSEQ6EBJ6BYTqUBAQJYIG+S0QRtcdinjojY0VaBX5bReIPmMBuH7b8g0"
                + "Uo7/mouAzgYIAQhWCC2XRxLmoM6nbUVWTehJvsP3+ecrAHVpOzIOikAiFglOVhAgLKf0DKenUr+sCXy"
                + "wtIiaEbGILCq6BasZKFFg5vMSVQlf6sWBVPwvTWT88a7WU5e+d4hBxSjtqSji4+Clpa6Aw==",
                Base64.DEFAULT);
        byte[] p256GeekChain = Base64.decode("g4RDoQEmoFhNpQECAyYgASFYIPcUituX9MxT79JkEcTjdR9mH6Rx"
                + "DGzP+glGgHSHVPKtIlggXn9b9uzk9hnM/xM3/Q+hyJPbGAZ2xF3m12p3hsMtr49YQC+XjkL7vgctlUe"
                + "FR5NAsB/Um0ekxESp8qEHhxDHn8sR9L+f6Dvg5zRMFfx7w34zBfTRNDztAgRgehXgedOK/ySEQ6EBJq"
                + "BYTaUBAgMmIAEhWCBRgKzPj5aM7A9Q4akbt5CGNIvjw6xlAk209jEOCEYyOSJYIFTrlJ3+trTkczolT"
                + "i8fnZ29+mbBEYvploxD5DD22narWECYOPs0OmXbc5ixJ6IVdPK+BueNIk7d8L/CAXTEtylrJBy12NJm"
                + "+kTv9TAsBHTt6MZg2s6fVlcndCHT3pOP47jNhEOhASagWHGmAQICWCCDn/j9EBwSn5JBx1uN5E70GRO"
                + "axxttpw6V8mRTXacdwQM4GCABIVggFqRSEmOzhlZQ2N/yoKh9vNlup2hg6oxc8ZPllxkNrN4iWCCJvs"
                + "xsP16wOTSvl7o40RYdocwdZNOMSE74coEbOz4x7lhA+trPLaulMAxzxeWrSZJZYET6xPIz5QSybBlk6"
                + "RzjZDs0hgBlLfXdr6oBya+DyU74WpToZZNR4xgeOYCnaUszzQ==",
                Base64.DEFAULT);
        assertThat(response.getGeekChain(CborUtils.EC_CURVE_25519)).isEqualTo(ed25519GeekChain);
        assertThat(response.getGeekChain(CborUtils.EC_CURVE_P256)).isEqualTo(p256GeekChain);
    }

    @Test
    public void testFetchKeyAndUpdate() throws IOException, RkpdException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_OK_VALID_CBOR);
        Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        mServerInterface.fetchGeekAndUpdate(
                ProvisionerMetrics.createScheduledAttemptMetrics(sContext));

        assertThat(Settings.getExtraSignedKeysAvailable(sContext)).isEqualTo(20);
        assertThat(Settings.getExpiringBy(sContext)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    public void testRequestSignedCertUnregistered() throws IOException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_DEVICE_UNREGISTERED);
        Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
        try {
            mServerInterface.requestSignedCertificates(new byte[0], new byte[0], metrics);
            fail("Should fail due to unregistered device.");
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.DEVICE_NOT_REGISTERED);
        }
    }

    @Test
    public void testRequestSignedCertClientError() throws IOException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_USER_UNAUTHORIZED);
        Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
        try {
            mServerInterface.requestSignedCertificates(new byte[0], new byte[0], metrics);
            fail("Should fail due to client error.");
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.HTTP_CLIENT_ERROR);
        }
    }

    @Test
    public void testRequestSignedCertCborError() throws IOException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_OK_INVALID_CBOR);
        Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
        try {
            mServerInterface.requestSignedCertificates(new byte[0], new byte[0], metrics);
            fail("Should fail due to invalid cbor.");
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.INTERNAL_ERROR);
            assertThat(e).hasMessageThat().isEqualTo("Response failed to parse.");
        }
    }

    @Test
    public void testRequestSignedCertValid() throws IOException, RkpdException {
        final String url = setupServerAndGetUrl(GEEK_RESPONSE,
                HttpResponse.HTTP_OK_VALID_CBOR);
        Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, url);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
        List<byte[]> certChains = mServerInterface.requestSignedCertificates(new byte[0],
                new byte[0], metrics);
        assertThat(certChains).isEmpty();
        assertThat(certChains).isNotNull();
    }

    @Test
    public void testDataBudgetEmptyFetchGeekNetworkConnected() {
        // Check the data budget in order to initialize a rolling window.
        assertThat(Settings.hasErrDataBudget(sContext, null /* curTime */)).isTrue();
        Settings.consumeErrDataBudget(sContext, Settings.FAILURE_DATA_USAGE_MAX);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
        try {
            mServerInterface.fetchGeek(metrics);
            fail("Network transaction should not have proceeded.");
        } catch (RkpdException e) {
            assertThat(e).hasMessageThat().contains("Out of data budget due to repeated errors");
            assertThat(e.getErrorCode()).isEqualTo(
                    RkpdException.ErrorCode.NETWORK_COMMUNICATION_ERROR);
        }
    }

    @Test
    public void testDataBudgetEmptyFetchGeekNetworkDisconnected() throws Exception {
        // Check the data budget in order to initialize a rolling window.
        try {
            setEthernetEnabled(false);
            setAirplaneMode(true);
            assertThat(Settings.hasErrDataBudget(sContext, null /* curTime */)).isTrue();
            Settings.consumeErrDataBudget(sContext, Settings.FAILURE_DATA_USAGE_MAX);
            ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(sContext);
            mServerInterface.fetchGeek(metrics);
            fail("Network transaction should not have proceeded.");
        } catch (RkpdException e) {
            assertThat(e).hasMessageThat().contains("Out of data budget due to repeated errors");
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.NO_NETWORK_CONNECTIVITY);
        } finally {
            setEthernetEnabled(true);
            setAirplaneMode(false);
        }
    }

    @Test
    public void testReadErrorInvalidContentType() {
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("application/NOPE");
        assertThat(ServerInterface.readErrorFromConnection(connection))
                .isEqualTo("Unexpected content type from the server: application/NOPE");
    }

    @Test
    public void testReadTextErrorFromErrorStreamNoErrorData() throws IOException {
        final String expectedError = "No error data returned by server.";

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream()).thenThrow(new IOException());
        Mockito.when(connection.getErrorStream()).thenReturn(null);

        assertThat(ServerInterface.readErrorFromConnection(connection)).isEqualTo(expectedError);
    }

    @Test
    public void testReadTextErrorFromErrorStream() throws IOException {
        final String error = "Explanation for error goes here.";

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream()).thenThrow(new IOException());
        Mockito.when(connection.getErrorStream())
                .thenReturn(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));

        assertThat(ServerInterface.readErrorFromConnection(connection)).isEqualTo(error);
    }

    @Test
    public void testReadTextError() throws IOException {
        final String error = "This is an error.  Oh No.";
        final String[] textContentTypes = new String[] {
                "text",
                "text/ANYTHING",
                "text/what-is-this; charset=unknown",
                "text/lowercase; charset=utf-8",
                "text/uppercase; charset=UTF-8",
                "text/yolo; charset=ASCII"
        };

        for (String contentType: textContentTypes) {
            HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
            Mockito.when(connection.getContentType()).thenReturn(contentType);
            Mockito.when(connection.getInputStream())
                    .thenReturn(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));

            assertWithMessage("Failed on content type '" + contentType + "'")
                    .that(error)
                    .isEqualTo(ServerInterface.readErrorFromConnection(connection));
        }
    }

    @Test
    public void testReadJsonError() throws IOException {
        final String error = "Not really JSON.";

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("application/json");
        Mockito.when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));

        assertThat(ServerInterface.readErrorFromConnection(connection)).isEqualTo(error);
    }

    @Test
    public void testReadErrorStreamThrowsException() throws IOException {
        InputStream stream = Mockito.mock(InputStream.class);
        Mockito.when(stream.read(Mockito.any())).thenThrow(new IOException());

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream()).thenReturn(stream);

        final String error = ServerInterface.readErrorFromConnection(connection);
        assertWithMessage("Error string: '" + error + "'")
                .that(error).startsWith("Error reading error string from server: ");
    }

    @Test
    public void testReadErrorEmptyStream() throws IOException {
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThat(ServerInterface.readErrorFromConnection(connection))
                .isEqualTo("No error data returned by server.");
    }

    @Test
    public void testReadErrorStreamTooLarge() throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2048; ++i) {
            sb.append(i % 100);
        }
        final String bigString = sb.toString();

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(bigString.getBytes(StandardCharsets.UTF_8)));

        sb.setLength(1024);
        assertThat(ServerInterface.readErrorFromConnection(connection)).isEqualTo(sb.toString());
    }

    private String setupServerAndGetUrl(byte[] geekResponse, HttpResponse signCertResponse)
            throws IOException {
        final NanoHTTPD server = new NanoHTTPD("localhost", 0) {
            @Override
            public Response serve(IHTTPSession session) {
                consumeRequestBody((HTTPSession) session);
                if (session.getUri().contains(":fetchEekChain")) {
                    return newFixedLengthResponse(Response.Status.OK, "application/cbor",
                            new ByteArrayInputStream(geekResponse), geekResponse.length);
                } else if (session.getUri().contains(":signCertificates")) {
                    Response.IStatus status = new Response.IStatus() {
                        @Override
                        public String getDescription() {
                            return signCertResponse.getDescription();
                        }

                        @Override
                        public int getRequestStatus() {
                            return signCertResponse.getCode();
                        }
                    };
                    byte[] response = signCertResponse.getMessage();
                    return newFixedLengthResponse(status, signCertResponse.getMime(),
                            new ByteArrayInputStream(response), response.length);
                }
                fail("Unexpected HTTP request: " + session.getUri());
                return null;
            }

            void consumeRequestBody(HTTPSession session) {
                try {
                    session.getInputStream().readNBytes((int) session.getBodySize());
                } catch (IOException e) {
                    fail("Error reading request bytes: " + e);
                }
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        return "http://localhost:" + server.getListeningPort() + "/";
    }

    private void setEthernetEnabled(boolean enable) throws Exception {
        // Whether the device running these tests supports ethernet.
        EthernetManager ethernetManager = sContext.getSystemService(EthernetManager.class);
        assertThat(ethernetManager).isNotNull();
        boolean hasEthernet = sContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_ETHERNET);
        if (hasEthernet) {
            try (PermissionContext c = TestApis.permissions().withPermission(
                    Manifest.permission.NETWORK_SETTINGS)) {
                // Enable/Disable the ethernet as it can not be controlled by airplane mode.
                ethernetManager.setEthernetEnabled(enable);
            }
        }
    }

    private void setAirplaneMode(boolean enable) throws Exception {
        ConnectivityManager cm = sContext.getSystemService(ConnectivityManager.class);
        assertThat(cm).isNotNull();
        try (PermissionContext ignored = TestApis.permissions().withPermission(
                Manifest.permission.NETWORK_SETTINGS)) {
            cm.setAirplaneMode(enable);

            // Now wait a "reasonable" time for the network to go down. This timeout matches
            // the connectivity manager tests, which wait for 2 minutes.
            for (int i = 0; i < 120; ++i) {
                NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                Log.e(TAG, "Checking active network... " + networkInfo);
                if (enable) {
                    if (networkInfo == null || !networkInfo.isConnected()) {
                        Log.e(TAG, "Successfully disconnected from to the network.");
                        return;
                    }
                } else if (networkInfo != null && networkInfo.isConnected()) {
                    Log.e(TAG, "Successfully reconnected to the network.");
                    return;
                }
                Thread.sleep(1000);
            }
        }
        fail("Failed to " + (enable ? "enable" : "disable") + " airplane mode");
    }

    enum HttpResponse {
        HTTP_OK_VALID_CBOR(200, "OK", Base64.decode("gkCA", Base64.DEFAULT)),
        HTTP_OK_INVALID_CBOR(200, "OK"),
        HTTP_DEVICE_UNREGISTERED(444, "Device Not Registered"),
        HTTP_USER_UNAUTHORIZED(403, "User not authorized");

        private final int mResponseCode;
        private final String mResponseDescription;
        private final byte[] mResponseMessage;
        private final String mResponseMime;
        HttpResponse(int code, String description) {
            mResponseCode = code;
            mResponseDescription = code + " " + description;
            mResponseMessage = description.getBytes(StandardCharsets.UTF_8);
            mResponseMime = "text/plain";
        }

        HttpResponse(int code, String description, byte[] message) {
            mResponseMessage = Arrays.copyOf(message, message.length);
            mResponseCode = code;
            mResponseDescription = code + " " + description;
            mResponseMime = "application/cbor";
        }

        public int getCode() {
            return mResponseCode;
        }

        public String getDescription() {
            return mResponseDescription;
        }

        public byte[] getMessage() {
            return mResponseMessage.clone();
        }

        public String getMime() {
            return mResponseMime;
        }
    }
}
