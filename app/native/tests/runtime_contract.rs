//! 线程计数由实际 Tokio 生命周期驱动，不依赖 Android 线程名或固定配置值。
use easytier_ffi::android_host;
use std::time::{Duration, Instant};

/// 建立并退出双 worker 的真实 runtime 后，进程计数增加并回到原基线。
#[test]
fn runtime_thread_lifecycle_returns_to_baseline() {
    let baseline = android_host::runtime_threads();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .on_thread_start(android_host::thread_started)
        .on_thread_stop(android_host::thread_stopped)
        .build()
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(2);
    while android_host::runtime_threads() != baseline + 2 {
        assert!(Instant::now() < deadline, "workers did not start");
        std::thread::yield_now();
    }
    drop(runtime);
    assert_eq!(android_host::runtime_threads(), baseline);
}
