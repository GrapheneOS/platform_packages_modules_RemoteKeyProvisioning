// Copyright 2022, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! This crate implements rkpd

use log::info;

use android_security_rkpd::aidl::android::security::rkpd::{
    IRefresh::IRefresh,
    IRegistrar::IRegistrar,
    IRegistration::{BnRegistration, IRegistration},
    RemotelyProvisionedKey::RemotelyProvisionedKey,
};

use android_security_rkpd::binder::{BinderFeatures, Interface, Result as BinderResult, Strong};

/// Implements IRegistration AIDL
pub struct MyRegistration;

impl Interface for MyRegistration {}

impl IRegistration for MyRegistration {
    fn getRemotelyProvisionedKey(&self, key_id: i32) -> BinderResult<RemotelyProvisionedKey> {
        info!("keyId provided: {}", key_id);
        Ok(RemotelyProvisionedKey { keyBlob: vec![0; 32], encodedCertChain: vec![0; 32] })
    }

    fn upgradeKey(&self, key_id: i32, _old_key_blob: &[u8]) -> BinderResult<std::vec::Vec<u8>> {
        info!("keyId provided: {}", key_id);
        Ok(vec![0; 32])
    }
}

/// Implements IRegistrar AIDL
pub struct MyRegistrar;

impl Interface for MyRegistrar {}

impl IRegistrar for MyRegistrar {
    /// Provides registration for given IRemotelyProvisionedComponent
    fn getRegistration(
        &self,
        irpc_name: &str,
        is_rkp_only: bool,
    ) -> BinderResult<Strong<dyn IRegistration>> {
        info!(
            "Called rkpd to get registration for {} with isRkpOnly as {}",
            irpc_name, is_rkp_only
        );
        let result = BnRegistration::new_binder(
            MyRegistration {},
            BinderFeatures { set_requesting_sid: true, ..BinderFeatures::default() },
        );
        Ok(result)
    }
}

/// Implements IRefresh AIDL
pub struct MyRefresh;

impl Interface for MyRefresh {}

impl IRefresh for MyRefresh {
    fn refreshData(&self) -> BinderResult<i32> {
        Ok(0)
    }
}
