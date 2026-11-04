// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! JNI entry points for `com.google.cloud.storage.RustNative`.
//!
//! Function names must match `Java_<package with _>_<class>_<method>`.

use crate::client::Client;
use crate::error::{FfiError, FfiResult, guarded};
use jni::JNIEnv;
use jni::objects::{JClass, JString, JThrowable, JValue};
use jni::sys::jlong;

const NATIVE_EXCEPTION: &str = "com/google/cloud/storage/RustNativeException";

/// Throws `RustNativeException(grpcCode, httpStatus, message)` in Java, unless a Java exception
/// is already pending.
pub(crate) fn throw(env: &mut JNIEnv, e: &FfiError) {
    if env.exception_check().unwrap_or(false) {
        return;
    }
    let thrown = (|| -> jni::errors::Result<()> {
        let message = env.new_string(&e.message)?;
        let ex = env.new_object(
            NATIVE_EXCEPTION,
            "(IILjava/lang/String;)V",
            &[
                JValue::Int(e.grpc_code),
                JValue::Int(e.http_status),
                JValue::Object(&message),
            ],
        )?;
        env.throw(JThrowable::from(ex))
    })();
    if thrown.is_err() && !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/lang/RuntimeException", &e.message);
    }
}

/// Runs `f` behind the panic guard. On error, throws in Java and returns `default`.
pub(crate) fn run<'local, T>(
    env: &mut JNIEnv<'local>,
    name: &str,
    default: T,
    f: impl FnOnce(&mut JNIEnv<'local>) -> FfiResult<T>,
) -> T {
    match guarded(name, || f(env)) {
        Ok(v) => v,
        Err(e) => {
            throw(env, &e);
            default
        }
    }
}

/// The client behind a handle from `clientNew`.
pub(crate) fn client<'a>(handle: jlong) -> FfiResult<&'a Client> {
    if handle == 0 {
        Err(FfiError::internal("null client handle"))
    } else {
        // Safety: a live handle from clientNew; Java frees it only after all calls finish.
        Ok(unsafe { &*(handle as *const Client) })
    }
}

/// Copies a non-null Java string.
pub(crate) fn java_string(env: &mut JNIEnv, s: &JString) -> FfiResult<String> {
    if s.is_null() {
        return Err(FfiError::internal("null string argument"));
    }
    Ok(env.get_string(s)?.into())
}

/// Creates a client. Returns an opaque handle that Java must pass to `clientClose` exactly once.
/// `endpoint` may be null for the default endpoint.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_clientNew<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    endpoint: JString<'local>,
) -> jlong {
    run(&mut env, "clientNew", 0, |env| {
        let endpoint = if endpoint.is_null() {
            None
        } else {
            Some(java_string(env, &endpoint)?)
        };
        let client = Client::new(endpoint)?;
        Ok(Box::into_raw(Box::new(client)) as jlong)
    })
}

/// Frees a client created by `clientNew`. A zero handle is ignored.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_clientClose<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    run(&mut env, "clientClose", (), |_| {
        if handle != 0 {
            // Safety: `handle` came from `clientNew` and Java frees it only once.
            drop(unsafe { Box::from_raw(handle as *mut Client) });
        }
        Ok(())
    })
}
