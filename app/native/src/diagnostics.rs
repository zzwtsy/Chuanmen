//! 有界诊断只接收静态事件分类和安全数字；上游消息、地址和错误原文不进入日志。
use easytier::{
    common::global_ctx::{EventBusSubscriber, GlobalCtxEvent},
    instance::factory::{NativeCoreInstance, subscribe_native_instance_event},
};
use serde::Serialize;
use std::collections::{BTreeMap, VecDeque};
use std::sync::{
    LazyLock, Mutex, Once,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
use std::time::Instant;
use tokio::{
    sync::{broadcast::error::RecvError, oneshot},
    task::JoinHandle,
};
use tracing::{Event, Subscriber};
use tracing_subscriber::{Layer, layer::Context, prelude::*};

const CAPACITY: usize = 128;
static START: LazyLock<Instant> = LazyLock::new(Instant::now);
static RECORDS: Mutex<VecDeque<Record>> = Mutex::new(VecDeque::new());
static DROPPED: AtomicU64 = AtomicU64::new(0);
static INIT: Once = Once::new();
static DEBUG_ENABLED: AtomicBool = AtomicBool::new(false);

pub fn set_debug(enabled: bool) {
    DEBUG_ENABLED.store(enabled, Ordering::Relaxed);
}

#[derive(Serialize)]
pub struct Record {
    elapsed_millis: u64,
    scope: &'static str,
    level: &'static str,
    target: &'static str,
    code: &'static str,
    fields: BTreeMap<&'static str, String>,
}

fn record(level: &'static str, target: &'static str, code: &'static str) {
    record_count(level, target, code, None);
}

fn record_count(
    level: &'static str,
    target: &'static str,
    code: &'static str,
    count: Option<usize>,
) {
    if level == "DEBUG" && !DEBUG_ENABLED.load(Ordering::Relaxed) {
        return;
    }
    let Ok(mut records) = RECORDS.try_lock() else {
        DROPPED.fetch_add(1, Ordering::Relaxed);
        return;
    };
    if records.len() == CAPACITY {
        records.pop_front();
        DROPPED.fetch_add(1, Ordering::Relaxed);
    }
    records.push_back(Record {
        elapsed_millis: START.elapsed().as_millis().min(i64::MAX as u128) as u64,
        scope: "core_process",
        level,
        target,
        code,
        fields: count
            .map(|value| BTreeMap::from([("count", value.to_string())]))
            .unwrap_or_default(),
    });
}

/// 仅匹配结构化事件的变体；地址、认证、配置和错误字段从不读取或格式化。
fn core_event(event: GlobalCtxEvent) {
    let (level, target, code, count) = match event {
        GlobalCtxEvent::PeerAdded(_) => ("INFO", "peer", "peer_added", None),
        GlobalCtxEvent::PeerRemoved(_) => ("INFO", "peer", "peer_removed", None),
        GlobalCtxEvent::PeerConnAdded(_) => ("INFO", "peer", "peer_connection_added", None),
        GlobalCtxEvent::PeerConnRemoved(_) => ("INFO", "peer", "peer_connection_removed", None),
        GlobalCtxEvent::Connecting(_) => ("DEBUG", "peer", "peer_connecting", None),
        GlobalCtxEvent::ConnectError(..) => ("WARN", "peer", "peer_connect_failed", None),
        GlobalCtxEvent::ListenerAdded(_) => ("INFO", "tunnel", "listener_added", None),
        GlobalCtxEvent::ListenerAddFailed(..) => ("ERROR", "tunnel", "listener_add_failed", None),
        GlobalCtxEvent::ListenerAcceptFailed(..) => {
            ("WARN", "tunnel", "listener_accept_failed", None)
        }
        GlobalCtxEvent::ConnectionAccepted(..) => ("INFO", "tunnel", "tunnel_accepted", None),
        GlobalCtxEvent::ConnectionError(..) => ("WARN", "tunnel", "tunnel_admission_failed", None),
        GlobalCtxEvent::DhcpIpv4Changed(..) => ("INFO", "config", "ipv4_changed", None),
        GlobalCtxEvent::DhcpIpv4Conflicted(_) => ("WARN", "config", "ipv4_conflicted", None),
        GlobalCtxEvent::ProxyCidrsUpdated(added, removed) => (
            "INFO",
            "route",
            "proxy_routes_changed",
            Some(added.len() + removed.len()),
        ),
        GlobalCtxEvent::TunDeviceReady(_) => ("INFO", "tunnel", "tun_ready", None),
        GlobalCtxEvent::TunDeviceError(_) => ("ERROR", "tunnel", "tun_error", None),
        _ => return,
    };
    record_count(level, target, code, count);
}

/// 会话只拥有订阅任务，任务不持有实例、TUN 或 JNI 回调，避免延长资源生命周期。
pub struct Subscription {
    stop: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<()>>,
}

impl Subscription {
    fn new(mut events: EventBusSubscriber) -> Self {
        let (stop, mut stopped) = oneshot::channel();
        let task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    biased;
                    _ = &mut stopped => {
                        DROPPED.fetch_add(events.len() as u64, Ordering::Relaxed);
                        break;
                    }
                    event = events.recv() => match event {
                        Ok(event) => core_event(event),
                        Err(RecvError::Lagged(count)) => { DROPPED.fetch_add(count, Ordering::Relaxed); }
                        Err(RecvError::Closed) => break,
                    }
                }
            }
        });
        Self {
            stop: Some(stop),
            task: Some(task),
        }
    }

    pub async fn stop(mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(task) = self.task.take() {
            if task.await.is_err() {
                record("WARN", "core", "core_events_unavailable");
            }
        }
    }
}

impl Drop for Subscription {
    fn drop(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

pub fn subscribe(instance: &NativeCoreInstance) -> Option<Subscription> {
    match subscribe_native_instance_event(instance) {
        Some(events) => Some(easytier_ffi::android_host::block_on(async {
            Subscription::new(events)
        })),
        None => {
            record("WARN", "core", "core_events_unavailable");
            None
        }
    }
}

// 较具体的模块前缀优先；未识别来源只保留通用核心分类。
fn module(target: &str) -> &'static str {
    const PREFIXES: &[(&str, &str)] = &[
        ("easytier_core::peers::route", "route"),
        ("easytier::peers::route", "route"),
        ("easytier_core::tunnel", "tunnel"),
        ("easytier::tunnel", "tunnel"),
        ("easytier::instance::runtime_host::tun", "tunnel"),
        ("easytier::instance::virtual_nic", "tunnel"),
        ("easytier_core::peers", "peer"),
        ("easytier::peers", "peer"),
        ("easytier_core::dns", "dns"),
        ("easytier::dns", "dns"),
        ("easytier_core::config", "config"),
        ("easytier::common::config", "config"),
    ];
    PREFIXES
        .iter()
        .find_map(|(prefix, module)| target.starts_with(prefix).then_some(*module))
        .unwrap_or("core")
}

struct SafeLayer;
impl<S: Subscriber> Layer<S> for SafeLayer {
    fn on_event(&self, event: &Event<'_>, _context: Context<'_, S>) {
        let metadata = event.metadata();
        let code = match *metadata.level() {
            tracing::Level::ERROR => "core_error",
            tracing::Level::WARN => "core_warn",
            tracing::Level::INFO => "core_info",
            tracing::Level::DEBUG => "core_debug",
            _ => return,
        };
        record(metadata.level().as_str(), module(metadata.target()), code);
    }
}

pub fn initialize() {
    INIT.call_once(|| {
        let _ =
            tracing::subscriber::set_global_default(tracing_subscriber::registry().with(SafeLayer));
    });
}

pub fn drain() -> serde_json::Value {
    let records: Vec<Record> = RECORDS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .drain(..)
        .collect();
    serde_json::json!({"records": records, "dropped": DROPPED.load(Ordering::Relaxed), "capacity": CAPACITY,
        "captured_elapsed_millis": START.elapsed().as_millis().min(i64::MAX as u128) as u64})
}

#[cfg(test)]
mod tests {
    use super::*;
    static TEST_LOCK: Mutex<()> = Mutex::new(());
    /// 连续写入超过容量时保留上限并登记丢弃，不增长到无界日志队列。
    #[test]
    fn diagnostic_buffer_is_bounded() {
        let _exclusive = TEST_LOCK.lock().unwrap();
        for _ in 0..CAPACITY + 10 {
            record("WARN", "synthetic_target", "synthetic_event");
        }
        let result = drain();
        assert_eq!(result["records"].as_array().unwrap().len(), CAPACITY);
        assert!(result["dropped"].as_u64().unwrap() >= 10);
    }

    /// 上游事件携带合成秘密时只记录静态分类；debug 不采集，原始字段和消息均不输出。
    #[test]
    fn upstream_fields_and_messages_are_not_exported() {
        let _exclusive = TEST_LOCK.lock().unwrap();
        let _ = drain();
        tracing::subscriber::with_default(tracing_subscriber::registry().with(SafeLayer), || {
            tracing::info!(
                network_secret = "synthetic-private-field",
                "synthetic-private-message"
            );
            tracing::warn!("warning-message-omitted");
            tracing::debug!("debug-not-collected");
        });
        let records = drain();
        assert_eq!(records["records"].as_array().unwrap().len(), 2);
        let output = records.to_string();
        assert!(!output.contains("synthetic-private-field"));
        assert!(!output.contains("synthetic-private-message"));
        assert!(!output.contains("debug-not-collected"));
    }

    /// 显式开启才采集 Debug；模块与 code 使用白名单，事件字段和原始消息仍不会进入输出。
    #[test]
    fn debug_is_explicit_and_keeps_static_safe_metadata() {
        let _exclusive = TEST_LOCK.lock().unwrap();
        let _ = drain();
        set_debug(true);
        tracing::subscriber::with_default(tracing_subscriber::registry().with(SafeLayer), || {
            tracing::debug!(target: "easytier::peers::peer_manager", secret = "synthetic-secret", "private message");
        });
        set_debug(false);
        let result = drain();
        let records = result["records"].as_array().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0]["target"], "peer");
        assert_eq!(records[0]["code"], "core_debug");
        assert!(!result.to_string().contains("synthetic-secret"));
        assert!(!result.to_string().contains("private message"));
    }

    /// 不同结构化失败映射到不同 code；合成地址、凭据和错误原文不进入诊断，路由只保留数量。
    #[test]
    fn typed_events_have_safe_specific_codes() {
        let _exclusive = TEST_LOCK.lock().unwrap();
        let _ = drain();
        core_event(GlobalCtxEvent::ConnectError(
            "synthetic-secret-url".into(),
            "synthetic-private-ip".into(),
            "synthetic-private-error".into(),
        ));
        core_event(GlobalCtxEvent::ListenerAddFailed(
            "tcp://synthetic.invalid:11010".parse().unwrap(),
            "synthetic-password".into(),
        ));
        core_event(GlobalCtxEvent::DhcpIpv4Conflicted(None));
        core_event(GlobalCtxEvent::ProxyCidrsUpdated(vec![], vec![]));
        let output = drain();
        let records = output["records"].as_array().unwrap();
        assert_eq!(
            records
                .iter()
                .map(|r| r["code"].as_str().unwrap())
                .collect::<Vec<_>>(),
            [
                "peer_connect_failed",
                "listener_add_failed",
                "ipv4_conflicted",
                "proxy_routes_changed"
            ]
        );
        assert_eq!(records[3]["fields"]["count"], "0");
        assert!(!output.to_string().contains("synthetic"));
    }

    /// 真实 broadcast 订阅接收事件后停止，receiver 数回到零；缓冲滞后被计入丢弃数。
    #[test]
    fn subscription_stops_and_accounts_for_lag() {
        let _exclusive = TEST_LOCK.lock().unwrap();
        let _ = drain();
        let before = DROPPED.load(Ordering::Relaxed);
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let (sender, receiver) = tokio::sync::broadcast::channel(2);
            for _ in 0..4 {
                sender.send(GlobalCtxEvent::PeerAdded(2)).unwrap();
            }
            let subscription = Subscription::new(receiver);
            tokio::task::yield_now().await;
            subscription.stop().await;
            assert_eq!(sender.receiver_count(), 0);
        });
        let output = drain();
        assert!(
            output["records"]
                .as_array()
                .unwrap()
                .iter()
                .any(|r| r["code"] == "peer_added")
        );
        assert!(DROPPED.load(Ordering::Relaxed) >= before + 2);
    }
}
