//! P00 自有实验观察点；不是上游 SDK，不采集报文内容或凭据。
use std::{collections::BTreeMap, sync::{Mutex, LazyLock}};
use serde_json::{json, Value};
pub use async_trait::async_trait;

#[derive(Default)]
struct Observations {
    tun: BTreeMap<i32, String>,
    read_packets: u64,
    write_packets: u64,
    destinations: BTreeMap<String, u64>,
    protections: Vec<Value>,
}
static OBS: LazyLock<Mutex<Observations>> = LazyLock::new(|| Mutex::new(Observations::default()));

pub fn reset() { *OBS.lock().unwrap() = Observations::default(); }
pub fn tun_state(fd: i32, phase: &str) { OBS.lock().unwrap().tun.insert(fd, phase.to_owned()); }
pub fn tun_read(packet: &[u8]) {
    let mut obs = OBS.lock().unwrap();
    obs.read_packets += 1;
    if packet.len() >= 20 && packet[0] >> 4 == 4 {
        let dst = std::net::Ipv4Addr::new(packet[16], packet[17], packet[18], packet[19]);
        *obs.destinations.entry(dst.to_string()).or_default() += 1;
    }
}
pub fn tun_write() { OBS.lock().unwrap().write_packets += 1; }
pub fn protection(fd: i32, before_port: Option<u16>, success: bool) {
    let mut obs = OBS.lock().unwrap();
    // 上限约束只截断诊断样本；计数和实验断言不会伪造成功。
    if obs.protections.len() < 512 {
        obs.protections.push(json!({"fd": fd, "before_port": before_port, "success": success,
            "native_thread": format!("{:?}", std::thread::current().id())}));
    }
}
pub fn snapshot() -> String {
    let obs = OBS.lock().unwrap();
    json!({"tun": obs.tun, "read_packets": obs.read_packets, "write_packets": obs.write_packets,
        "destinations": obs.destinations, "protections": obs.protections}).to_string()
}
pub fn socket_port(fd: i32) -> Option<u16> {
    use std::os::fd::BorrowedFd;
    let borrowed = unsafe { BorrowedFd::borrow_raw(fd) };
    socket2::SockRef::from(&borrowed).local_addr().ok().and_then(|a| a.as_socket()).map(|a| a.port())
}
pub fn socket_probe_sync() -> String {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(socket_probe())
}

/// 用真实核心 TCP/UDP 创建路径检查 protector；仅使用本机未绑定 socket，不连接外部。
pub async fn socket_probe() -> String {
    use easytier_core::socket::{tcp::TcpBindOptions, udp::UdpBindOptions};
    let local = "127.0.0.1:0".parse().unwrap();
    let tcp = crate::socket::tcp::create_tcp_socket(local, &TcpBindOptions::default()
        .with_local_addr(Some(local))).await;
    let udp = crate::socket::udp::create_udp_socket(&UdpBindOptions::direct_connect()
        .with_local_addr(Some(local))).await;
    json!({"tcp_created": tcp.is_ok(), "udp_created": udp.is_ok(),
        "tcp_error": tcp.as_ref().err().map(|e| e.to_string()),
        "udp_error": udp.as_ref().err().map(|e| e.to_string())}).to_string()
}
