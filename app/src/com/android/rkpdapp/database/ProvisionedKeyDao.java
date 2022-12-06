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
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

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
     * Delete all expiring keys provided by given Instant.
     */
    @Query("DELETE FROM provisioned_keys WHERE expiration_time < :expiryTime")
    public abstract void deleteExpiringKeys(Instant expiryTime);

    /**
     * Delete all the provisioned keys.
     */
    @Query("DELETE FROM provisioned_keys")
    public abstract void deleteAllKeys();

    /**
     * Get all provisioned keys for a specific IRPC that are expiring at a given Instant.
     */
    @Query("SELECT * FROM provisioned_keys"
            + " WHERE expiration_time < :expiryTime AND irpc_hal = :irpcHal")
    public abstract List<ProvisionedKey> getExpiringKeysForIrpc(Instant expiryTime, String irpcHal);

    /**
     * Get provisioned keys that can be assigned to clients.
     */
    @Query("SELECT * FROM provisioned_keys"
            + " WHERE client_uid IS NULL AND irpc_hal = :irpcHal"
            + " LIMIT 1")
    abstract ProvisionedKey getUnassignedKeyForIrpc(String irpcHal);

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
     * Stores the upgraded key blob.
     */
    @Query("UPDATE provisioned_keys SET key_blob = :newKeyBlob WHERE key_blob = :oldKeyBlob")
    public abstract void upgradeKeyBlob(byte[] oldKeyBlob, byte[] newKeyBlob);

    /**
     * This transaction directly tries to assign an unassigned provisioned key to the given keyId
     * and clientId. If the clientId and keyId combination is already on another key with same
     * irpcHal, then this would throw an exception since index on these need to be unique.
     * @param irpcHal Searches for unassigned keys for this remotely provisioned component.
     * @param clientUid Uid for RKPD's client that needs to set up the key for its own client.
     * @param keyId Client provided identifier to set up the key with.
     *
     * @return the key that has been assigned to the given (irpcHal, clientUid, keyId) tuple,
     * else null if no keys are available to be assigned.
     */
    @Transaction
    public ProvisionedKey assignKey(String irpcHal, int clientUid, int keyId) {
        ProvisionedKey availableKey = getUnassignedKeyForIrpc(irpcHal);
        if (availableKey == null) {
            return null;
        }
        availableKey.clientUid = clientUid;
        availableKey.keyId = keyId;
        updateKey(availableKey);
        return availableKey;
    }
}
