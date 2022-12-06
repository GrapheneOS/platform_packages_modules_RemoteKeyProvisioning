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

import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;

import androidx.room.Room;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.InstantConverter;
import com.android.rkpdapp.database.ProvisionedKey;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpdDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RkpdDatabaseTest {
    private static final String TEST_HAL_1 = "testIrpc";
    private static final String TEST_HAL_2 = "someOtherIrpc";
    private static final byte[] TEST_KEY_BLOB_1 = new byte[] {0x01, 0x02, 0x03};
    private static final byte[] TEST_KEY_BLOB_2 = new byte[] {0x11, 0x12, 0x13};
    private static final byte[] TEST_KEY_BLOB_3 = new byte[] {0x21, 0x22, 0x23};
    private static final int FAKE_CLIENT_UID = 1;
    private static final int FAKE_CLIENT_UID_2 = 2;
    private static final int FAKE_KEY_ID = 1;
    private static final int FAKE_CLIENT_UID_3 = 3;
    private static final int FAKE_KEY_ID_2 = 2;

    private ProvisionedKeyDao mKeyDao;
    private RkpdDatabase mDatabase;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        mDatabase = Room.inMemoryDatabaseBuilder(context, RkpdDatabase.class).build();
        mKeyDao = mDatabase.provisionedKeyDao();
    }

    @After
    public void tearDown() {
        mDatabase.close();
    }

    @Test
    public void testWriteToTable() {
        ProvisionedKey key = new ProvisionedKey(TEST_KEY_BLOB_1);
        mKeyDao.insertKeys(List.of(key));
        List<ProvisionedKey> keysInDatabase = getAllKeys();

        assertThat(keysInDatabase).containsExactly(key);
    }

    @Test
    public void testOverwriteConflict() {
        ProvisionedKey key1 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key1.irpcHal = TEST_HAL_1;
        ProvisionedKey key2 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key2.irpcHal = TEST_HAL_2;
        try {
            mKeyDao.insertKeys(List.of(key1, key2));
            fail("Insert Keys should throw SQLiteConstraintException.");
        } catch (SQLiteConstraintException ex) {
            assertThat(ex).hasMessageThat().contains("UNIQUE constraint failed");
        }

        List<ProvisionedKey> unassignedKeys = getAllKeys();
        assertThat(unassignedKeys).isEmpty();
    }

    @Test
    public void testRemovingExpiredKeyFromTable() {
        ProvisionedKey key1 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key1.expirationTime = roundToMilliseconds(Instant.now().minus(1000, ChronoUnit.MINUTES));
        ProvisionedKey key2 = new ProvisionedKey(TEST_KEY_BLOB_2);
        key2.expirationTime = roundToMilliseconds(Instant.now().plus(1000, ChronoUnit.MINUTES));

        mKeyDao.insertKeys(List.of(key1, key2));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).hasSize(2);

        mKeyDao.deleteExpiringKeys(Instant.now());

        keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).containsExactly(key2);
    }

    @Test
    public void testUpdate() {
        ProvisionedKey key = new ProvisionedKey(TEST_KEY_BLOB_1);
        mKeyDao.insertKeys(List.of(key));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).hasSize(1);
        assertThat(keysInDatabase.get(0).expirationTime).isNull();

        Instant expiredInstant = InstantConverter.fromTimestamp(System.currentTimeMillis())
                .minus(1000, ChronoUnit.MINUTES);
        key.expirationTime = expiredInstant;
        mKeyDao.updateKey(key);
        keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).containsExactly(key);
        assertThat(keysInDatabase.get(0).expirationTime).isEqualTo(expiredInstant);
    }

    @Test
    public void testUpdateWithNonExistentKey() {
        ProvisionedKey key = new ProvisionedKey(TEST_KEY_BLOB_1);
        mKeyDao.updateKey(key);

        assertThat(getAllKeys()).isEmpty();
    }

    @Test
    public void testDeleteAllKeys() {
        mKeyDao.insertKeys(List.of(new ProvisionedKey(TEST_KEY_BLOB_1),
                new ProvisionedKey(TEST_KEY_BLOB_2)));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).hasSize(2);

        mKeyDao.deleteAllKeys();
        assertThat(getAllKeys()).isEmpty();
    }

    @Test
    public void testGetExpiringKeysForIrpc() {
        ProvisionedKey key1 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key1.expirationTime = roundToMilliseconds(Instant.now().minus(1000, ChronoUnit.MINUTES));
        key1.irpcHal = TEST_HAL_1;
        ProvisionedKey key2 = new ProvisionedKey(TEST_KEY_BLOB_2);
        key2.expirationTime = roundToMilliseconds(Instant.now().plus(1000, ChronoUnit.MINUTES));
        key2.irpcHal = TEST_HAL_1;
        ProvisionedKey key3 = new ProvisionedKey(TEST_KEY_BLOB_3);
        key3.expirationTime = roundToMilliseconds(Instant.now().minus(1000, ChronoUnit.MINUTES));
        key3.irpcHal = TEST_HAL_2;
        mKeyDao.insertKeys(List.of(key1, key2, key3));

        List<ProvisionedKey> expiringKeys = mKeyDao.getExpiringKeysForIrpc(Instant.now(),
                TEST_HAL_1);
        assertThat(expiringKeys).containsExactly(key1);
    }

    @Test
    public void testGetKeyForClientAndIrpc() {
        ProvisionedKey key1 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key1.irpcHal = TEST_HAL_1;
        key1.keyId = FAKE_KEY_ID;
        key1.clientUid = FAKE_CLIENT_UID;
        ProvisionedKey key2 = new ProvisionedKey(TEST_KEY_BLOB_2);
        key2.irpcHal = TEST_HAL_1;
        key2.keyId = FAKE_KEY_ID;
        key2.clientUid = FAKE_CLIENT_UID_2;
        mKeyDao.insertKeys(List.of(key1, key2));

        ProvisionedKey assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID,
                FAKE_KEY_ID);
        assertThat(key1).isEqualTo(assignedKey);

        assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID_2, FAKE_KEY_ID);
        assertThat(key2).isEqualTo(assignedKey);

        assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID_3, FAKE_KEY_ID_2);
        assertThat(assignedKey).isNull();
    }

    @Test
    public void testUpgradeKeyBlob() {
        ProvisionedKey key = new ProvisionedKey(TEST_KEY_BLOB_1);
        mKeyDao.insertKeys(List.of(key));

        ProvisionedKey databaseKey = getAllKeys().get(0);
        assertThat(databaseKey.keyBlob).isEqualTo(TEST_KEY_BLOB_1);

        mKeyDao.upgradeKeyBlob(TEST_KEY_BLOB_1, TEST_KEY_BLOB_2);
        databaseKey = getAllKeys().get(0);
        assertThat(databaseKey.keyBlob).isEqualTo(TEST_KEY_BLOB_2);
    }

    @Test
    public void testAssignKey() throws RkpdException {
        ProvisionedKey key1 = new ProvisionedKey(TEST_KEY_BLOB_1);
        key1.irpcHal = TEST_HAL_1;
        key1.expirationTime = roundToMilliseconds(Instant.now());
        ProvisionedKey key2 = new ProvisionedKey(TEST_KEY_BLOB_2);
        key2.irpcHal = TEST_HAL_1;
        key2.expirationTime = roundToMilliseconds(Instant.now());
        mKeyDao.insertKeys(List.of(key1, key2));

        List<ProvisionedKey> keysPersisted = getAllKeys();
        for (ProvisionedKey databaseKey: keysPersisted) {
            assertThat(databaseKey.keyId).isNull();
            assertThat(databaseKey.clientUid).isNull();
        }

        ProvisionedKey assignedKey = mKeyDao.assignKey(TEST_HAL_1, FAKE_CLIENT_UID, FAKE_KEY_ID);

        assertThat(assignedKey.keyId).isEqualTo(FAKE_KEY_ID);
        assertThat(assignedKey.clientUid).isEqualTo(FAKE_CLIENT_UID);

        try {
            mKeyDao.assignKey(TEST_HAL_1, FAKE_CLIENT_UID, FAKE_KEY_ID);
            fail("Able to assign key with duplicate IRPC, clientId and keyId.");
        } catch (SQLiteConstraintException ex) {
            assertThat(ex).hasMessageThat().contains("UNIQUE constraint failed");
        }
    }

    @Test
    public void testNoUnassignedKeyRemaining() {
        try {
            mKeyDao.assignKey(TEST_HAL_1, FAKE_CLIENT_UID, FAKE_KEY_ID);
            fail("Able to assign key even when there is no key.");
        } catch (RkpdException ex) {
            assertThat(ex).hasMessageThat().contains("Out of keys");
            assertThat(ex.getErrorCode()).isEqualTo(RkpdException.Status.OUT_OF_KEYS);
        }
    }

    @Test
    public void testUpgradeWithNullKeyBlob() {
        ProvisionedKey key = new ProvisionedKey(TEST_KEY_BLOB_1);
        mKeyDao.insertKeys(List.of(key));

        try {
            mKeyDao.upgradeKeyBlob(TEST_KEY_BLOB_1, null);
            fail("UpgradeKeyBlob should fail for null keyblob.");
        } catch (SQLiteConstraintException ex) {
            assertThat(ex).hasMessageThat().contains("NOT NULL constraint failed");
        }
    }

    /**
     * Instant counts up to nano seconds. However, it can be created from milliseconds and upwards
     * only. This causes a difference in the stored vs retrieved values. Adding this function to
     * test cases so that we don't fail test cases due to a difference in nano seconds.
     */
    private Instant roundToMilliseconds(Instant x) {
        return InstantConverter.fromTimestamp(InstantConverter.toTimestamp(x));
    }

    /**
     * Gets all the keys using Cursor magic. Verified separately using temp test.
     */
    private List<ProvisionedKey> getAllKeys() {
        Cursor cursor = mDatabase.query(new SimpleSQLiteQuery("SELECT * FROM provisioned_keys"));
        List<ProvisionedKey> returnList = new ArrayList<>(cursor.getCount());
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            byte[] keyBlob = cursor.getBlob(cursor.getColumnIndex("key_blob"));
            ProvisionedKey key = new ProvisionedKey(keyBlob);
            key.irpcHal = cursor.getString(cursor.getColumnIndex("irpc_hal"));
            key.publicKey = cursor.getBlob(cursor.getColumnIndex("public_key"));
            key.certificateChain = cursor.getBlob(cursor.getColumnIndex("certificate_chain"));

            int expirationColumnIndex = cursor.getColumnIndex("expiration_time");
            key.expirationTime = cursor.isNull(expirationColumnIndex)
                    ? null : InstantConverter.fromTimestamp(cursor.getLong(expirationColumnIndex));

            int clientUidColumnIndex = cursor.getColumnIndex("client_uid");
            key.clientUid = cursor.isNull(clientUidColumnIndex)
                    ? null : cursor.getInt(clientUidColumnIndex);

            int keyIdColumnIndex = cursor.getColumnIndex("key_id");
            key.keyId = cursor.isNull(keyIdColumnIndex) ? null : cursor.getInt(keyIdColumnIndex);

            returnList.add(key);
            cursor.moveToNext();
        }
        cursor.close();
        return returnList;
    }
}
