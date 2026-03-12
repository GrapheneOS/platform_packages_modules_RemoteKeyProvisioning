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

package com.android.rkpdapp.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;
import com.android.rkpdapp.utils.Settings;
import java.time.Instant;
import java.util.List;

/**
 * DAO accessor for ProvisionedKey entities in RKPD database. This class helps us to execute queries
 * over the database for remotely provisioned keys.
 */
@Dao
public abstract class ProvisionedKeyDao {
    /**
     * Insert keys to database.
     */
    @Insert
    public abstract void insertKeys(List<ProvisionedKey> keys);

    /**
     * Update specified keys in the database.
     */
    @Update
    public abstract void updateKey(ProvisionedKey key);

    /**
     * Gets all the keys in the database.
     */
    @Query("SELECT * FROM provisioned_keys")
    public abstract List<ProvisionedKey> getAllKeys();

    /**
     * Deletes a list of keys from the database.
     */
    @Delete
    public abstract void deleteKeys(List<ProvisionedKey> keys);

    /**
     * Deletes a specific key from the database.
     */
    @Query("DELETE from provisioned_keys WHERE key_blob = :keyBlob")
    public abstract void deleteKey(byte[] keyBlob);

    /**
     * Delete all the provisioned keys for a given key_id for keystore service.
     * 1017 is the keystore service user id.
     */
    @Query("DELETE FROM provisioned_keys WHERE key_id = :keyId AND client_uid = :clientId")
    public abstract void deleteAllKeysForClientAndKeyId(int clientId, int keyId);

    /**
     * Delete all the provisioned keys.
     */
    @Query("DELETE FROM provisioned_keys")
    public abstract void deleteAllKeys();

    /**
     * Delete all expiring keys provided by given Instant.
     */
    @Query("DELETE FROM provisioned_keys WHERE expiration_time < :expiryTime")
    public abstract void deleteExpiringKeys(Instant expiryTime);

    /**
     * Get a count of provisioned keys for a specific IRPC that are expiring at a given Instant.
     */
    @Query("SELECT COUNT(*) FROM provisioned_keys"
            + " WHERE expiration_time < :expiryTime AND irpc_hal = :irpcHal")
    public abstract int getTotalExpiringKeysForIrpc(String irpcHal, Instant expiryTime);

    /**
     * Get provisioned keys that can be assigned to clients, factoring in an expiration time to
     * ensure that we do not return stale keys.
     *
     * @param minExpiry Any keys that expire previous to this time will not be considered, as they
     *                  are too stale.
     */
    @Query("SELECT * FROM provisioned_keys"
            + " WHERE client_uid IS NULL AND irpc_hal = :irpcHal AND expiration_time >= :minExpiry"
            + " LIMIT 1")
    abstract ProvisionedKey getUnassignedKeyForIrpc(String irpcHal, Instant minExpiry);

    /**
     * Get provisioned keys that can be assigned to clients, factoring in an expiration time to
     * ensure that we do not return stale keys. We return the newest unassigned key, i.e. one with
     * the farthest expiration time to allow RKPD automatically use the newest unassigned key for
     *  confirming certificates.
     *
     * @param minExpiry Any keys that expire previous to this time will not be considered, as they
     *                  are too stale.
     */
    @Query("SELECT * FROM provisioned_keys"
            + " WHERE client_uid IS NULL AND irpc_hal = :irpcHal AND expiration_time >= :minExpiry"
            + " ORDER BY expiration_time DESC"
            + " LIMIT 1")
    abstract ProvisionedKey getNewestUnassignedKeyForIrpc(String irpcHal, Instant minExpiry);


    /**
     * Gets total number of keys that can be assigned for a specific IRPC.
     */
    @Query("SELECT COUNT(*) FROM provisioned_keys WHERE client_uid IS NULL AND irpc_hal = :irpcHal")
    public abstract int getTotalUnassignedKeysForIrpc(String irpcHal);

    /**
     * Gets total keys attested for a specific IRPC.
     */
    @Query("SELECT COUNT(*) FROM provisioned_keys WHERE irpc_hal = :irpcHal")
    public abstract int getTotalKeysForIrpc(String irpcHal);

    /**
     * Get key for given client and IRPC.
     */
    @Query("SELECT * FROM provisioned_keys"
            + " WHERE client_uid = :clientUid AND irpc_hal = :irpcHal AND key_id = :keyId")
    public abstract ProvisionedKey getKeyForClientAndIrpc(String irpcHal, int clientUid, int keyId);

    /**
     * Un-assigns a specific key from the database by public key, making it available for reuse.
     */
    @Query("UPDATE provisioned_keys SET client_uid = NULL, key_id = NULL"
            + " WHERE public_key = :publicKey")
    public abstract void UnassignPublicKey(byte[] publicKey);

    /**
     * Stores the upgraded key blob.
     */
    @Query("UPDATE provisioned_keys SET key_blob = :newKeyBlob"
            + " WHERE key_blob = :oldKeyBlob AND client_uid = :clientUid")
    public abstract int upgradeKeyBlob(int clientUid, byte[] oldKeyBlob, byte[] newKeyBlob);

    /**
     * This transaction first looks to see if a caller already has a key assigned, and if so
     * returns that. If not, the caller is then assigned a key from the available pool of keys.
     * If a key was assigned (either by this method or a previous call to this method), then the
     * assigned key is returned. If no keys are available, this method returns null.
     *
     * @param irpcHal   The HAL for which we need to assign a key
     * @param minExpiry The minimum expiration time allowed for an assigned key. Any keys that
     *                  expire before minExpiry will not be assigned.
     * @param clientUid Uid for RKPD's client that needs to set up the key for its own client.
     * @param keyId     Client provided identifier to set up the key with.
     * @return the key that has been assigned to the given (irpcHal, clientUid, keyId) tuple,
     * else null if no keys are available to be assigned.
     */
    @Transaction
    public ProvisionedKey getOrAssignKey(String irpcHal, Instant minExpiry, int clientUid,
            int keyId) {
        ProvisionedKey existingKey = getKeyForClientAndIrpc(irpcHal, clientUid, keyId);
        if (existingKey != null) {
            return existingKey;
        }

        ProvisionedKey availableKey = getAvailableKeyByHal(irpcHal, minExpiry, clientUid, keyId);
        if (availableKey == null) {
            return null;
        }
        availableKey.clientUid = clientUid;
        availableKey.keyId = keyId;
        updateKey(availableKey);
        return availableKey;
    }

    private ProvisionedKey getAvailableKeyByHal(String irpcHal, Instant minExpiry, int clientUid,
            int keyId) {
        if (Settings.isCallForFeedbackLoop(clientUid, keyId)) {
            return getNewestUnassignedKeyForIrpc(irpcHal, minExpiry);
        }
        return getUnassignedKeyForIrpc(irpcHal, minExpiry);
    }
}
