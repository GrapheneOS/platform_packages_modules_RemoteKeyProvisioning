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

import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.RkpKey;
import com.android.rkpdapp.utils.CborUtils;

import java.util.List;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;

/**
 * Provides convenience methods for interfacing with the IRemotelyProvisionedComponent
 * implementations. Since these APIs are internal only and subject to change, it is handy
 * to have an abstraction layer to reduce the impact of these changes on the app.
 */
public class SystemInterface {
    private static final String TAG = "RkpdSystemInterface";
    private final IRemotelyProvisionedComponent mBinder;
    private final String mServiceName;
    private final int mSupportedCurve;

    public SystemInterface(ServiceManagerInterface serviceManagerInterface) {
        mServiceName = serviceManagerInterface.getServiceName();
        mBinder = serviceManagerInterface.getBinder();
        try {
            mSupportedCurve = mBinder.getHardwareInfo().supportedEekCurve;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call getHardwareInfo", e);
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Generates attestation keys pair through binder service.
     * Returns generated key in {@link RkpKey} format.
     */
    public RkpKey generateKey(ProvisionerMetrics metrics) throws CborException, RkpdException {
        MacedPublicKey macedPublicKey = new MacedPublicKey();
        try (ProvisionerMetrics.StopWatch ignored = metrics.startBinderWait()) {
            byte[] privKey = mBinder.generateEcdsaP256KeyPair(false, macedPublicKey);
            return CborUtils.extractRkpKeyFromMacedKey(privKey, mServiceName, macedPublicKey);
        } catch (RemoteException e) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_KEYPAIR_FAILED);
            Log.e(TAG, "Failed to generate key.", e);
            throw e.rethrowAsRuntimeException();
        } catch (ServiceSpecificException e) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_KEYPAIR_FAILED);
            Log.e(TAG, "Failed to generate key. Failed with " + e.errorCode, e);
            throw e;
        } catch (CborException e) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_KEYPAIR_FAILED);
            throw e;
        }
    }

    /**
     * Sends a generateCsr request over the binder interface to generate the request to be sent to
     * Remote provisioning servers.
     * @param geekResponse Contains the challenge and GEEK chain for older implementations. Only has
     *                    challenge for the newer ones.
     * @param keysToSign array of keys to be signed.
     */
    public byte[] generateCsr(ProvisionerMetrics metrics, GeekResponse geekResponse,
            List<RkpKey> keysToSign) throws CborException, RkpdException {
        byte[] challenge = geekResponse.getChallenge();
        byte[] csrTag;
        MacedPublicKey[] macedKeysToSign = keysToSign.stream()
                .map(x -> {
                    MacedPublicKey key = new MacedPublicKey();
                    key.macedKey = x.getMacedPublicKey();
                    return key;
                }).toArray(MacedPublicKey[]::new);
        try (ProvisionerMetrics.StopWatch ignored = metrics.startBinderWait()) {
            if (getVersion() < 3) {
                DeviceInfo deviceInfo = new DeviceInfo();
                ProtectedData protectedData = new ProtectedData();
                byte[] geekChain = geekResponse.getGeekChain(mSupportedCurve);
                csrTag = mBinder.generateCertificateRequest(false, macedKeysToSign, geekChain,
                        challenge, deviceInfo, protectedData);
                Array macedKeys = new Array();
                for (RkpKey key: keysToSign) {
                    macedKeys.add(key.getCoseKey());
                }
                try {
                    Array mac0Message = new Array()
                            .add(new ByteString(CborUtils.encodeCbor(
                                    CborUtils.makeProtectedHeaders())))
                            .add(new Map())
                            .add(new ByteString(CborUtils.encodeCbor(macedKeys)))
                            .add(new ByteString(csrTag));
                    return CborUtils.buildCertificateRequest(deviceInfo.deviceInfo,
                            challenge,
                            protectedData.protectedData,
                            CborUtils.encodeCbor(mac0Message),
                            CborUtils.buildUnverifiedDeviceInfo());
                } catch (CborException | RkpdException e) {
                    Log.e(TAG, "Failed to parse/build CBOR", e);
                    metrics.setStatus(ProvisionerMetrics.Status.GENERATE_CSR_FAILED);
                    throw e;
                }
            } else {
                byte[] csrBytes = mBinder.generateCertificateRequestV2(macedKeysToSign, challenge);
                Array array = (Array) CborUtils.decodeCbor(csrBytes, "CSR request",
                        MajorType.ARRAY);
                array.add(CborUtils.buildUnverifiedDeviceInfo());
                return CborUtils.encodeCbor(array);
            }
        } catch (RemoteException e) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_CSR_FAILED);
            Log.e(TAG, "Failed to generate CSR blob", e);
            throw e.rethrowAsRuntimeException();
        } catch (ServiceSpecificException ex) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_CSR_FAILED);
            Log.e(TAG, "Failed to generate CSR blob. Failed with " + ex.errorCode, ex);
            throw ex;
        } catch (CborException e) {
            metrics.setStatus(ProvisionerMetrics.Status.GENERATE_CSR_FAILED);
            throw e;
        }
    }

    /**
     * Gets the implemented version for IRemotelyProvisionedComponent.
     */
    public int getVersion() throws RemoteException {
        return mBinder.getHardwareInfo().versionNumber;
    }
}
