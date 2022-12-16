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
    private static final Instant CURRENT_INSTANT = InstantConverter.fromTimestamp(
            System.currentTimeMillis());
    private ProvisionedKey mProvisionedKey1;
    private ProvisionedKey mProvisionedKey2;

    private ProvisionedKeyDao mKeyDao;
    private RkpdDatabase mDatabase;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        mDatabase = Room.inMemoryDatabaseBuilder(context, RkpdDatabase.class).build();
        mKeyDao = mDatabase.provisionedKeyDao();
        mProvisionedKey1 = new ProvisionedKey(TEST_KEY_BLOB_1, TEST_HAL_1, TEST_KEY_BLOB_1,
                TEST_KEY_BLOB_1, CURRENT_INSTANT);
        mProvisionedKey2 = new ProvisionedKey(TEST_KEY_BLOB_2, TEST_HAL_2, TEST_KEY_BLOB_2,
                TEST_KEY_BLOB_2, CURRENT_INSTANT);
    }

    @After
    public void tearDown() {
        mDatabase.close();
    }

    @Test
    public void testWriteToTable() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1));
        List<ProvisionedKey> keysInDatabase = getAllKeys();

        assertThat(keysInDatabase).containsExactly(mProvisionedKey1);
    }

    @Test
    public void testOverwriteConflict() {
        mProvisionedKey2.keyBlob = TEST_KEY_BLOB_1;
        try {
            mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));
            fail("Inserting keys with same keyBlob should throw SQLiteConstraintException.");
        } catch (SQLiteConstraintException ex) {
            assertThat(ex).hasMessageThat().contains("UNIQUE constraint failed");
        }

        List<ProvisionedKey> unassignedKeys = getAllKeys();
        assertThat(unassignedKeys).isEmpty();
    }

    @Test
    public void testRemovingExpiredKeyFromTable() {
        mProvisionedKey1.expirationTime = CURRENT_INSTANT.minus(1000, ChronoUnit.MINUTES);
        mProvisionedKey2.expirationTime = CURRENT_INSTANT.plus(1000, ChronoUnit.MINUTES);

        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).hasSize(2);

        mKeyDao.deleteExpiringKeys(Instant.now());

        keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).containsExactly(mProvisionedKey2);
    }

    @Test
    public void testUpdate() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        ProvisionedKey key = keysInDatabase.get(0);
        assertThat(keysInDatabase).hasSize(1);
        assertThat(key.expirationTime).isEqualTo(CURRENT_INSTANT);

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
        mKeyDao.updateKey(mProvisionedKey1);

        assertThat(getAllKeys()).isEmpty();
    }

    @Test
    public void testDeleteAllKeys() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));

        List<ProvisionedKey> keysInDatabase = getAllKeys();
        assertThat(keysInDatabase).hasSize(2);

        mKeyDao.deleteAllKeys();
        assertThat(getAllKeys()).isEmpty();
    }

    @Test
    public void testGetExpiringKeysForIrpc() {
        mProvisionedKey1.expirationTime = CURRENT_INSTANT.minus(1000, ChronoUnit.MINUTES);
        mProvisionedKey2.expirationTime = CURRENT_INSTANT.plus(1000, ChronoUnit.MINUTES);
        ProvisionedKey key3 = new ProvisionedKey(TEST_KEY_BLOB_3, TEST_HAL_2, TEST_KEY_BLOB_3,
                TEST_KEY_BLOB_3, CURRENT_INSTANT.minus(1000, ChronoUnit.MINUTES));
        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2, key3));

        List<ProvisionedKey> expiringKeys = mKeyDao.getExpiringKeysForIrpc(Instant.now(),
                TEST_HAL_1);
        assertThat(expiringKeys).containsExactly(mProvisionedKey1);
    }

    @Test
    public void testGetKeyForClientAndIrpc() {
        mProvisionedKey1.keyId = FAKE_KEY_ID;
        mProvisionedKey1.clientUid = FAKE_CLIENT_UID;
        mProvisionedKey2.irpcHal = TEST_HAL_1;
        mProvisionedKey2.keyId = FAKE_KEY_ID;
        mProvisionedKey2.clientUid = FAKE_CLIENT_UID_2;

        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));

        ProvisionedKey assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID,
                FAKE_KEY_ID);
        assertThat(mProvisionedKey1).isEqualTo(assignedKey);

        assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID_2, FAKE_KEY_ID);
        assertThat(mProvisionedKey2).isEqualTo(assignedKey);

        assignedKey = mKeyDao.getKeyForClientAndIrpc(TEST_HAL_1, FAKE_CLIENT_UID_3, FAKE_KEY_ID_2);
        assertThat(assignedKey).isNull();
    }

    @Test
    public void testUpgradeKeyBlob() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1));

        ProvisionedKey databaseKey = getAllKeys().get(0);
        assertThat(databaseKey.keyBlob).isEqualTo(TEST_KEY_BLOB_1);

        mKeyDao.upgradeKeyBlob(TEST_KEY_BLOB_1, TEST_KEY_BLOB_2);
        databaseKey = getAllKeys().get(0);
        assertThat(databaseKey.keyBlob).isEqualTo(TEST_KEY_BLOB_2);
    }

    @Test
    public void testCountUnassignedKeys() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));
        assertThat(mKeyDao.getTotalUnassignedKeysForIrpc(TEST_HAL_1)).isEqualTo(1);
        assertThat(mKeyDao.getTotalUnassignedKeysForIrpc(TEST_HAL_2)).isEqualTo(1);
        assertThat(mKeyDao.getTotalUnassignedKeysForIrpc("fakeHal")).isEqualTo(0);
    }

    @Test
    public void testAssignKey() throws RkpdException {
        mProvisionedKey2.irpcHal = TEST_HAL_1;
        mKeyDao.insertKeys(List.of(mProvisionedKey1, mProvisionedKey2));

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
        assertThat(mKeyDao.assignKey(TEST_HAL_1, FAKE_CLIENT_UID, FAKE_KEY_ID)).isNull();
    }

    @Test
    public void testUpgradeWithNullKeyBlob() {
        mKeyDao.insertKeys(List.of(mProvisionedKey1));

        try {
            mKeyDao.upgradeKeyBlob(TEST_KEY_BLOB_1, null);
            fail("UpgradeKeyBlob should fail for null keyblob.");
        } catch (SQLiteConstraintException ex) {
            assertThat(ex).hasMessageThat().contains("NOT NULL constraint failed");
        }
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
            String irpcHal = cursor.getString(cursor.getColumnIndex("irpc_hal"));
            byte[] publicKey = cursor.getBlob(cursor.getColumnIndex("public_key"));
            byte[] certificateChain = cursor.getBlob(cursor.getColumnIndex("certificate_chain"));
            Instant expirationTime = InstantConverter.fromTimestamp(cursor.getLong(
                    cursor.getColumnIndex("expiration_time")));

            ProvisionedKey key = new ProvisionedKey(keyBlob, irpcHal, publicKey, certificateChain,
                    expirationTime);

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
