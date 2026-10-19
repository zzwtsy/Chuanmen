//! 有界诊断只保留静态元数据；上游日志字段可能含配置，禁止读取或格式化。
use serde::Serialize;
use std::collections::VecDeque;
use std::sync::{
    LazyLock, Mutex, Once,
    atomic::{AtomicU64, Ordering},
};
use std::time::Instant;
use tracing::{Event, Subscriber};
use tracing_subscriber::{Layer, layer::Context, prelude::*};

const CAPACITY: usize = 128;
static START: LazyLock<Instant> = LazyLock::new(Instant::now);
static RECORDS: Mutex<VecDeque<Record>> = Mutex::new(VecDeque::new());
static DROPPED: AtomicU64 = AtomicU64::new(0);
static INIT: Once = Once::new();

#[derive(Serialize)]
pub struct Record {
    elapsed_millis: u64,
    scope: &'static str,
    level: &'static str,
    target: String,
    code: &'static str,
}

fn record(level: &'static str, target: &str, code: &'static str) {
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
        target: target.chars().take(160).collect(),
        code,
    });
}

struct SafeLayer;
impl<S: Subscriber> Layer<S> for SafeLayer {
    fn on_event(&self, event: &Event<'_>, _context: Context<'_, S>) {
        let metadata = event.metadata();
        if matches!(
            *metadata.level(),
            tracing::Level::ERROR | tracing::Level::WARN | tracing::Level::INFO
        ) {
            record(
                metadata.level().as_str(),
                metadata.target(),
                "core_event_fields_omitted",
            );
        }
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
    serde_json::json!({"records": records, "dropped": DROPPED.load(Ordering::Relaxed), "capacity": CAPACITY})
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
}
