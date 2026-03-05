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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import com.android.rkpd.flags.Flags;
import com.android.rkpdapp.ConfirmCertificates;
import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.metrics.ProvisioningAttempt;
import com.android.rkpdapp.testutil.FakeRkpServer;
import com.android.rkpdapp.utils.Settings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class ServerInterfaceTest {
    private static final Duration TIME_TO_REFRESH_HOURS = Duration.ofHours(2);
    private static Context sContext;
    private ServerInterface mServerInterface;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @BeforeClass
    public static void init() {
        sContext = Mockito.spy(ApplicationProvider.getApplicationContext());
    }

    @Before
    public void setUp() {
        Settings.clearPreferences(sContext);
        mServerInterface = new ServerInterface(sContext, false);
        Utils.mockConnectivityState(sContext, Utils.ConnectivityState.CONNECTED);
    }

    @After
    public void tearDown() {
        Settings.clearPreferences(sContext);
        Mockito.reset(sContext);
    }

    @Test
    public void testRetryOnServerFailure() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(FakeRkpServer.Response.INTERNAL_ERROR,
                FakeRkpServer.Response.INTERNAL_ERROR)) {
            Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            Settings.setMaxRequestTime(sContext, 100);
            GeekResponse ignored = mServerInterface.fetchGeek(
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));
            assertWithMessage("Expected RkpdException.").fail();
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.HTTP_SERVER_ERROR);
            assertThat(e).hasMessageThat().contains("HTTP error status encountered");
        }
    }

    @Test
    public void testFetchGeekRkpDisabled() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_RKP_DISABLED,
                FakeRkpServer.Response.INTERNAL_ERROR)) {
            Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            GeekResponse response = mServerInterface.fetchGeek(
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(response.numExtraAttestationKeys).isEqualTo(0);
            assertThat(response.getChallenge()).isNotNull();
            assertThat(response.getGeekChain(2)).isNotNull();
        }
    }

    @Test
    public void testFetchGeekRkpEnabled() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            GeekResponse response = mServerInterface.fetchGeek(
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(response.numExtraAttestationKeys).isEqualTo(20);
            assertThat(response.getChallenge()).isNotNull();
            byte[] challenge = Base64.decode("AAABgEg1zGsBILStY/1VNI7st0AG9x2S/tba+H4=",
                    Base64.DEFAULT);
            assertThat(response.getChallenge()).isEqualTo(challenge);
            byte[] ed25519GeekChain = Base64.decode(
                    "g4RDoQEnoFgqpAEBAycgBiFYIJm57t1e5FL2hcZMYtw+YatXS"
                            + "H11NymtdoAy0rPLY1jZWEAeIghLpLekyNdOAw7+uK8UTKc7b6XN3Np5xitk"
                            + "/pk5r3bngPpmAIUNB5gqrJFcpyUUSQY0dcqKJ3rZ41pJ6wIDhEOhASegWCqk"
                            + "AQEDJyAGIVgg6i+FDp5qDFz3vdn6KDK/2lXpIKJRA8kDkxjOoBUp7NFYQIJr"
                            + "x12mNle3x3ESrRzCarMsIyrdFDDLghS2icXTHjG7uFAhSklNupEMbzNNg7xY"
                            + "Ky6E28VZD5hh4sHqifLQrgSEQ6EBJ6BYTqUBAQJYIG+S0QRtcdinjojY0VaB"
                            + "X5bReIPmMBuH7b8g0Uo7/mouAzgYIAQhWCC2XRxLmoM6nbUVWTehJvsP3+ec"
                            + "rAHVpOzIOikAiFglOVhAgLKf0DKenUr+sCXywtIiaEbGILCq6BasZKFFg5vM"
                            + "SVQlf6sWBVPwvTWT88a7WU5e+d4hBxSjtqSji4+Clpa6Aw==",
                    Base64.DEFAULT);
            byte[] p256GeekChain = Base64.decode(
                    "g4RDoQEmoFhNpQECAyYgASFYIPcUituX9MxT79JkEcTjdR9mH6Rx"
                            + "DGzP+glGgHSHVPKtIlggXn9b9uzk9hnM/xM3/Q+hyJPbGAZ2xF3m12p3hsMtr49YQC"
                            + "+XjkL7vgctlUeFR5NAsB/Um0ekxESp8qEHhxDHn8sR9L+f6Dvg5zRMFfx7w34zBfTR"
                            + "NDztAgRgehXgedOK/ySEQ6EBJqBYTaUBAgMmIAEhWCBRgKzPj5aM7A9Q4akbt5CGNI"
                            + "vjw6xlAk209jEOCEYyOSJYIFTrlJ3+trTkczolTi8fnZ29+mbBEYvploxD5DD22nar"
                            + "WECYOPs0OmXbc5ixJ6IVdPK+BueNIk7d8L/CAXTEtylrJBy12NJm+kTv9TAsBHTt6M"
                            + "Zg2s6fVlcndCHT3pOP47jNhEOhASagWHGmAQICWCCDn/j9EBwSn5JBx1uN5E70GROa"
                            + "xxttpw6V8mRTXacdwQM4GCABIVggFqRSEmOzhlZQ2N/yoKh9vNlup2hg6oxc8ZPllx"
                            + "kNrN4iWCCJvsxsP16wOTSvl7o40RYdocwdZNOMSE74coEbOz4x7lhA+trPLaulMAxz"
                            + "xeWrSZJZYET6xPIz5QSybBlk6RzjZDs0hgBlLfXdr6oBya+DyU74WpToZZNR4xgeOY"
                            + "CnaUszzQ==",
                    Base64.DEFAULT);
            assertThat(response.getGeekChain(GeekResponse.EC_CURVE_25519))
                    .isEqualTo(ed25519GeekChain);
            assertThat(response.getGeekChain(GeekResponse.EC_CURVE_P256)).isEqualTo(p256GeekChain);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_REQUEST_ID_REUSE)
    public void testFetchGeekIncludesRequestId() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(
                    sContext,
                    2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */,
                    server.getUrl());
            GeekResponse response =
                    mServerInterface.fetchGeekAndUpdate(
                            ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(server.getCapturedUri()).contains(":fetchEekChain");
            assertThat(server.getCapturedParams()).containsKey("request_id");
            String requestId = server.getCapturedParams().get("request_id");
            try {
                UUID.fromString(requestId);
            } catch (IllegalArgumentException e) {
                assertWithMessage("Request ID is not a UUID.").fail();
            }
            assertThat(response.requestId).isEqualTo(requestId);
        }
    }

    @Test
    public void testFetchKeyAndUpdate() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            mServerInterface.fetchGeekAndUpdate(
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(Settings.getExtraSignedKeysAvailable(sContext)).isEqualTo(20);
            assertThat(Settings.getExpiringBy(sContext)).isEqualTo(Duration.ofHours(72));
        }
    }

    @Test
    public void testFetchGeekNullResponseResetsConfig() throws Exception {
        // Use a response that is not valid CBOR for a GEEK response, which will cause
        // GeekResponse.parse to return null.
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.SIGN_CERTS_OK_INVALID_CBOR,
                        FakeRkpServer.Response.INTERNAL_ERROR)) {
            final String badUrl = server.getUrl();
            Settings.setDeviceConfig(sContext, 1, TIME_TO_REFRESH_HOURS, badUrl);
            assertThat(Settings.getUrl(sContext)).isEqualTo(badUrl);

            ProvisioningAttempt metrics =
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
            RkpdException e =
                    assertThrows(RkpdException.class, () -> mServerInterface.fetchGeek(metrics));

            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.HTTP_SERVER_ERROR);
            assertThat(e).hasMessageThat().contains("Response failed to parse.");
            assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
        }
    }

    @Test
    public void testRequestSignedCertUnregistered() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_DEVICE_UNREGISTERED)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(
                    sContext);
            mServerInterface.requestSignedCertificates(new byte[0], metrics,
                    Optional.empty(), Optional.empty());
            assertWithMessage("Should fail due to unregistered device.").fail();
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.DEVICE_NOT_REGISTERED);
        }
    }

    @Test
    public void testRequestSignedCertClientError() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_USER_UNAUTHORIZED)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            Settings.setMaxRequestTime(sContext, 100);
            ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(
                    sContext);
            mServerInterface.requestSignedCertificates(new byte[0], metrics,
                    Optional.empty(), Optional.empty());
            assertWithMessage("Should fail due to client error.").fail();
        } catch (RkpdException e) {
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.HTTP_CLIENT_ERROR);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testRequestSignedCertCborErrorShouldConfirmCertificates() throws Exception {
        FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_INVALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK);
        Settings.setDeviceConfig(
                sContext,
                2 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */,
                server.getUrl());
        ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
        SystemInterface mockSystem = Mockito.mock(SystemInterface.class);
        doReturn("strongbox").when(mockSystem).getHalInstanceName();

        RkpdException ex =
                assertThrows(
                        RkpdException.class,
                        () ->
                                mServerInterface.requestSignedCertificates(
                                        new byte[0],
                                        metrics,
                                        Optional.of("requestId"),
                                        Optional.of(mockSystem)));

        assertThat(ex.getErrorCode()).isEqualTo(RkpdException.ErrorCode.INTERNAL_ERROR);
        assertThat(ex).hasMessageThat().contains("Failed to parse signed certificates");

        assertThat(server.getCapturedUri()).contains(":confirmCertificates");
        assertThat(server.getCapturedParams()).containsKey("request_id");
    }

    @Test
    public void testRequestSignedCertValid() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());
            ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(
                    sContext);
            List<byte[]> certChains = mServerInterface.requestSignedCertificates(new byte[0],
                    metrics, Optional.empty(), Optional.empty());
            assertThat(certChains).isEmpty();
            assertThat(certChains).isNotNull();
        }
    }

    @Test
    public void testDataBudgetEmptyFetchGeekNetworkConnected() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());

            // Check the data budget in order to initialize a rolling window.
            assertThat(Settings.hasErrDataBudget(sContext, null /* curTime */)).isTrue();
            Settings.consumeErrDataBudget(sContext, Settings.FAILURE_DATA_USAGE_MAX);
            ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(
                    sContext);

            mServerInterface.fetchGeek(metrics);
            assertWithMessage("Network transaction should not have proceeded.").fail();
        } catch (RkpdException e) {
            assertThat(e).hasMessageThat().contains("Out of data budget due to repeated errors");
            assertThat(e.getErrorCode()).isEqualTo(
                    RkpdException.ErrorCode.NETWORK_COMMUNICATION_ERROR);
        }
    }

    @Test
    public void testNetworkDisconnected() throws Exception {
        try (FakeRkpServer server = new FakeRkpServer(
                FakeRkpServer.Response.FETCH_EEK_OK,
                FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            Settings.setDeviceConfig(sContext, 2 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */, server.getUrl());

            ProvisioningAttempt metrics = ProvisioningAttempt.createScheduledAttemptMetrics(
                    sContext);

            // We are okay in mocking connectivity failure since network check is the first thing
            // to happen.
            Utils.mockConnectivityState(sContext, Utils.ConnectivityState.DISCONNECTED);
            mServerInterface.fetchGeek(metrics);
            assertWithMessage("Network transaction should not have proceeded.").fail();
        } catch (RkpdException e) {
            assertThat(e).hasMessageThat().contains("No network detected");
            assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.NO_NETWORK_CONNECTIVITY);
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
    public void testReadTextErrorFromErrorStreamNoErrorData() throws Exception {
        final String expectedError = "No error data returned by server.";

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream()).thenThrow(new IOException());
        Mockito.when(connection.getErrorStream()).thenReturn(null);

        assertThat(ServerInterface.readErrorFromConnection(connection)).isEqualTo(expectedError);
    }

    @Test
    public void testReadTextErrorFromErrorStream() throws Exception {
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
        final String[] textContentTypes = new String[]{
                "text",
                "text/ANYTHING",
                "text/what-is-this; charset=unknown",
                "text/lowercase; charset=utf-8",
                "text/uppercase; charset=UTF-8",
                "text/yolo; charset=ASCII"
        };

        for (String contentType : textContentTypes) {
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

    @Test
    public void testServerConnectionTimeout() {
        ServerInterface serverInterface = Mockito.spy(mServerInterface);
        Mockito.when(serverInterface.getRegionalProperty()).thenReturn("cn");
        assertThat(serverInterface.getConnectTimeoutMs()).isEqualTo(
                ServerInterface.SYNC_CONNECT_TIMEOUT_RESTRICTED_MS);

        Mockito.when(serverInterface.getRegionalProperty()).thenReturn("cn,us");
        assertThat(serverInterface.getConnectTimeoutMs()).isEqualTo(
                ServerInterface.SYNC_CONNECT_TIMEOUT_RESTRICTED_MS);

        Mockito.when(serverInterface.getRegionalProperty()).thenReturn(null);
        assertThat(serverInterface.getConnectTimeoutMs()).isEqualTo(
                ServerInterface.SYNC_CONNECT_TIMEOUT_OPEN_MS);

        Mockito.when(serverInterface.getRegionalProperty()).thenReturn("");
        assertThat(serverInterface.getConnectTimeoutMs()).isEqualTo(
                ServerInterface.SYNC_CONNECT_TIMEOUT_OPEN_MS);

        Mockito.when(serverInterface.getRegionalProperty()).thenReturn("us");
        assertThat(serverInterface.getConnectTimeoutMs())
                .isEqualTo(ServerInterface.SYNC_CONNECT_TIMEOUT_OPEN_MS);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesErrorDoesNotRetryOnServerFailure() throws Exception {
        FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.INTERNAL_ERROR);
        Settings.setDeviceConfig(
                sContext,
                1 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */,
                server.getUrl());
        Settings.setMaxRequestTime(sContext, 100);
        ConfirmCertificates confirmCertificates =
                ConfirmCertificates.createError(
                        "strongbox",
                        "error",
                        "stackTrace",
                        new ConfirmCertificates.DerCertificateChains(new byte[] {1, 2, 3}));

        // Does not throw.
        mServerInterface.confirmCertificates(
                confirmCertificates,
                "requestId",
                ProvisioningAttempt.createScheduledAttemptMetrics(sContext));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesSuccessInstanceDoesNotRetryOnServerFailure()
            throws Exception {
        FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.INTERNAL_ERROR);
        Settings.setDeviceConfig(
                sContext,
                1 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */,
                server.getUrl());
        Settings.setMaxRequestTime(sContext, 100);

        // Does not throw.
        mServerInterface.confirmCertificates(
                ConfirmCertificates.createSuccess("strongbox"),
                "requestId",
                ProvisioningAttempt.createScheduledAttemptMetrics(sContext));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesSuccessInstanceDoesNotResetDeviceConfig() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(
                    sContext,
                    1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */,
                    server.getUrl());

            // The method does not return anything, but should not throw an exception.
            mServerInterface.confirmCertificates(
                    ConfirmCertificates.createSuccess("strongbox"),
                    "requestId",
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(server.getCapturedUri()).contains(":confirmCertificates");
            assertThat(server.getCapturedParams()).containsEntry("request_id", "requestId");

            // The device config should not be reset.
            assertThat(Settings.getUrl(sContext)).isEqualTo(server.getUrl());
            assertThat(Settings.getExpiringBy(sContext)).isEqualTo(TIME_TO_REFRESH_HOURS);
            assertThat(Settings.getExtraSignedKeysAvailable(sContext)).isEqualTo(1);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesErrorInstanceResetsDeviceConfig() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_OK)) {
            Settings.setDeviceConfig(
                    sContext,
                    1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */,
                    server.getUrl());

            // The method does not return anything, but should not throw an exception.
            mServerInterface.confirmCertificates(
                    ConfirmCertificates.createError(
                            "strongbox",
                            "error",
                            "stackTrace",
                            new ConfirmCertificates.DerCertificateChains(new byte[] {1, 2, 3})),
                    "requestId",
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(server.getCapturedUri()).contains(":confirmCertificates");

            // The device config should be reset.
            assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
            assertThat(Settings.getExpiringBy(sContext))
                    .isEqualTo(Duration.ofMillis(Settings.EXPIRING_BY_MS_DEFAULT));
            assertThat(Settings.getExtraSignedKeysAvailable(sContext))
                    .isEqualTo(Settings.EXTRA_SIGNED_KEYS_AVAILABLE_DEFAULT);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesInvalidServerResponseResetsDeviceConfig() throws Exception {
        try (FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR,
                        FakeRkpServer.Response.CONFIRM_CERTS_INVALID_CBOR)) {
            Settings.setDeviceConfig(
                    sContext,
                    1 /* extraKeys */,
                    TIME_TO_REFRESH_HOURS /* expiringBy */,
                    server.getUrl());
            assertThat(Settings.getUrl(sContext)).isNotEqualTo(Settings.getDefaultUrl());

            mServerInterface.confirmCertificates(
                    ConfirmCertificates.createSuccess("strongbox"),
                    "requestId",
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

            assertThat(server.getCapturedUri()).contains(":confirmCertificates");

            // Device config should be reset.
            assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
            assertThat(Settings.getExpiringBy(sContext))
                    .isEqualTo(Duration.ofMillis(Settings.EXPIRING_BY_MS_DEFAULT));
            assertThat(Settings.getExtraSignedKeysAvailable(sContext))
                    .isEqualTo(Settings.EXTRA_SIGNED_KEYS_AVAILABLE_DEFAULT);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_FEEDBACK_LOOP)
    public void testConfirmCertificatesErrorLongReasonIsTruncated() throws Exception {
        ServerInterface spyServerInterface = Mockito.spy(mServerInterface);
        ArgumentCaptor<ConfirmCertificates> captor =
                ArgumentCaptor.forClass(ConfirmCertificates.class);
        Mockito.doNothing()
                .when(spyServerInterface)
                .confirmCertificates(
                        captor.capture(),
                        Mockito.anyString(),
                        Mockito.any(ProvisioningAttempt.class));
        SystemInterface mockSystemInterface = Mockito.mock(SystemInterface.class);
        doReturn("strongbox").when(mockSystemInterface).getHalInstanceName();

        // Create an exception with a message and cause that will exceed 256 chars.
        String longMessage = new String(new char[200]).replace('\0', 'A');
        String longCauseMessage = new String(new char[200]).replace('\0', 'B');

        spyServerInterface.confirmCertificatesError(
                Optional.of(mockSystemInterface),
                new Exception(longMessage, new Throwable(longCauseMessage)),
                new ConfirmCertificates.DerCertificateChains(new byte[] {1, 2, 3}),
                "requestId",
                ProvisioningAttempt.createScheduledAttemptMetrics(sContext));

        byte[] cborBytes = captor.getValue().buildCborBytes();
        List<co.nstant.in.cbor.model.DataItem> dataItems =
                new co.nstant.in.cbor.CborDecoder(new ByteArrayInputStream(cborBytes)).decode();
        co.nstant.in.cbor.model.Map confirmCertificatesInfo =
                (co.nstant.in.cbor.model.Map) dataItems.get(0);
        co.nstant.in.cbor.model.Map errorInfo =
                (co.nstant.in.cbor.model.Map)
                        confirmCertificatesInfo.get(
                                new co.nstant.in.cbor.model.UnicodeString("error_info"));
        co.nstant.in.cbor.model.UnicodeString reason =
                (co.nstant.in.cbor.model.UnicodeString)
                        errorInfo.get(new co.nstant.in.cbor.model.UnicodeString("reason"));

        String expectedReason = (longMessage + ": " + longCauseMessage).substring(0, 256);
        assertThat(reason.getString()).isEqualTo(expectedReason);
        assertThat(reason.getString().length()).isEqualTo(256);
    }

    @Test
    public void malformedUrlResetsConfig() throws Exception {
        Settings.setMaxRequestTime(sContext, 100);
        assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
        final String badUrl = "bad url";

        // Override the default config.
        Settings.setDeviceConfig(sContext, 1 /* extraKeys */,
                TIME_TO_REFRESH_HOURS /* expiringBy */, badUrl);
        assertThat(Settings.getUrl(sContext)).isEqualTo(badUrl);

        ProvisioningAttempt metrics =
                ProvisioningAttempt.createScheduledAttemptMetrics(sContext);
        RkpdException e =
                assertThrows(
                        RkpdException.class, () -> mServerInterface.fetchGeek(metrics));

        assertThat(e.getErrorCode()).isEqualTo(RkpdException.ErrorCode.HTTP_CLIENT_ERROR);
        assertThat(e).hasMessageThat().contains("Bad URL");

        // Verify that the config is reset to the default.
        assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
    }

    @Test
    public void httpClientErrorResetsConfigAfterMaxFailures() throws Exception {
        // Default config.
        Settings.setMaxRequestTime(sContext, 100);
        assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());

        // Override the default config with a URL that will return a 404.
        final String badUrl = "http://google.com/validUrlNonExistentPath";
        Settings.setDeviceConfig(sContext, 1, TIME_TO_REFRESH_HOURS, badUrl);
        assertThat(Settings.getUrl(sContext)).isEqualTo(badUrl);

        try (
            // These endpoints will not be called, but must be provided.
            FakeRkpServer server =
                new FakeRkpServer(
                        FakeRkpServer.Response.FETCH_EEK_OK,
                        FakeRkpServer.Response.SIGN_CERTS_OK_VALID_CBOR)) {
            ProvisioningAttempt metrics =
                    ProvisioningAttempt.createScheduledAttemptMetrics(sContext);

            // First failure should not reset the config.
            assertThrows(RkpdException.class, () -> mServerInterface.fetchGeek(metrics));
            assertThat(Settings.getUrl(sContext)).isEqualTo(badUrl);

            // Simulate a number of failures to reach the maximum.
            for (int i = 0; i < Settings.FAILURE_MAXIMUM-1; ++i) {
                Settings.incrementFailureCounter(sContext);
            }

            // The next request should reset the config.
            assertThrows(RkpdException.class, () -> mServerInterface.fetchGeek(metrics));
            assertThat(Settings.getUrl(sContext)).isEqualTo(Settings.getDefaultUrl());
            assertThat(Settings.getFailureCounter(sContext)).isEqualTo(0);
        }
    }
}
