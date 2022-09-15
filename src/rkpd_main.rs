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

use log::{error, info};
use std::panic;

use rkpd::{MyRefresh, MyRegistrar};

use android_security_rkpd::aidl::android::security::rkpd::{
    IRefresh::BnRefresh, IRegistrar::BnRegistrar,
};

use android_security_rkpd::binder::BinderFeatures;

static RKPD_SERVICE_NAME: &str = "com.android.rkpd";

fn main() {
    // Initialize android logging
    android_logger::init_once(
        android_logger::Config::default().with_tag("rkpd").with_min_level(log::Level::Debug),
    );
    // Redirect panic messages to logcat
    panic::set_hook(Box::new(|panic_info| {
        error!("{}", panic_info);
    }));

    info!("{} starting up", RKPD_SERVICE_NAME);

    let my_registrar = MyRegistrar;

    let my_registrar_binder = BnRegistrar::new_binder(my_registrar, BinderFeatures::default());

    binder::register_lazy_service("rkpd.registrar", my_registrar_binder.as_binder())
        .expect("Failed to register registrar");

    let my_refresh = MyRefresh;
    let my_refresh_binder = BnRefresh::new_binder(my_refresh, BinderFeatures::default());

    binder::register_lazy_service("rkpd.refresh", my_refresh_binder.as_binder())
        .expect("Failed to register refresh");

    binder::ProcessState::join_thread_pool();
}
