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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.RkpKey;
import com.android.rkpdapp.interfaces.ServiceManagerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.utils.CborUtils;

import com.google.crypto.tink.subtle.Ed25519Sign;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;

@RunWith(AndroidJUnit4.class)
public class SystemInterfaceTest {
    // TODO: Change this to V3 once Server starts accepting CSR v2.
    private static final int INTERFACE_VERSION_V3 = 4;
    private static final String SERVICE = IRemotelyProvisionedComponent.DESCRIPTOR + "/default";
    private static final int INTERFACE_VERSION_V2 = 2;
    private static final byte[] FAKE_PROTECTED_DATA = new byte[] { (byte) 0x84, 0x43, (byte) 0xA1,
            0x01, 0x03, (byte) 0xA1, 0x05, 0x4C, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            (byte) 0x88, (byte) 0x99, 0x00, (byte) 0xAA, (byte) 0xBB, 0x46, 0x12, 0x34,
            0x12, 0x34, 0x12, 0x34, (byte) 0x80 };

    @Before
    public void preCheck() {
        Assume.assumeTrue(ServiceManager.isDeclared(SERVICE));
    }

    @Test
    public void testGetDeclaredInstances() {
        String[] instances = ServiceManagerInterface.getDeclaredInstances();
        assertThat(instances).asList().isNotEmpty();
        assertThat(instances).asList().contains(SERVICE);
    }

    @Test
    public void testSearchFailForOtherServices() {
        try {
            new ServiceManagerInterface("default");
            fail("Getting the declared service 'default' should fail due to SEPolicy.");
        } catch (RuntimeException e) {
            assertThat(e).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    public void testGenerateKey() throws CborException, RkpdException, RemoteException {
        IRemotelyProvisionedComponent mockedComponent = mock(IRemotelyProvisionedComponent.class);
        ServiceManagerInterface mockedServiceManager = mockServiceManager(CborUtils.EC_CURVE_25519,
                INTERFACE_VERSION_V3, mockedComponent);
        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(
                ApplicationProvider.getApplicationContext());
        RkpKey rkpKey = systemInterface.generateKey(metrics);
        ProvisionedKey key = rkpKey.generateProvisionedKey(new byte[0], Instant.now());
        assertThat(key.irpcHal).isEqualTo(SERVICE);
    }

    @Test
    public void testGenerateKeyFailureRemoteException() throws RemoteException, CborException,
            RkpdException {
        ServiceManagerInterface mockedServiceManager = mockServiceManagerFailure(
                new RemoteException());
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(
                ApplicationProvider.getApplicationContext());

        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);
        try {
            systemInterface.generateKey(metrics);
            fail("GenerateKey should throw RemoteException.");
        } catch (RuntimeException e) {
            assertThat(e).hasCauseThat().isInstanceOf(RemoteException.class);
        }
    }

    @Test
    public void testGenerateKeyFailureServiceSpecificException() throws RemoteException,
            CborException, RkpdException {
        ServiceManagerInterface mockedServiceManager = mockServiceManagerFailure(
                new ServiceSpecificException(2));
        ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(
                ApplicationProvider.getApplicationContext());

        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);
        try {
            systemInterface.generateKey(metrics);
            fail("GenerateKey should throw ServiceSpecificException.");
        } catch (ServiceSpecificException e) {
            assertThat(e.errorCode).isEqualTo(2);
        }
    }

    @Test
    public void testGenerateCSRPreV3P256() throws Exception {
        IRemotelyProvisionedComponent mockedComponent = mock(IRemotelyProvisionedComponent.class);
        ServiceManagerInterface mockedServiceManager = mockServiceManager(CborUtils.EC_CURVE_P256,
                INTERFACE_VERSION_V2, mockedComponent);
        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);

        ProvisionerMetrics metrics = ProvisionerMetrics.createOutOfKeysAttemptMetrics(
                ApplicationProvider.getApplicationContext(), SERVICE);
        KeyPair eekEcdsaKeyPair = generateEcdsaKeyPair();
        ECPublicKey eekPubKey = (ECPublicKey) eekEcdsaKeyPair.getPublic();
        byte[] eekPub = Utils.getBytesFromP256PublicKey(eekPubKey);
        byte[] eekChain = generateEekChain(Utils.CURVE_P256, eekPub);
        assertThat(eekChain).isNotNull();
        GeekResponse geekResponse = new GeekResponse();
        geekResponse.addGeek(CborUtils.EC_CURVE_P256, eekChain);
        geekResponse.setChallenge(new byte[]{0x02});

        byte[] csrTag = systemInterface.generateCsr(metrics, geekResponse, new ArrayList<>());
        assertThat(csrTag).isNotEmpty();
        verify(mockedComponent, times(1)).generateCertificateRequest(anyBoolean(),
                any(MacedPublicKey[].class), any(byte[].class), any(byte[].class),
                any(DeviceInfo.class), any(ProtectedData.class));
        verify(mockedComponent, never()).generateCertificateRequestV2(any(MacedPublicKey[].class),
                any(byte[].class));
    }

    @Test
    public void testGenerateCSRPreV3Ed25519() throws Exception {
        IRemotelyProvisionedComponent mockedComponent = mock(IRemotelyProvisionedComponent.class);
        ServiceManagerInterface mockedServiceManager = mockServiceManager(CborUtils.EC_CURVE_25519,
                INTERFACE_VERSION_V2, mockedComponent);

        ProvisionerMetrics metrics = ProvisionerMetrics.createOutOfKeysAttemptMetrics(
                ApplicationProvider.getApplicationContext(), SERVICE);
        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);
        GeekResponse geekResponse = new GeekResponse();
        byte[] eekPub = new byte[32];
        new Random().nextBytes(eekPub);
        byte[] eekChain = generateEekChain(Utils.CURVE_ED25519, eekPub);
        assertThat(eekChain).isNotNull();
        geekResponse.addGeek(CborUtils.EC_CURVE_25519, eekChain);
        geekResponse.setChallenge(new byte[]{0x02});

        byte[] csrTag = systemInterface.generateCsr(metrics, geekResponse, new ArrayList<>());
        assertThat(csrTag).isNotEmpty();
        verify(mockedComponent, times(1)).generateCertificateRequest(anyBoolean(),
                any(MacedPublicKey[].class), any(byte[].class), any(byte[].class),
                any(DeviceInfo.class), any(ProtectedData.class));
        verify(mockedComponent, never()).generateCertificateRequestV2(any(MacedPublicKey[].class),
                any(byte[].class));
    }

    @Test
    public void testGenerateCSRv3() throws Exception {
        IRemotelyProvisionedComponent mockedComponent = mock(IRemotelyProvisionedComponent.class);
        ServiceManagerInterface mockedServiceManager = mockServiceManager(CborUtils.EC_CURVE_25519,
                INTERFACE_VERSION_V3, mockedComponent);

        ProvisionerMetrics metrics = ProvisionerMetrics.createOutOfKeysAttemptMetrics(
                ApplicationProvider.getApplicationContext(), SERVICE);
        SystemInterface systemInterface = new SystemInterface(mockedServiceManager);
        GeekResponse geekResponse = new GeekResponse();
        geekResponse.setChallenge(new byte[]{0x02});

        byte[] csrTag = systemInterface.generateCsr(metrics, geekResponse, new ArrayList<>());
        assertThat(csrTag).isNotEmpty();
        verify(mockedComponent, never()).generateCertificateRequest(anyBoolean(),
                any(MacedPublicKey[].class), any(byte[].class), any(byte[].class),
                any(DeviceInfo.class), any(ProtectedData.class));
        verify(mockedComponent, times(1)).generateCertificateRequestV2(any(MacedPublicKey[].class),
                any(byte[].class));
    }

    private byte[] generateEekChain(int curve, byte[] eek) throws Exception {
        if (curve == Utils.CURVE_ED25519) {
            Ed25519Sign.KeyPair kp = Ed25519Sign.KeyPair.newKeyPair();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addArray()
                    .add(Utils.encodeAndSignSign1Ed25519(
                            Utils.encodeEd25519PubKey(kp.getPublicKey()), kp.getPrivateKey()))
                    .add(Utils.encodeAndSignSign1Ed25519(
                            Utils.encodeX25519PubKey(eek), kp.getPrivateKey()))
                    .end()
                    .build());
            return baos.toByteArray();
        } else if (curve == Utils.CURVE_P256) { // P256
            // Root
            KeyPair ecdsaKeyPair = generateEcdsaKeyPair();
            ECPublicKey pubKey = (ECPublicKey) ecdsaKeyPair.getPublic();
            ECPrivateKey privKey = (ECPrivateKey) ecdsaKeyPair.getPrivate();
            byte[] pubKeyBytes = Utils.getBytesFromP256PublicKey(pubKey);
            byte[] pubx = new byte[32];
            byte[] puby = new byte[32];
            System.arraycopy(pubKeyBytes, 0, pubx, 0, 32);
            System.arraycopy(pubKeyBytes, 32, puby, 0, 32);

            BigInteger priv = privKey.getS();
            byte[] privBytes = priv.toByteArray();
            byte[] signingKey = new byte[32];
            if (privBytes.length <= 32) {
                System.arraycopy(privBytes, 0, signingKey, 32
                        - privBytes.length, privBytes.length);
            } else if (privBytes.length == 33 && privBytes[0] == 0) {
                System.arraycopy(privBytes, 1, signingKey, 0, 32);
            } else {
                throw new IllegalStateException("EC private key value is too large");
            }

            byte[] eekPubX = new byte[32];
            byte[] eekPubY = new byte[32];
            System.arraycopy(eek, 0, eekPubX, 0, 32);
            System.arraycopy(eek, 32, eekPubY, 0, 32);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            new CborEncoder(baos).encode(new CborBuilder()
                    .addArray()
                    .add(Utils.encodeAndSignSign1Ecdsa256(
                            Utils.encodeP256PubKey(pubx, puby, false), signingKey))
                    .add(Utils.encodeAndSignSign1Ecdsa256(
                            Utils.encodeP256PubKey(eekPubX, eekPubY, true), signingKey))
                    .end()
                    .build());
            return baos.toByteArray();
        } else {
            Assert.fail("Unsupported curve: " + curve);
        }
        throw new RkpdException(RkpdException.ErrorCode.INTERNAL_ERROR,
                "Could not generate eek chain");
    }

    private ServiceManagerInterface mockServiceManagerFailure(Exception exception)
            throws RemoteException {
        IRemotelyProvisionedComponent mockedComponent = mock(IRemotelyProvisionedComponent.class);
        RpcHardwareInfo mockedHardwareInfo = mock(RpcHardwareInfo.class);
        mockedHardwareInfo.supportedEekCurve = CborUtils.EC_CURVE_25519;
        when(mockedComponent.getHardwareInfo()).thenReturn(mockedHardwareInfo);
        when(mockedComponent.generateEcdsaP256KeyPair(eq(false), any())).thenThrow(exception);
        ServiceManagerInterface mockedServiceManager = mock(ServiceManagerInterface.class);
        when(mockedServiceManager.getBinder()).thenReturn(mockedComponent);
        when(mockedServiceManager.getServiceName()).thenReturn(SERVICE);
        return mockedServiceManager;
    }

    private ServiceManagerInterface mockServiceManager(int supportedCurve, int interfaceVersion,
            IRemotelyProvisionedComponent mockedComponent) throws RemoteException {
        RpcHardwareInfo mockedHardwareInfo = mock(RpcHardwareInfo.class);
        mockedHardwareInfo.supportedEekCurve = supportedCurve;
        mockedHardwareInfo.versionNumber = interfaceVersion;
        when(mockedComponent.getHardwareInfo()).thenReturn(mockedHardwareInfo);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ((MacedPublicKey) args[1]).macedKey = Base64.decode("g0BAWE2lAQIDJiABIVggUYCsz4+WjOwPU"
                    + "OGpG7eQhjSL48OsZQJNtPYxDghGMjkiWCBU65Sd/ra05HM6JU4vH52dvfpmwRGL6ZaMQ+Qw9tp2"
                    + "qw==", Base64.DEFAULT);
            return new byte[] { 0x01 };
        }).when(mockedComponent).generateEcdsaP256KeyPair(eq(false), any());
        if (interfaceVersion == INTERFACE_VERSION_V2) {
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                ((DeviceInfo) args[4]).deviceInfo = new byte[] { (byte) 0xA0 };
                ((ProtectedData) args[5]).protectedData = FAKE_PROTECTED_DATA;
                return new byte[0];
            }).when(mockedComponent).generateCertificateRequest(anyBoolean(),
                    any(MacedPublicKey[].class), any(byte[].class), any(byte[].class),
                    any(DeviceInfo.class), any(ProtectedData.class));
        } else {
            when(mockedComponent.generateCertificateRequestV2(any(MacedPublicKey[].class),
                    any(byte[].class))).thenReturn(new byte[] { (byte) 0x80 });
        }

        ServiceManagerInterface mockedServiceManager = mock(ServiceManagerInterface.class);
        when(mockedServiceManager.getBinder()).thenReturn(mockedComponent);
        when(mockedServiceManager.getServiceName()).thenReturn(SERVICE);
        return mockedServiceManager;
    }
}
