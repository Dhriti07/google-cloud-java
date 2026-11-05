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

//! Completing Java futures from Rust.
//!
//! An async JNI call returns at once. The work runs as a Tokio task, which reports the result
//! by calling `succeeded(long, byte[])` or `failed(int, int, String)` on the Java
//! `RustCompletion` object it was given. No Java thread waits while the work runs.

use crate::error::{FfiError, FfiResult, panic_message};
use futures::FutureExt;
use jni::objects::{GlobalRef, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use std::future::Future;
use std::panic::AssertUnwindSafe;
use std::sync::Arc;
use tokio::runtime::Runtime;

/// The value a task hands back to Java: a number (a handle or a byte count) and optional bytes.
pub(crate) type Outcome = (i64, Option<Vec<u8>>);

/// A Java `RustCompletion`, kept alive by a global reference until the task reports back.
pub(crate) struct Completion {
    vm: Arc<JavaVM>,
    target: GlobalRef,
}

impl Completion {
    pub(crate) fn new(env: &mut JNIEnv, target: &JObject) -> FfiResult<Self> {
        if target.is_null() {
            return Err(FfiError::internal("null completion"));
        }
        Ok(Self {
            vm: Arc::new(env.get_java_vm()?),
            target: env.new_global_ref(target)?,
        })
    }

    /// The JVM, for tasks that need to call into Java before they finish.
    pub(crate) fn vm(&self) -> Arc<JavaVM> {
        self.vm.clone()
    }

    fn report(self, result: FfiResult<Outcome>) {
        // Tokio worker threads stay attached as daemons, so this is cheap after the first call.
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
            return;
        };
        // A local frame, because local references on an attached native thread are never freed
        // otherwise.
        let _ = env.with_local_frame(4, |env| -> jni::errors::Result<()> {
            match &result {
                Ok((value, bytes)) => {
                    let array = match bytes {
                        Some(b) => JObject::from(env.byte_array_from_slice(b)?),
                        None => JObject::null(),
                    };
                    env.call_method(
                        &self.target,
                        "succeeded",
                        "(J[B)V",
                        &[JValue::Long(*value), JValue::Object(&array)],
                    )?;
                }
                Err(e) => {
                    let message = env.new_string(&e.message)?;
                    env.call_method(
                        &self.target,
                        "failed",
                        "(IILjava/lang/String;)V",
                        &[
                            JValue::Int(e.grpc_code),
                            JValue::Int(e.http_status),
                            JValue::Object(&message),
                        ],
                    )?;
                }
            }
            Ok(())
        });
        // Never leave a Java exception pending on a Rust thread.
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

/// Runs `work` on the client's runtime and reports its result, or its panic, to `completion`.
pub(crate) fn spawn_completing<F>(rt: &Runtime, completion: Completion, work: F)
where
    F: Future<Output = FfiResult<Outcome>> + Send + 'static,
{
    rt.spawn(async move {
        let result = match AssertUnwindSafe(work).catch_unwind().await {
            Ok(result) => result,
            Err(payload) => Err(FfiError::internal(format!(
                "Rust panic in async task: {}",
                panic_message(&*payload)
            ))),
        };
        completion.report(result);
    });
}
