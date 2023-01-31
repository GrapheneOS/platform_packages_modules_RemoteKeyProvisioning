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

import static com.android.rkpdapp.unittest.Utils.generateEcdsaKeyPair;
import static com.android.rkpdapp.unittest.Utils.getP256PubKeyFromBytes;
import static com.android.rkpdapp.unittest.Utils.signPublicKey;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.rkpdapp.utils.X509Utils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

@RunWith(AndroidJUnit4.class)
public class X509UtilsTest {

    @Test
    public void testFormatX509Certs() throws Exception {
        KeyPair root = generateEcdsaKeyPair();
        KeyPair intermediate = generateEcdsaKeyPair();
        KeyPair leaf = generateEcdsaKeyPair();
        X509Certificate[] certs = new X509Certificate[3];
        certs[2] = signPublicKey(root, root.getPublic());
        certs[1] = signPublicKey(root, intermediate.getPublic());
        certs[0] = signPublicKey(intermediate, leaf.getPublic());
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        for (X509Certificate cert : certs) {
            os.write(cert.getEncoded());
        }
        X509Certificate[] roundTrip = X509Utils.formatX509Certs(os.toByteArray());
        assertThat(certs.length).isEqualTo(roundTrip.length);
        for (int i = 0; i < certs.length; i++) {
            assertWithMessage("Failed on index " + i)
                    .that(certs[i].getEncoded())
                    .isEqualTo(roundTrip[i].getEncoded());
        }
    }

    @Test
    public void testGetAndFormatRawPublicKey() throws Exception {
        KeyPair testKey = generateEcdsaKeyPair();
        X509Certificate testCert = signPublicKey(testKey, testKey.getPublic());
        byte[] formattedKey = X509Utils.getAndFormatRawPublicKey(testCert);
        byte[] xPoint = new byte[32];
        byte[] yPoint = new byte[32];
        System.arraycopy(formattedKey, 0 /* offset */, xPoint, 0 /* offset */, 32 /* length */);
        System.arraycopy(formattedKey, 32 /* offset */, yPoint, 0 /* offset */, 32 /* length */);
        assertThat(testKey.getPublic()).isEqualTo(getP256PubKeyFromBytes(xPoint, yPoint));
    }

    @Test
    public void testCertificateChains() throws Exception {
        String encodedTestCert = "MIIBzDCCAXOgAwIBAgIRAKrDc87UaGSeFTRzF4vz0IcwCgYIKoZIzj0EAwIwMjEY"
                + "MBYGA1UEChMPR29vZ2xlIFRlc3QgTExDMRYwFAYDVQQDEw1Ecm9pZCBUZXN0IENBMB4XDTIzMDEyMzE"
                + "wMzU1OVoXDTIzMDIwMTEwMzU1OVowOTEMMAoGA1UEChMDVEVFMSkwJwYDVQQDEyBhYWMzNzNjZWQ0Nj"
                + "g2NDllMTUzNDczMTc4YmYzZDA4NzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLObeIaknIBWLIJlD"
                + "R2nH7R7J4uG35pMaOdKx7Z0l+zwd78zT/Q3tvCNk412YNwMlpXYd9lqP6GbMmLplU9lNU6jYzBhMB0G"
                + "A1UdDgQWBBRyvR22kj7DPA6hYebzV/7SAV75+zAfBgNVHSMEGDAWgBT4MR/vSGF22DdunejgJitJY4X"
                + "DPTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDAKBggqhkjOPQQDAgNHADBEAiA+91WraD"
                + "ckWIWpRePZRv/zrBNY8PbD72hl1M3wzC1mkAIgV3pRHYjsQM2OfLweVvIDT9TNKf4cbyYc+K/6xakrm"
                + "NE=";
        String encodedRootCert = "MIIBxDCCAWugAwIBAgIQf7TE7zQ0iDLyiZIIpqKCvjAKBggqhkjOPQQDAjAyMRgw"
                + "FgYDVQQKEw9Hb29nbGUgVGVzdCBMTEMxFjAUBgNVBAMTDURyb2lkIFRlc3QgQ0EwHhcNMjMwMTIyMTg"
                + "yMjUyWhcNMjMwMjI4MTgyMjUyWjAyMRgwFgYDVQQKEw9Hb29nbGUgVGVzdCBMTEMxFjAUBgNVBAMTDU"
                + "Ryb2lkIFRlc3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQeq/B7LxO49X7o6bqrJ93pulwAk"
                + "uKbSdSwDae51ZdnAyuMxL2gUwVcHL8JViQS5uNQLuvpFegPKxDDFj6mzDpio2MwYTAdBgNVHQ4EFgQU"
                + "+DEf70hhdtg3bp3o4CYrSWOFwz0wHwYDVR0jBBgwFoAU+DEf70hhdtg3bp3o4CYrSWOFwz0wDwYDVR0"
                + "TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQwCgYIKoZIzj0EAwIDRwAwRAIgJHyRnMmtZ36uSkuVTt"
                + "9OtQNpCM6GgCSh2qb1Xiw60xECIDII1Ps/Cf29SUsgFq8KgvqWRms4Ctp5ioFbbeovcFwX";

        X509Certificate rootCert = generateCertificateFromEncodedBytes(encodedRootCert);
        X509Certificate testCert = generateCertificateFromEncodedBytes(encodedTestCert);
        X509Certificate[] validCertChain = new X509Certificate[]{testCert, rootCert};
        X509Certificate[] invalidCertChain = new X509Certificate[]{rootCert, testCert};

        assertThat(X509Utils.isCertChainValid(validCertChain)).isTrue();
        assertThat(X509Utils.isCertChainValid(invalidCertChain)).isFalse();
    }

    @Test
    public void testCertChainSwapOAndCN() throws Exception {
        String encodedTestCert = "MIIBzDCCAXOgAwIBAgIRAKrDc87UaGSeFTRzF4vz0IcwCgYIKoZIzj0EAwIwMjEW"
                + "MBQGA1UEAxMNRHJvaWQgVGVzdCBDQTEYMBYGA1UEChMPR29vZ2xlIFRlc3QgTExDMB4XDTIzMDEyMzE"
                + "wMzU1OVoXDTIzMDIwMTEwMzU1OVowOTEMMAoGA1UEChMDVEVFMSkwJwYDVQQDEyBhYWMzNzNjZWQ0Nj"
                + "g2NDllMTUzNDczMTc4YmYzZDA4NzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLObeIaknIBWLIJlD"
                + "R2nH7R7J4uG35pMaOdKx7Z0l+zwd78zT/Q3tvCNk412YNwMlpXYd9lqP6GbMmLplU9lNU6jYzBhMB0G"
                + "A1UdDgQWBBRyvR22kj7DPA6hYebzV/7SAV75+zAfBgNVHSMEGDAWgBT4MR/vSGF22DdunejgJitJY4X"
                + "DPTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDAKBggqhkjOPQQDAgNHADBEAiA+91WraD"
                + "ckWIWpRePZRv/zrBNY8PbD72hl1M3wzC1mkAIgV3pRHYjsQM2OfLweVvIDT9TNKf4cbyYc+K/6xakrm"
                + "NE=";
        String encodedRootCert = "MIIBxDCCAWugAwIBAgIQf7TE7zQ0iDLyiZIIpqKCvjAKBggqhkjOPQQDAjAyMRgw"
                + "FgYDVQQKEw9Hb29nbGUgVGVzdCBMTEMxFjAUBgNVBAMTDURyb2lkIFRlc3QgQ0EwHhcNMjMwMTIyMTg"
                + "yMjUyWhcNMjMwMjI4MTgyMjUyWjAyMRgwFgYDVQQKEw9Hb29nbGUgVGVzdCBMTEMxFjAUBgNVBAMTDU"
                + "Ryb2lkIFRlc3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQeq/B7LxO49X7o6bqrJ93pulwAk"
                + "uKbSdSwDae51ZdnAyuMxL2gUwVcHL8JViQS5uNQLuvpFegPKxDDFj6mzDpio2MwYTAdBgNVHQ4EFgQU"
                + "+DEf70hhdtg3bp3o4CYrSWOFwz0wHwYDVR0jBBgwFoAU+DEf70hhdtg3bp3o4CYrSWOFwz0wDwYDVR0"
                + "TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQwCgYIKoZIzj0EAwIDRwAwRAIgJHyRnMmtZ36uSkuVTt"
                + "9OtQNpCM6GgCSh2qb1Xiw60xECIDII1Ps/Cf29SUsgFq8KgvqWRms4Ctp5ioFbbeovcFwX";

        X509Certificate rootCert = generateCertificateFromEncodedBytes(encodedRootCert);
        X509Certificate testCert = generateCertificateFromEncodedBytes(encodedTestCert);
        X509Certificate[] certChain = new X509Certificate[]{testCert, rootCert};

        assertThat(X509Utils.isSelfSignedCertificate(rootCert)).isTrue();
        assertThat(X509Utils.isSelfSignedCertificate(testCert)).isFalse();
        assertThat(X509Utils.isCertChainValid(certChain)).isFalse();
    }

    private X509Certificate generateCertificateFromEncodedBytes(String encodedCert)
            throws CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        InputStream in = new ByteArrayInputStream(Base64.decode(encodedCert, Base64.DEFAULT));
        return (X509Certificate) certFactory.generateCertificate(in);
    }
}
