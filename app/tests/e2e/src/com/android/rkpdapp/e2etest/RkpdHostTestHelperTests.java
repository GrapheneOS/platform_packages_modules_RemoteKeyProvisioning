/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.rkpdapp.e2etest;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.content.Context;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.os.ServiceManager;
import android.os.SystemProperties;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import com.android.rkpdapp.database.ProvisionedKeyDao;
import com.android.rkpdapp.database.RkpdDatabase;
import com.android.rkpdapp.interfaces.ServiceManagerInterface;
import com.android.rkpdapp.interfaces.SystemInterface;
import com.android.rkpdapp.provisioner.PeriodicProvisioner;
import com.android.rkpdapp.utils.Settings;
import com.android.rkpdapp.utils.StatsProcessor;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.concurrent.Executors;

@RunWith(Parameterized.class)
public class RkpdHostTestHelperTests {
    private static Context sContext;
    private final String mServiceName;
    private ProvisionedKeyDao mKeyDao;

    @Rule
    public final TestName mName = new TestName();

    @Parameterized.Parameters(name = "{index}: instanceName={0}")
    public static String[] parameters() {
        return ServiceManager.getDeclaredInstances(IRemotelyProvisionedComponent.DESCRIPTOR);
    }

    public RkpdHostTestHelperTests(String instanceName) {
        mServiceName = IRemotelyProvisionedComponent.DESCRIPTOR + "/" + instanceName;
    }

    @BeforeClass
    public static void init() {
        sContext = ApplicationProvider.getApplicationContext();

        assume()
                .withMessage("The RKP server hostname is not configured -- assume RKP disabled.")
                .that(SystemProperties.get("remote_provisioning.hostname"))
                .isNotEmpty();
    }

    @Before
    public void setUp() throws Exception {
        Settings.clearPreferences(sContext);
        mKeyDao = RkpdDatabase.getDatabase(sContext).provisionedKeyDao();
        mKeyDao.deleteAllKeys();
        SystemInterface systemInterface = ServiceManagerInterface.getInstance(mServiceName);
        ServiceManagerInterface.setInstances(new SystemInterface[] {systemInterface});
    }

    @After
    public void tearDown() {
        Settings.clearPreferences(sContext);
        mKeyDao.deleteAllKeys();
        ServiceManagerInterface.setInstances(null);
    }

    @Test
    public void testPeriodicProvisionerNoop() {
        // Similar to the PeriodicProvisioner round trip, except first we actually populate the
        // key pool to ensure that the PeriodicProvisioner just noops.
        // This test is purely to test out proper metrics.
        PeriodicProvisioner provisioner = TestWorkerBuilder.from(
                sContext,
                PeriodicProvisioner.class,
                Executors.newSingleThreadExecutor()).build();
        assertThat(provisioner.doWork()).isEqualTo(ListenableWorker.Result.success());
        StatsProcessor.PoolStats pool = StatsProcessor.processPool(mKeyDao, mServiceName,
                Settings.getExtraSignedKeysAvailable(sContext),
                Settings.getExpirationTime(sContext));

        // The metrics host test will perform additional validation by ensuring correct metrics
        // are recorded.
        assertThat(provisioner.doWork()).isEqualTo(ListenableWorker.Result.success());
        StatsProcessor.PoolStats updatedPool = StatsProcessor.processPool(mKeyDao, mServiceName,
                Settings.getExtraSignedKeysAvailable(sContext),
                Settings.getExpirationTime(sContext));
        assertThat(updatedPool.toString()).isEqualTo(pool.toString());
    }
}
