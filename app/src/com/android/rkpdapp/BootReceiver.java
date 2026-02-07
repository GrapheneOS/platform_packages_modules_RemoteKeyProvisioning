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

package com.android.rkpdapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Log;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.android.rkpdapp.provisioner.PeriodicProvisioner;
import com.android.rkpdapp.provisioner.WidevineProvisioner;
import com.android.rkpdapp.utils.Settings;
import java.util.concurrent.TimeUnit;

/**
 * A receiver class that listens for boot to be completed and then starts a recurring job that will
 * monitor the status of the attestation key pool on device, purging old certificates and requesting
 * new ones as needed.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "RkpdBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Caught boot intent, waking up.");
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isSystemUser()) {
            Log.i(TAG, "Caught boot intent on non-system user, disabling the application and going"
                    + " back to sleep.");
            context.getPackageManager().setApplicationEnabledSetting(
                    context.getPackageName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0);
            return;
        }
        Settings.resetDefaultConfig(context);
        Settings.generateAndSetId(context);

        Constraints rkpConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build();

        PeriodicWorkRequest workRequest =
                new PeriodicWorkRequest.Builder(PeriodicProvisioner.class, 1, TimeUnit.DAYS)
                        .setConstraints(rkpConstraints)
                        .build();
        WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(PeriodicProvisioner.UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, // Replace on reboot.
                        workRequest);

        Constraints wvConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        Log.i(TAG, "Queueing a one-time provisioning job for widevine provisioning.");
        OneTimeWorkRequest wvRequest = new OneTimeWorkRequest.Builder(WidevineProvisioner.class)
                .addTag("WidevineProvisioner")
                .setConstraints(wvConstraints)
                .build();
        WorkManager.getInstance(context).enqueue(wvRequest);
    }
}
