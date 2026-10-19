//! 固定核心的受控对端，DNS 绑定用户态虚拟端口；不建立宿主 TUN 或系统路由。
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
    io::{AsyncReadExt, AsyncWriteExt},
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
        let socket = instance
            .data_plane_udp_bind(53, Duration::from_secs(10))
            .await?;
        fs::write(
            out.join("dns-ready.json"),
            serde_json::to_vec_pretty(
                &json!({"run_id":run,"virtual_port":53,"host_tun":false,"system_routes_modified":false}),
            )?,
        )?;
        let mut records = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(out.join("dns.jsonl"))?;
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
