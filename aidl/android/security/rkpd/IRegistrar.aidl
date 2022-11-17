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

import android.security.rkpd.IRegistration;

/**
 * {@link IRegistrar} is the interface provided to use the remote key
 * provisioning functionality from the Remote Key Provisioning Daemon (RKPD).
 * This would be the first service that RKPD clients would interact with. The
 * intent is for the clients to get the {@link IRegistration} object from this
 * interface and use it for actual remote provisioning work.
 *
 * @hide
 */
interface IRegistrar {
    /**
     * Takes a remotely provisioned component service name and gets a
     * registration bound to that service and the caller's UID.
     *
     * @param irpcName The name of the IRemotelyProvisionedComponent for which
     * remotely provisioned keys should be managed.
     *
     * Notes:
     * - This function will attempt to get the service named by irpcName. This
     *   implies that a lazy/dynamic aidl service will be instantiated, and this
     *   function blocks until the service is up. Upon return, any binder tokens
     *   are dropped, allowing the lazy/dynamic service to shutdown.
     * - The returned registration object is unique per caller. If two different
     *   UIDs call getRegistration with the same irpcName, they will receive
     *   different registrations back. This prevents two different applications
     *   from being able to see the same keys.
     * - This function is idempotent per calling UID. Additional calls to
     *   getRegistration with the same parameters, from the same caller, will have
     *   no side effects.
     *
     * Errors:
     * - If irpcName does not reference an IRemotelyProvisionedComponent that can
     *   be fetched from IServiceManager, this function fails with
     *   STATUS_BAD_VALUE.
     *
     * @see IRegistration#getRemotelyProvisionedKey()
     * @see IRegistration#waitForRemotelyProvisionedKey()
     * @see IRemotelyProvisionedComponent
     *
     * @return an IRegistration that is used to fetch remotely provisioned
     * keys for the given IRemotelyProvisionedComponent.
     */
    IRegistration getRegistration(String irpcName);
}
