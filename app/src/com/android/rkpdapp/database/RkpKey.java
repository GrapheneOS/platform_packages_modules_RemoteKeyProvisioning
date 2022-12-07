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

package com.android.rkpdapp.database;

import co.nstant.in.cbor.model.DataItem;

/**
 * In-memory key representation for Remotely Provisioned Keys.
 */
public final class RkpKey {
    private final ProvisionedKey mPersistentKey;
    private final byte[] mMacedPublicKey;
    private final DataItem mCoseKey;

    public RkpKey(ProvisionedKey key, byte[] macedPublicKey, DataItem coseKey) {
        this.mPersistentKey = key;
        this.mMacedPublicKey = macedPublicKey;
        this.mCoseKey = coseKey;
    }

    public ProvisionedKey getProvisionedKey() {
        return mPersistentKey;
    }

    public byte[] getMacedPublicKey() {
        return mMacedPublicKey;
    }

    public DataItem getCoseKey() {
        return mCoseKey;
    }
}
