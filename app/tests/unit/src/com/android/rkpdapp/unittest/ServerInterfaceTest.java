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

import android.content.Context;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;

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

public class ServerInterfaceTest {

    private static final String TAG = "RkpdServerInterfaceTest";

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

    @BeforeClass
    public static void init() throws Exception {
        sContext = ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() throws Exception {
        Settings.clearPreferences(sContext);
    }

    @After
    public void tearDown() throws Exception {
        Settings.clearPreferences(sContext);
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
        Assert.assertTrue(
                "Error string: '" + error + "'",
                error.startsWith("Error reading error string from server: "));
    }

    @Test
    public void testReadErrorEmptyStream() throws IOException {
        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        Assert.assertEquals(
                "No error data returned by server.",
                ServerInterface.readErrorFromConnection(connection));
    }

    @Test
    public void testReadErrorStreamTooLarge() throws IOException {
        final StringBuffer sb = new StringBuffer();
        for (int i = 0; i < 2048; ++i) {
            sb.append(i % 100);
        }
        final String bigString = sb.toString();

        HttpURLConnection connection = Mockito.mock(HttpURLConnection.class);
        Mockito.when(connection.getContentType()).thenReturn("text");
        Mockito.when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(bigString.getBytes(StandardCharsets.UTF_8)));

        sb.setLength(1024);
        Assert.assertEquals(sb.toString(), ServerInterface.readErrorFromConnection(connection));
    }
}
