//! Chuanmen P00 自有 JNI：注册真实平台回调和读取实验观察值。
use std::{io, sync::Arc};
use jni::{JNIEnv, JavaVM, objects::{GlobalRef, JClass, JObject, JValue}, sys::{jint, jstring}};
use easytier::socket_protector::{NativeSocketProtector, set_native_socket_protector};

struct Protector { vm: JavaVM, callback: GlobalRef }
#[easytier::p00_observation::async_trait]
impl NativeSocketProtector for Protector {
    async fn protect(&self, handle: u64) -> io::Result<()> {
        let fd = i32::try_from(handle).map_err(|_| io::Error::other("invalid fd"))?;
        // 当前 socket 创建任务仍拥有 fd；这里仅同步查询和保护，不复制或关闭。
        let port = easytier::p00_observation::socket_port(fd);
        let result = (|| {
            // JNIEnv 只属于当前 native 线程；GlobalRef 的 Arc 生命周期覆盖整个回调。
            let mut env = self.vm.attach_current_thread().map_err(|e| io::Error::other(e.to_string()))?;
            let value = env.call_method(self.callback.as_obj(), "protectForNative", "(I)Z", &[JValue::Int(fd)]);
            if env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
                return Err(io::Error::other("platform protect callback threw"));
            }
            let protected = value.and_then(|v| v.z()).map_err(|e| io::Error::other(e.to_string()))?;
            if protected { Ok(()) } else { Err(io::Error::new(io::ErrorKind::PermissionDenied, "platform protect returned false")) }
        })();
        easytier::p00_observation::protection(fd, port, result.is_ok());
        result
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_installProtector(
    mut env: JNIEnv, _class: JClass, callback: JObject,
) -> jint {
    let result = (|| {
        let vm = env.get_java_vm()?;
        let callback = env.new_global_ref(callback)?;
        set_native_socket_protector(Some(Arc::new(Protector { vm, callback })));
        Ok::<(), jni::errors::Error>(())
    })();
    if let Err(error) = result { let _ = env.throw_new("java/lang/RuntimeException", error.to_string()); return -1; }
    0
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_clearProtector(
    _env: JNIEnv, _class: JClass,
) { set_native_socket_protector(None); }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_resetObservations(
    _env: JNIEnv, _class: JClass,
) { easytier::p00_observation::reset(); }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_observations(
    env: JNIEnv, _class: JClass,
) -> jstring { env.new_string(easytier::p00_observation::snapshot()).unwrap().into_raw() }
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_probeProtectedSockets(
    env: JNIEnv, _class: JClass,
) -> jstring {
    env.new_string(easytier::p00_observation::socket_probe_sync()).unwrap().into_raw()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_github_zzwtsy_chuanmen_p00probe_P00NativeBridge_abortForExperiment(
    _env: JNIEnv, _class: JClass,
) { panic!("P00 synthetic panic in disposable instrumentation process"); }
