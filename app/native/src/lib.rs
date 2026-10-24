//! 串门的唯一 cdylib 入口；公开 JSON 协议隔离 JNI、FFI 和核心内部类型。
mod config_codec;
mod diagnostics;
mod probe;
mod protector;
mod protocol;
mod session;

use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString},
    sys::jstring,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_lib_vpn_NativeBindings_request(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
    callback: JObject,
) -> jstring {
    let response = match env.get_string(&request) {
        Ok(request) => protocol::execute(&mut env, &callback, &String::from(request)),
        Err(_) => serde_json::json!({"protocol": 1, "status": "failed", "code": "jni_error"}),
    };
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    match env.new_string(response.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
