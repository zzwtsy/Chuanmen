//! P02 的 TUN 借用与安装回执；状态绑定 attachment，不以可复用 fd 作为身份。

use serde::{Deserialize, Serialize};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};
use tokio::sync::oneshot;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct TunIdentity {
    pub session_id: String,
    pub generation: u64,
    pub operation: u64,
    pub attachment: u64,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TunPhase {
    Pending,
    Installed,
    Released,
    Failed,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TunError {
    QueueClosed,
    Cancelled,
    InstallFailed,
}

#[derive(Debug, Serialize)]
pub struct TunCounterSnapshot {
    pub tx_bytes: u64,
    pub rx_bytes: u64,
    pub tx_packets: u64,
    pub rx_packets: u64,
}

pub struct TunAttachment {
    pub identity: TunIdentity,
    phase: Mutex<TunPhase>,
    tx_bytes: AtomicU64,
    rx_bytes: AtomicU64,
    tx_packets: AtomicU64,
    rx_packets: AtomicU64,
}

impl TunAttachment {
    pub fn new(identity: TunIdentity) -> Arc<Self> {
        Arc::new(Self {
            identity,
            phase: Mutex::new(TunPhase::Pending),
            tx_bytes: AtomicU64::new(0),
            rx_bytes: AtomicU64::new(0),
            tx_packets: AtomicU64::new(0),
            rx_packets: AtomicU64::new(0),
        })
    }

    pub fn phase(&self) -> TunPhase {
        *self
            .phase
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    pub fn installed(&self) {
        let mut phase = self
            .phase
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if *phase == TunPhase::Pending {
            *phase = TunPhase::Installed;
        }
    }

    /// 只在排空 I/O 或从待安装队列取消、尚未开始借用时调用。
    pub fn released(&self) {
        *self
            .phase
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = TunPhase::Released;
    }

    pub fn failed(&self) {
        *self
            .phase
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = TunPhase::Failed;
    }

    fn add(counter: &AtomicU64, amount: u64) {
        // 协议使用 Kotlin Long 范围；饱和计数不回绕成负数或小值。
        let _ = counter.try_update(Ordering::Relaxed, Ordering::Relaxed, |value| {
            Some(value.saturating_add(amount).min(i64::MAX as u64))
        });
    }

    pub fn read_completed(&self, bytes: usize) {
        if bytes > 0 {
            Self::add(&self.tx_bytes, bytes as u64);
            Self::add(&self.tx_packets, 1);
        }
    }

    pub fn write_completed(&self, bytes: usize) {
        if bytes > 0 {
            Self::add(&self.rx_bytes, bytes as u64);
            Self::add(&self.rx_packets, 1);
        }
    }

    pub fn counters(&self) -> TunCounterSnapshot {
        TunCounterSnapshot {
            tx_bytes: self.tx_bytes.load(Ordering::Relaxed),
            rx_bytes: self.rx_bytes.load(Ordering::Relaxed),
            tx_packets: self.tx_packets.load(Ordering::Relaxed),
            rx_packets: self.rx_packets.load(Ordering::Relaxed),
        }
    }
}

pub struct TunRequest {
    pub fd: Option<i32>,
    pub attachment: Option<Arc<TunAttachment>>,
    pub receipt: oneshot::Sender<Result<(), TunError>>,
}

impl TunRequest {
    pub fn new(
        fd: Option<i32>,
        attachment: Option<Arc<TunAttachment>>,
    ) -> (Self, oneshot::Receiver<Result<(), TunError>>) {
        let (receipt, receiver) = oneshot::channel();
        (
            Self {
                fd,
                attachment,
                receipt,
            },
            receiver,
        )
    }

    pub fn cancel(self) {
        if let Some(attachment) = self.attachment {
            attachment.released();
        }
        let _ = self.receipt.send(Err(TunError::Cancelled));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn attachment(sequence: u64) -> Arc<TunAttachment> {
        TunAttachment::new(TunIdentity {
            session_id: "test-session".into(),
            generation: 1,
            operation: 1,
            attachment: sequence,
        })
    }

    /// 入队后尚无安装回执，取消待安装请求只释放自己的身份并返回取消结果。
    #[tokio::test]
    async fn queued_request_waits_for_install_or_cancel() {
        let attachment = attachment(1);
        let (request, mut receipt) = TunRequest::new(Some(5), Some(attachment.clone()));
        let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
        sender
            .try_send(request)
            .unwrap_or_else(|_| panic!("fixture queue failed"));
        assert_eq!(attachment.phase(), TunPhase::Pending);
        assert!(matches!(
            receipt.try_recv(),
            Err(oneshot::error::TryRecvError::Empty)
        ));
        receiver.recv().await.unwrap().cancel();
        assert_eq!(receipt.await.unwrap(), Err(TunError::Cancelled));
        assert_eq!(attachment.phase(), TunPhase::Released);
    }

    /// 同一整数 fd 被下一次借用复用时，旧计数和释放不影响新 attachment。
    #[test]
    fn counters_and_release_are_scoped_to_attachment() {
        let old = attachment(1);
        let next = attachment(2);
        old.installed();
        next.installed();
        old.read_completed(128);
        old.write_completed(64);
        old.released();
        assert_eq!(old.counters().tx_bytes, 128);
        assert_eq!(old.counters().rx_bytes, 64);
        assert_eq!(next.counters().tx_bytes, 0);
        assert_eq!(next.phase(), TunPhase::Installed);
    }

    /// 超过浮点精确整数范围的计数保持原值；超过协议整数范围后饱和而不回绕。
    #[test]
    fn counters_preserve_precision_and_saturate() {
        let attachment = attachment(1);
        TunAttachment::add(&attachment.tx_bytes, 9_007_199_254_740_993);
        assert_eq!(attachment.counters().tx_bytes, 9_007_199_254_740_993);
        TunAttachment::add(&attachment.tx_bytes, u64::MAX);
        assert_eq!(attachment.counters().tx_bytes, i64::MAX as u64);
    }
}
