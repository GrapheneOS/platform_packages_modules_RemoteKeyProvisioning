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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents an error that occurred while contacting the remote key provisioning server.
 */
public final class RkpdException extends Exception {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            Status.NO_NETWORK_CONNECTIVITY,
            Status.NETWORK_COMMUNICATION_ERROR,
            Status.DEVICE_NOT_REGISTERED,
            Status.HTTP_CLIENT_ERROR,
            Status.HTTP_SERVER_ERROR,
            Status.HTTP_UNKNOWN_ERROR,
            Status.OUT_OF_KEYS,
            Status.INTERNAL_ERROR,
    })
    public @interface ErrorCode {
    }

    private static final int HTTP_STATUS_DEVICE_NOT_REGISTERED = 444;
    private static final int HTTP_CLIENT_ERROR_HUNDREDS_DIGIT = 4;
    private static final int HTTP_SERVER_ERROR_HUNDREDS_DIGIT = 5;
    public enum Status {
        ;

        public static final int NO_NETWORK_CONNECTIVITY = 1;
        public static final int NETWORK_COMMUNICATION_ERROR = 2;
        public static final int DEVICE_NOT_REGISTERED = 3;
        public static final int HTTP_CLIENT_ERROR = 4;
        public static final int HTTP_SERVER_ERROR = 5;
        public static final int HTTP_UNKNOWN_ERROR = 6;
        public static final int OUT_OF_KEYS = 7;
        public static final int INTERNAL_ERROR = 8;
    }

    @ErrorCode
    private final int mErrorCode;

    /**
     * @param errorCode the underlying ServerInterface error
     * @param message describes the exception
     */
    public RkpdException(@ErrorCode int errorCode, String message) {
        super(message);
        mErrorCode = errorCode;
    }

    /**
     * @param errorCode the underlying ServerInterface error
     * @param message describes the exception
     * @param cause the underlying error that led this exception
     */
    public RkpdException(@ErrorCode int errorCode, String message, Throwable cause) {
        super(message, cause);
        mErrorCode = errorCode;
    }

    /**
     * @param httpStatus the HTTP status that lead to the error
     * @return a newly created RemoteProvisioningException that indicates an HTTP error occurred
     */
    public static RkpdException createFromHttpError(@ErrorCode int httpStatus) {
        String message = "HTTP error status encountered: " + httpStatus;
        if (httpStatus == HTTP_STATUS_DEVICE_NOT_REGISTERED) {
            return new RkpdException(Status.DEVICE_NOT_REGISTERED, message);
        }
        if ((httpStatus / 100) == HTTP_CLIENT_ERROR_HUNDREDS_DIGIT) {
            return new RkpdException(Status.HTTP_CLIENT_ERROR, message);
        }
        if ((httpStatus / 100) == HTTP_SERVER_ERROR_HUNDREDS_DIGIT) {
            return new RkpdException(Status.HTTP_SERVER_ERROR, message);
        }
        return new RkpdException(Status.HTTP_UNKNOWN_ERROR, message);
    }

    /**
     * @return the underlying error that caused the failure
     */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }
}
