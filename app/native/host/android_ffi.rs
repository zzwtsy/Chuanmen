//! 本项目共享固定 FFI 宿主的 runtime 与管理器，不再创建第二份进程上下文。
use easytier::instance::factory::NativeInstanceManager;
use std::sync::atomic::{AtomicU64, Ordering};
use std::{future::Future, sync::Arc};

static RUNTIME_THREADS: AtomicU64 = AtomicU64::new(0);

/// 由共享 runtime 的真实线程创建/退出钩子调用，包含 worker 和阻塞池线程。
pub fn thread_started() {
    RUNTIME_THREADS.fetch_add(1, Ordering::Relaxed);
}

pub fn thread_stopped() {
    RUNTIME_THREADS.fetch_sub(1, Ordering::Relaxed);
}

pub fn runtime_threads() -> u64 {
    RUNTIME_THREADS.load(Ordering::Relaxed).min(i64::MAX as u64)
}

pub fn manager() -> Arc<NativeInstanceManager> {
    crate::state::ffi_context().manager.clone()
}

/// 只供 Kotlin 的后台 JNI 调度线程调用，不能在 Tokio worker 内嵌套 block_on。
pub fn block_on<F: Future>(future: F) -> F::Output {
    crate::state::ffi_context().runtime.block_on(future)
}
