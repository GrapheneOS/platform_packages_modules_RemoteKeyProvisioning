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
     * @param isRkpOnly Informs the registrar if this remotely provisioned
     * component has a fallback mechanism or if it's totally reliant upon
     * remote key provisioning. Some remotely provisioned components may have a
     * factory-provisioned key that may be used as a fallback in case remote key
     * provisioning fails. Such components are NOT "RKP only". Other components
     * may only function if a remotely provisioned key is available, in which
     * case this parameter must be true.
     *
     * If the pool of remotely provisioned keys for a component is empty, and a
     * client calls {@link IRegistration#getRemotelyProvisionedKey()}, the value
     * of isRkpOnly determines behavior when no keys are available:
     * 1. If isRkpOnly is true, then IRegistration.getRemotelyProvisionedKey()
     *    blocks until either an error occurs or keys are provisioned.
     * 2. If isRkpOnly is false then IRegistration.getRemotelyProvisionedKey()
     *    returns null immediately.
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
     * - Multiple calls with the same component but with different values for
     *   isRkpOnly during the same boot will fail with STATUS_BAD_VALUE. That
     *   is, a IRemotelyProvisionedComponent must be consistently "RKP only" or
     *   not per boot.
     * - If irpcName does not reference an IRemotelyProvisionedComponent that can
     *   be fetched from IServiceManager, this function fails with
     *   STATUS_BAD_VALUE.
     *
     * @see IRegistration#getRemotelyProvisionedKey()
     * @see IRemotelyProvisionedComponent
     *
     * @return an IRegistration that is used to fetch remotely provisioned
     * keys for the given IRemotelyProvisionedComponent.
     */
    IRegistration getRegistration(String irpcName, boolean isRkpOnly);
}
