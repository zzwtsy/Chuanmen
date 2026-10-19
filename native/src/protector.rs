//! Service 回调的借用范围；停止先撤销能力，再等全部已进入的回调返回。
use easytier::socket_protector::NativeSocketProtector;
use jni::{
    JavaVM,
    objects::{GlobalRef, JValue},
};
use std::{
    io,
    sync::{
        Arc, Condvar, Mutex,
        atomic::{AtomicU64, Ordering},
    },
};

#[derive(Default)]
struct GateState {
    closed: bool,
    in_flight: usize,
}

#[derive(Default)]
pub struct CallbackGate {
    state: Mutex<GateState>,
    idle: Condvar,
}

pub struct Permit<'a>(&'a CallbackGate);
impl Drop for Permit<'_> {
    fn drop(&mut self) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.in_flight -= 1;
        if state.in_flight == 0 {
            self.0.idle.notify_all();
        }
    }
}

impl CallbackGate {
    pub fn enter(&self) -> io::Result<Permit<'_>> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.closed {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "service revoked",
            ));
        }
        state.in_flight += 1;
        Ok(Permit(self))
    }

    pub fn close_and_wait(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.closed = true;
        while state.in_flight != 0 {
            state = self
                .idle
                .wait(state)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
        }
    }

    pub fn count(&self) -> usize {
        self.state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .in_flight
    }
}

pub struct Protector {
    pub gate: CallbackGate,
    pub vm: JavaVM,
    pub callback: GlobalRef,
    pub session_id: String,
    pub generation: i64,
    pub calls: AtomicU64,
    pub failures: AtomicU64,
}

#[async_trait::async_trait]
impl NativeSocketProtector for Protector {
    async fn protect(&self, handle: u64) -> io::Result<()> {
        let _permit = self.gate.enter()?;
        self.calls.fetch_add(1, Ordering::Relaxed);
        let result = self.invoke(handle);
        if result.is_err() {
            self.failures.fetch_add(1, Ordering::Relaxed);
        }
        result
    }
}

impl Protector {
    fn invoke(&self, handle: u64) -> io::Result<()> {
        let fd = i32::try_from(handle).map_err(|_| io::Error::other("invalid socket handle"))?;
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|_| io::Error::other("JNI attach failed"))?;
        let session = env
            .new_string(&self.session_id)
            .map_err(|_| io::Error::other("JNI string failed"))?;
        let result = env.call_method(
            self.callback.as_obj(),
            "protectForNative",
            "(ILjava/lang/String;J)Z",
            &[
                JValue::Int(fd),
                JValue::Object(&session),
                JValue::Long(self.generation),
            ],
        );
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "platform callback threw",
            ));
        }
        if result.and_then(|value| value.z()).unwrap_or(false) {
            Ok(())
        } else {
            Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "protect or bind failed",
            ))
        }
    }
}

pub struct DenyProtector;
#[async_trait::async_trait]
impl NativeSocketProtector for DenyProtector {
    async fn protect(&self, _handle: u64) -> io::Result<()> {
        Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "protector not registered",
        ))
    }
}

pub fn deny_new_sockets() {
    easytier::socket_protector::set_native_socket_protector(Some(Arc::new(DenyProtector)));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::mpsc, thread, time::Duration};

    /// 停止在已有保护回调结束前不能返回；停止后新回调被拒绝。
    #[test]
    fn close_waits_for_callback_and_rejects_new_calls() {
        let gate = Arc::new(CallbackGate::default());
        let permit = gate.enter().unwrap();
        let (send, receive) = mpsc::channel();
        let closer = gate.clone();
        let thread = thread::spawn(move || {
            closer.close_and_wait();
            send.send(()).unwrap();
        });
        assert!(receive.recv_timeout(Duration::from_millis(20)).is_err());
        assert_eq!(gate.count(), 1);
        drop(permit);
        receive.recv_timeout(Duration::from_secs(1)).unwrap();
        thread.join().unwrap();
        assert_eq!(gate.count(), 0);
        assert!(gate.enter().is_err());
    }

    /// 无 Service 能力时默认拒绝保护请求，不能继承上游未注册钩子的成功语义。
    #[tokio::test]
    async fn absent_service_is_fail_closed() {
        assert!(DenyProtector.protect(1).await.is_err());
    }
}
