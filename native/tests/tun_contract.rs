//! 宿主测试使用正式 overlay 导出的类型；不把队列测试当成设备 TUN 安装验收。
use easytier::android_tun::{TunAttachment, TunError, TunIdentity, TunPhase, TunRequest};
use std::sync::Arc;

fn attachment(sequence: u64) -> Arc<TunAttachment> {
    TunAttachment::new(TunIdentity {
        session_id: "synthetic-session".into(),
        generation: 1,
        operation: 1,
        attachment: sequence,
    })
}

/// 入队后无安装确认，排空待安装队列时每个请求收到取消且借用结束。
#[tokio::test]
async fn queued_fds_are_cancelled_before_borrow() {
    let (sender, mut receiver) = tokio::sync::mpsc::channel(2);
    let first = attachment(1);
    let second = attachment(2);
    let (one, mut one_receipt) = TunRequest::new(Some(0), Some(first.clone()));
    let (two, two_receipt) = TunRequest::new(Some(7), Some(second.clone()));
    assert!(sender.try_send(one).is_ok());
    assert!(sender.try_send(two).is_ok());
    assert_eq!(first.phase(), TunPhase::Pending);
    assert!(one_receipt.try_recv().is_err());
    receiver.close();
    while let Ok(request) = receiver.try_recv() {
        request.cancel();
    }
    assert_eq!(one_receipt.await.unwrap(), Err(TunError::Cancelled));
    assert_eq!(two_receipt.await.unwrap(), Err(TunError::Cancelled));
    assert_eq!(first.phase(), TunPhase::Released);
    assert_eq!(second.phase(), TunPhase::Released);
}

/// fd 复用时旧身份计数和释放不会修改新 attachment；成功 I/O 保持精确整数。
#[test]
fn reused_fd_does_not_reuse_attachment_state() {
    let old = attachment(1);
    let next = attachment(2);
    old.installed();
    next.installed();
    old.read_completed(9_007_199_254_740_993);
    old.write_completed(64);
    old.released();
    assert_eq!(old.counters().tx_bytes, 9_007_199_254_740_993);
    assert_eq!(old.counters().rx_bytes, 64);
    assert_eq!(next.counters().tx_bytes, 0);
    assert_eq!(next.phase(), TunPhase::Installed);
}
