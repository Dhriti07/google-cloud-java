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

//! List Objects (gRPC `ListObjects`) through the Rust SDK's `StorageControl::list_objects`.
//! One call returns one page; Java asks for the next page with the returned token.

use crate::error::FfiError;
use crate::jni_client::{client, run};
use crate::proto;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jlong};
use std::ptr;

/// Takes `google.storage.v2.ListObjectsRequest` bytes and returns one page as
/// `google.storage.v2.ListObjectsResponse` bytes. Blocks the calling Java thread, like the Java
/// `list` call does.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_google_cloud_storage_RustNative_listObjects<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    client_handle: jlong,
    request: JByteArray<'local>,
) -> jbyteArray {
    run(&mut env, "listObjects", ptr::null_mut(), |env| {
        let c = client(client_handle)?;
        let req = proto::list_objects_request(&env.convert_byte_array(&request)?)
            .map_err(|e| FfiError::internal(format!("invalid ListObjectsRequest: {e}")))?;
        let resp =
            c.rt.block_on(c.control.list_objects().with_request(req).send())?;
        Ok(env
            .byte_array_from_slice(&proto::encode_list_objects_response(&resp))?
            .into_raw())
    })
}
