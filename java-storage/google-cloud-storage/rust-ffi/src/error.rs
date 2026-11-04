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

use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};

/// An error handed to Java. Java turns it into a `StorageException` the same way each
/// java-storage transport does today, so Rust doesn't need to know Java's error rules.
#[derive(Debug, PartialEq)]
pub(crate) struct FfiError {
    /// gRPC status code (0..=16) if the service returned a gRPC status, else -1.
    pub grpc_code: i32,
    /// HTTP status if the service returned an HTTP error (JSON API), else -1.
    pub http_status: i32,
    pub message: String,
}

pub(crate) type FfiResult<T> = Result<T, FfiError>;

impl FfiError {
    /// An error that didn't come from the service (bad input, panic, client setup).
    pub(crate) fn internal(message: impl Into<String>) -> Self {
        Self {
            grpc_code: -1,
            http_status: -1,
            message: message.into(),
        }
    }
}

impl From<google_cloud_storage::Error> for FfiError {
    fn from(e: google_cloud_storage::Error) -> Self {
        Self {
            grpc_code: e.status().map(|s| s.code as i32).unwrap_or(-1),
            http_status: e.http_status_code().map(i32::from).unwrap_or(-1),
            message: e.to_string(),
        }
    }
}

/// Runs `f` and turns a Rust panic into an error. A panic must never unwind into the JVM, so
/// every JNI entry point goes through this.
pub(crate) fn guarded<T>(name: &str, f: impl FnOnce() -> FfiResult<T>) -> FfiResult<T> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => Err(FfiError::internal(format!(
            "Rust panic in {name}: {}",
            panic_message(&*payload)
        ))),
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use google_cloud_gax::error::rpc::{Code, Status};

    #[test]
    fn grpc_status_keeps_code_and_message() {
        let status = Status::default()
            .set_code(Code::NotFound)
            .set_message("no such object");
        let e = FfiError::from(google_cloud_storage::Error::service(status));
        assert_eq!(e.grpc_code, 5);
        assert_eq!(e.http_status, -1);
        assert!(e.message.contains("no such object"), "{}", e.message);
    }

    #[test]
    fn http_error_keeps_status() {
        let e = FfiError::from(google_cloud_storage::Error::http(
            503,
            Default::default(),
            Default::default(),
        ));
        assert_eq!(e.http_status, 503);
    }

    #[test]
    fn non_service_error_has_no_codes() {
        let e = FfiError::from(google_cloud_storage::Error::io("connection reset"));
        assert_eq!((e.grpc_code, e.http_status), (-1, -1));
    }

    #[test]
    fn panic_becomes_error() {
        let r: FfiResult<()> = guarded("test_fn", || panic!("boom"));
        let e = r.unwrap_err();
        assert_eq!((e.grpc_code, e.http_status), (-1, -1));
        assert_eq!(e.message, "Rust panic in test_fn: boom");
    }
}
