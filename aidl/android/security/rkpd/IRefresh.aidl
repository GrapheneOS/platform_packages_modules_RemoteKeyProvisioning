/*
 * Copyright 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.rkpd;

/**
 * The IRefresh interface is responsible for background refreshes of expired
 * certificates. This interface must be regularly called by a scheduled (or
 * long-lived) system component so that the remote provisioning subsystem can
 * get rid of expired or expiring data, replacing them with freshly provisioned
 * data.
 *
 * @hide
 */
interface IRefresh {
    /**
     * Informs the service that it should perform a refresh of remotely
     * provisioned data, replacing any expired or expiring data with new. This
     * would help to control the policy on how often refreshes occur. The value
     * returned can be updated via mainline updates.
     *
     * @return the number of seconds to delay before calling this function again
     */
    int refreshData();
}
