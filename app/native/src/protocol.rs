//! 只返回稳定错误码，TOML、凭据及异常原文不进入回执或诊断。
use jni::{JNIEnv, objects::JObject};
use serde::Deserialize;
use serde_json::{Value, json};

pub const PROTOCOL: u32 = 1;
pub const CORE_REVISION: &str = "7592951b808c4839cb9d77cc4bc642f124915951";

#[derive(Clone, Deserialize)]
pub struct Request {
    pub protocol: u32,
    pub action: String,
    pub session_id: Option<String>,
    pub generation: Option<u64>,
    pub operation: Option<u64>,
    pub attachment: Option<u64>,
    pub saved_revision: Option<u64>,
    pub fd: Option<i32>,
    pub config: Option<String>,
    pub patch: Option<Value>,
    pub debug: Option<bool>,
}

impl Request {
    pub fn identity(&self) -> Result<(String, u64, u64), &'static str> {
        let id = self
            .session_id
            .as_ref()
            .filter(|id| !id.is_empty() && id.len() <= 128)
            .ok_or("invalid_identity")?;
        let generation = self
            .generation
            .filter(|value| *value > 0 && *value <= i64::MAX as u64)
            .ok_or("invalid_identity")?;
        let operation = self
            .operation
            .filter(|value| *value > 0 && *value <= i64::MAX as u64)
            .ok_or("invalid_identity")?;
        Ok((id.clone(), generation, operation))
    }
}

pub fn reply(request: &Request, status: &str, value: Value) -> Value {
    json!({"protocol": PROTOCOL, "status": status, "session_id": request.session_id,
        "generation": request.generation, "operation": request.operation, "attachment": request.attachment,
        "result": value})
}

pub fn failure(request: &Request, status: &str, code: &str) -> Value {
    let mut response = reply(request, status, Value::Null);
    response["code"] = json!(code);
    response
}

pub fn execute(env: &mut JNIEnv<'_>, callback: &JObject<'_>, text: &str) -> Value {
    crate::diagnostics::initialize();
    if text.len() > 256 * 1024 {
        return json!({"protocol": PROTOCOL, "status": "failed", "code": "input_too_large"});
    }
    let request: Request = match serde_json::from_str(text) {
        Ok(request) => request,
        Err(_) => {
            return json!({"protocol": PROTOCOL, "status": "failed", "code": "invalid_request"});
        }
    };
    if request.protocol != PROTOCOL {
        return failure(&request, "failed", "protocol_mismatch");
    }
    match request.action.as_str() {
        "build_info" => reply(
            &request,
            "confirmed",
            json!({"core_revision": CORE_REVISION,
            "core_version": easytier::VERSION, "bridge_version": env!("CARGO_PKG_VERSION"),
            "abi": if cfg!(target_arch = "aarch64") { "arm64-v8a" } else { "x86_64" },
            "platform": std::env::consts::OS, "features": ["upstream-default", "session-confirmations", "tun-counters", "config-codec-v2", "observability-v1", "observability-v2", "icmp-datagram"]}),
        ),
        "validate" => match request.config.as_deref().and_then(|config| {
            easytier::common::config::TomlConfigLoader::new_from_str(config).ok()
        }) {
            Some(_) => reply(&request, "confirmed", json!({"valid": true})),
            None => failure(&request, "failed", "invalid_config"),
        },
        "config_parse" | "config_patch" | "config_runtime" => {
            match crate::config_codec::execute(
                &request.action,
                request.config.as_deref().unwrap_or(""),
                request.patch.as_ref(),
            ) {
                Ok(value) => reply(&request, "confirmed", value),
                Err(error) => {
                    let mut response = failure(&request, "failed", error.code);
                    response["result"] = json!({"key": error.key, "offset": error.offset});
                    response
                }
            }
        }
        "start" => crate::session::start(env, callback, &request),
        "attach" => crate::session::attach(&request, false),
        "detach" => crate::session::attach(&request, true),
        "stop" => crate::session::stop(&request),
        "release" => crate::session::release(&request),
        "query" => crate::session::query(&request),
        "diagnostics" => reply(&request, "confirmed", crate::diagnostics::drain()),
        "probe_nonblocking" => match request.fd.and_then(|fd| crate::probe::nonblocking(fd).ok()) {
            Some(()) => reply(&request, "confirmed", json!({"nonblocking": true})),
            None => failure(&request, "failed", "probe_socket_unavailable"),
        },
        "diagnostics_debug" => match request.debug {
            Some(enabled) => {
                crate::diagnostics::set_debug(enabled);
                reply(&request, "confirmed", json!({"enabled": enabled}))
            }
            None => failure(&request, "failed", "invalid_request"),
        },
        _ => failure(&request, "failed", "unsupported_operation"),
    }
}
