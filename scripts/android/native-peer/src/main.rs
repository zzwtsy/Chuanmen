//! 固定核心的受控对端，回显和 DNS 均绑定用户态虚拟端口；不建立宿主 TUN 或系统路由。
mod dns;
use easytier::{
    common::config::{ConfigLoader, TomlConfigLoader},
    instance::factory::create_native_instance,
};
use serde_json::json;
use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    net::SocketAddr,
    path::PathBuf,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    net::TcpListener,
};

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let mut config_path = None;
    let mut output = None;
    let mut run_id = None;
    let mut check_only = false;
    let mut health_bind = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--config-file" => config_path = args.next().map(PathBuf::from),
            "--out" => output = args.next().map(PathBuf::from),
            "--run-id" => run_id = args.next(),
            "--check-config" => check_only = true,
            "--health-bind" => {
                health_bind = args
                    .next()
                    .and_then(|value| value.parse::<SocketAddr>().ok())
            }
            _ => return Err("unsupported controlled peer argument".into()),
        }
    }
    let config =
        TomlConfigLoader::new_from_str(&fs::read_to_string(config_path.ok_or("config missing")?)?)?;
    let flags = config.get_flags();
    if !flags.no_tun || !flags.use_smoltcp || flags.enable_ipv6 || !flags.disable_upnp {
        return Err(
            "controlled peer requires no TUN, userspace packets and bounded platform scope".into(),
        );
    }
    if check_only {
        return Ok(());
    }
    let out = output.ok_or("output missing")?;
    let bind = health_bind
        .filter(|address| {
            matches!(address.ip(), std::net::IpAddr::V4(ip) if ip.is_private())
                && address.port() == 31274
        })
        .ok_or("private controlled status address missing")?;
    let run = run_id
        .filter(|value| {
            value.starts_with("p02-")
                && value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
        .ok_or("run identity missing")?;
    let instance = create_native_instance(config)?;
    instance.start().await?;
    let mut termination =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    let listener = TcpListener::bind(bind).await?;
    let health = async {
        let mut records = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(out.join("peer-status.jsonl"))?;
        loop {
            let (mut client, _) = listener.accept().await?;
            let mut request = Vec::new();
            if !matches!(
                tokio::time::timeout(
                    Duration::from_secs(2),
                    (&mut client).take(128).read_to_end(&mut request)
                )
                .await,
                Ok(Ok(_))
            ) || request != format!("{run}\n").as_bytes()
            {
                continue;
            }
            let peers = instance.peer_snapshots().await;
            let routes = instance.route_snapshots().await;
            let response = json!({"run_id":run,"active_connections":peers.iter().map(|peer| peer.conns.len()).sum::<usize>(),"peers":peers.iter().map(|peer|json!({"peer_id":peer.peer_id,"connections":peer.conns.len()})).collect::<Vec<_>>()});
            let mut response = response;
            response["reachable_routes"] = json!(
                routes
                    .iter()
                    .filter(|route| route.peer_id != instance.peer_id())
                    .count()
            );
            let bytes = format!("{response}\n");
            let _ =
                tokio::time::timeout(Duration::from_secs(2), client.write_all(bytes.as_bytes()))
                    .await;
            records.write_all(bytes.as_bytes())?;
            records.flush()?;
        }
        #[allow(unreachable_code)]
        Ok::<(), Box<dyn std::error::Error>>(())
    };
    let serving = async {
        // 原始宿主代理与显式数据面端口不共用接收路径，统一使用虚拟端口避免混装两种对端。
        let mut tcp = instance
            .data_plane_tcp_bind(31271, Duration::from_secs(10))
            .await?;
        let udp = instance
            .data_plane_udp_bind(31272, Duration::from_secs(10))
            .await?;
        let socket = instance
            .data_plane_udp_bind(53, Duration::from_secs(10))
            .await?;
        let mut tcp_records = OpenOptions::new()
            .create_new(true)
            .append(true)
            .open(out.join("echo.jsonl"))?;
        let mut udp_records = tcp_records.try_clone()?;
        fs::write(
            out.join("dns-ready.json"),
            serde_json::to_vec_pretty(
                &json!({"run_id":run,"virtual_port":53,"host_tun":false,"system_routes_modified":false}),
            )?,
        )?;
        let tcp_echo = async {
            loop {
                let (mut client, _) = tcp.accept().await?;
                // 单个异常连接最多占用三秒，读取上限也包含越界判据所需的额外一个字节。
                let request = async {
                    let mut payload = Vec::new();
                    BufReader::new(&mut client)
                        .take(4097)
                        .read_until(b'\n', &mut payload)
                        .await?;
                    if valid_echo(&payload, &run) {
                        client.write_all(&payload).await?;
                        // 用户态 TCP 写入仅入队；排空后再记录并释放，避免移除 socket 丢掉回包。
                        client.flush().await?;
                        record_echo(&mut tcp_records, "tcp", &payload)?;
                    }
                    Ok::<(), std::io::Error>(())
                };
                let _ = tokio::time::timeout(Duration::from_secs(3), request).await;
            }
            #[allow(unreachable_code)]
            Ok::<(), Box<dyn std::error::Error>>(())
        };
        let udp_echo = async {
            let mut buffer = [0u8; 4097];
            loop {
                let (length, sender) = udp.recv_from(&mut buffer).await?;
                let payload = &buffer[..length];
                if valid_echo(payload, &run) {
                    udp.send_to(payload, sender).await?;
                    record_echo(&mut udp_records, "udp", payload)?;
                }
            }
            #[allow(unreachable_code)]
            Ok::<(), Box<dyn std::error::Error>>(())
        };
        let mut records = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(out.join("dns.jsonl"))?;
        let dns = async {
            let mut buffer = [0u8; 4096];
            loop {
                let (length, sender) = socket.recv_from(&mut buffer).await?;
                if let Some(answer) = dns::answer(&buffer[..length], &run) {
                    let sent = socket.send_to(&answer.response, sender).await?;
                    writeln!(
                        records,
                        "{}",
                        json!({"query_name_sha256":answer.name_sha256,"transaction":answer.transaction,"query_type":answer.query_type,"query_bytes":length,"response_bytes":sent,"answer_ipv4":if answer.query_type==1 {Some("10.222.0.1")} else {None}})
                    )?;
                    records.flush()?;
                }
            }
            #[allow(unreachable_code)]
            Ok::<(), Box<dyn std::error::Error>>(())
        };
        tokio::select! {
            result = tcp_echo => result,
            result = udp_echo => result,
            result = dns => result,
        }
    };
    let result = tokio::select! {
        result = serving => result,
        result = health => result,
        _ = termination.recv() => Ok(()),
        _ = tokio::signal::ctrl_c() => Ok(()),
    };
    instance.stop().await;
    fs::write(
        out.join("dns-stopped.json"),
        serde_json::to_vec_pretty(
            &json!({"run_id":run,"state":format!("{:?}",instance.state()),"dns_socket_dropped":true}),
        )?,
    )?;
    result
}

fn valid_echo(payload: &[u8], run: &str) -> bool {
    payload.len() <= 4096 && payload.starts_with(format!("{run}:").as_bytes())
}

fn record_echo(records: &mut std::fs::File, protocol: &str, payload: &[u8]) -> std::io::Result<()> {
    use sha2::{Digest, Sha256};
    writeln!(
        records,
        "{}",
        json!({"protocol":protocol,"request_sha256":format!("{:x}",Sha256::digest(payload)),"bytes":payload.len()})
    )?;
    records.flush()
}

#[cfg(test)]
mod tests {
    use super::valid_echo;

    /// 本轮合成载荷可以回显，其他轮次、缺少分隔符和超长请求必须被拒绝。
    #[test]
    fn echo_requires_bounded_current_run_payload() {
        assert!(valid_echo(b"p02-current:tcp:nonce\n", "p02-current"));
        assert!(!valid_echo(b"p02-other:tcp:nonce\n", "p02-current"));
        assert!(!valid_echo(b"p02-current-extra:nonce\n", "p02-current"));
        let mut oversized = b"p02-current:".to_vec();
        oversized.resize(4097, b'x');
        assert!(!valid_echo(&oversized, "p02-current"));
    }
}
