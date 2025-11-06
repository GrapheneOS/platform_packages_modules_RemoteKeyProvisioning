/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.rkpdapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpdDatabase;

/**
 * A receiver class that listens for package removed broadcast and removes the
 * associated attestation key.
 */
public class PackageRemovalReceiver extends BroadcastReceiver {
    private static final String TAG = "RkpdBroadcast";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Caught package_removed intent, waking up.");
        ThreadPool.EXECUTOR.execute(() -> processPackageRemovalIntent(context, intent));
    }

    private void processPackageRemovalIntent(Context context, Intent intent) {
        ProvisionedKeyDao keyDao = RkpdDatabase.getDatabase(context).provisionedKeyDao();
        int uid = intent.getExtras().getInt(Intent.EXTRA_UID);
        keyDao.deleteAllKeysForClientAndKeyId(ProvisionedKeyDao.KEYSTORE_SERVICE_UID, uid);
        Log.i(TAG, "Deleted associated keys for uid: " + uid);
    }
}
