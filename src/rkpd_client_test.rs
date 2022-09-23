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

use android_security_rkpd::aidl::android::security::rkpd::{
    IRefresh::IRefresh, IRegistrar::IRegistrar,
};

use binder::{wait_for_interface, Strong};

static RKPD_REGISTRAR_SERVICE_NAME: &str = "rkpd.registrar";
static RKPD_REFRESH_SERVICE_NAME: &str = "rkpd.refresh";

fn get_registrar_service() -> Strong<dyn IRegistrar> {
    wait_for_interface::<dyn IRegistrar>(RKPD_REGISTRAR_SERVICE_NAME).unwrap()
}

fn get_refresh_service() -> Strong<dyn IRefresh> {
    wait_for_interface::<dyn IRefresh>(RKPD_REFRESH_SERVICE_NAME).unwrap()
}

#[test]
fn test_function() {
    let registrar_service = get_registrar_service();
    let a = registrar_service.getRegistration("some remotely provisioned component", true);
    if let Ok(value) = a {
        let _b = value.getRemotelyProvisionedKey(123123);
        let _c = value.upgradeKey(123123, &[0; 32]);
    }
    let refresh_service = get_refresh_service();
    let _d = refresh_service.refreshData();
}
