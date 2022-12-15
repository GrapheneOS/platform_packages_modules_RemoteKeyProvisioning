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

package com.android.rkpdapp.provisioner;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.rkpdapp.GeekResponse;
import com.android.rkpdapp.ProvisionerMetrics;
import com.android.rkpdapp.RkpdException;
import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpdDatabase;
import com.android.rkpdapp.interfaces.ServerInterface;
import com.android.rkpdapp.interfaces.ServiceManagerInterface;

import java.time.Instant;

import co.nstant.in.cbor.CborException;

/**
 * A class that extends Worker in order to be scheduled to maintain the attestation key pool at
 * regular intervals. If the job determines that more keys need to be generated and signed, it would
 * drive that process.
 */
public class PeriodicProvisioner extends Worker {
    private static final String TAG = "RkpdPeriodicProvisioner";
    private final Context mContext;
    private final ProvisionedKeyDao mKeyDao;

    public PeriodicProvisioner(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        mContext = context;
        mKeyDao = RkpdDatabase.getDatabase(context).provisionedKeyDao();
    }

    /**
     * Overrides the default doWork method to handle checking and provisioning the device.
     */
    @Override
    public Result doWork() {
        Log.i(TAG, "Waking up; checking provisioning state.");
        try (ProvisionerMetrics metrics = ProvisionerMetrics.createScheduledAttemptMetrics(
                mContext)) {
            // Clean up the expired keys
            mKeyDao.deleteExpiringKeys(Instant.now());

            // Fetch geek from the server and figure out whether provisioning needs to be stopped.
            GeekResponse response = new ServerInterface(mContext).fetchGeekAndUpdate(metrics);
            if (response.numExtraAttestationKeys == 0) {
                Log.i(TAG, "Disable provisioning and delete all keys.");
                metrics.setEnablement(ProvisionerMetrics.Enablement.DISABLED);
                metrics.setStatus(ProvisionerMetrics.Status.PROVISIONING_DISABLED);

                mKeyDao.deleteAllKeys();
                return Result.success();
            }

            // Figure out each of the IRPCs and get SystemInterface instance for each.
            String[] serviceNames = ServiceManagerInterface.getDeclaredInstances();
            Log.i(TAG, "Total services found implementing IRPC: " + serviceNames.length);
            Provisioner provisioner = new Provisioner(mContext, mKeyDao);
            for (String serviceName: serviceNames) {
                Log.i(TAG, "Starting provisioning for " + serviceName);
                provisioner.provisionKeys(metrics, serviceName, response);
            }
            Log.i(TAG, "Periodic provisioning completed.");
            metrics.setStatus(ProvisionerMetrics.Status.KEYS_SUCCESSFULLY_PROVISIONED);
            return Result.success();
        } catch (RemoteException | CborException | RkpdException | InterruptedException e) {
            Log.e(TAG, "Some issue when running rkpd.", e);
        }
        return Result.failure();
    }
}
