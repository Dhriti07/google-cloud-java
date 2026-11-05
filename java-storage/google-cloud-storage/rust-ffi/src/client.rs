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

use crate::error::{FfiError, FfiResult};
use google_cloud_storage::client::{Storage, StorageControl};
use tokio::runtime::{Builder, Runtime};

/// One Rust client per Java `Storage` instance.
///
/// `storage` serves the data path (bidi reads/writes, JSON reads/writes). `control` serves the
/// other gRPC calls (list objects, list buckets). Both share one multi-threaded Tokio runtime,
/// the setup a Rust application would use.
pub(crate) struct Client {
    pub(crate) rt: Runtime,
    pub(crate) storage: Storage,
    pub(crate) control: StorageControl,
}

impl Client {
    /// `endpoint` overrides the default `https://storage.googleapis.com`. Credentials come from
    /// Application Default Credentials, the same account the Java client uses by default.
    /// `grpc_subchannels` sets the data-path connection count; `None` keeps the SDK default
    /// (one per CPU). `control` always keeps its SDK default.
    pub(crate) fn new(
        endpoint: Option<String>,
        grpc_subchannels: Option<usize>,
    ) -> FfiResult<Self> {
        let rt = Builder::new_multi_thread()
            .enable_all()
            .thread_name("gcs-rust")
            .build()
            .map_err(|e| FfiError::internal(format!("failed to start Tokio runtime: {e}")))?;
        let (storage, control) = rt.block_on(async {
            let mut storage = Storage::builder();
            let mut control = StorageControl::builder();
            if let Some(endpoint) = endpoint {
                storage = storage.with_endpoint(endpoint.clone());
                control = control.with_endpoint(endpoint);
            }
            if let Some(n) = grpc_subchannels {
                storage = storage.with_grpc_subchannel_count(n);
            }
            let storage = storage
                .build()
                .await
                .map_err(|e| FfiError::internal(format!("failed to create Storage client: {e}")))?;
            let control = control.build().await.map_err(|e| {
                FfiError::internal(format!("failed to create StorageControl client: {e}"))
            })?;
            Ok::<_, FfiError>((storage, control))
        })?;
        Ok(Self {
            rt,
            storage,
            control,
        })
    }
}
