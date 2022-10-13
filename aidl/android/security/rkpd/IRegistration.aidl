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

package android.security.rkpd;

import android.security.rkpd.RemotelyProvisionedKey;

/**
 * This interface is associated with the registration of an
 * IRemotelyProvisionedComponent. Each component has a unique database of keys
 * and certificates that are provisioned to the device for attestation. An
 * IRegistration binder is created by calling {@link IRegistrar#getRegistration()}.
 *
 * This interface is used to query for available keys and certificates for the
 * registered component.
 *
 * @hide
 */
interface IRegistration {
    /**
     * Fetch a remotely provisioned key for the given keyId. Keys are unique
     * per caller/keyId/registration tuple. This ensures that no two
     * applications are able to correlate keys to uniquely identify a
     * device/user.
     *
     * If no keys are immediately available, then this function returns immediately
     * with null. The waitForRemotelyProvisionedKey call is similar to this one, but
     * it will block until keys are available.
     *
     * @param keyId This is a client-chosen key identifier, used to
     * differentiate between keys for varying client-specific use-cases. For
     * example, keystore2 passes the UID of the applications that call it as
     * the keyId value here, so that each of keystore2's clients gets a unique
     * key.
     */
    RemotelyProvisionedKey getRemotelyProvisionedKey(int keyId);

    /**
     * Block until a remotely provisioned key is available. If no keys are
     * immediately available, then this function blocks, waiting until the
     * remote provisioning server can be contacted to provision a key.
     *
     * @see getRemotelyProvisionedKey()
     *
     * @param keyId a client-chosen key identifier
     */
    RemotelyProvisionedKey waitForRemotelyProvisionedKey(int keyId);

    /**
     * Replace the key blob with the given key id with an upgraded key blob.
     * In certain cases, such as security patch level upgrade, keys become "old".
     * In these cases, the component which supports operations with the remotely
     * provisioned key blobs must support upgrading the blobs to make them "new"
     * and usable on the updated system.
     *
     * For an example of a remotely provisioned component that has an upgrade
     * mechanism, see the documentation for IKeyMintDevice.upgradeKey.
     *
     * Once a key has been upgraded, the IRegistration where the key is stored
     * needs to be told about the new blob. After calling storeUpgradedKey,
     * getRemotelyProvisionedKey will return the new key blob instead of the old
     * one.
     *
     * Note that this function does NOT extend the lifetime of key blobs. The
     * certificate for the key is unchanged, and the key will still expire at
     * the same time it would have if storeUpgradedKey had never been called.
     *
     * @param keyId The client-chosen key identifier by the client. This key
     * blob will replace the previous key blob associated with the identifier.
     *
     * @param newKeyblob The new blob to replace the key blob currently indexed
     * by keyId.
     */
    void storeUpgradedKey(int keyId, in byte[] newKeyBlob);
}
