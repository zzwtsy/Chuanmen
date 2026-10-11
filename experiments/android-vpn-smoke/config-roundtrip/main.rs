//! P00-03：直接调用固定核心的公开 Rust 配置类型，不启动网络，不冒充 Android SDK。
use easytier_core::config::toml::{ConfigLoader, TomlConfig};
use serde_json::{Value, json};
use std::io::{self, BufRead};

fn effective(c: &TomlConfig) -> Value {
    let f = c.get_flags();
    // protobuf JSON 会省略 false；逐个读取实际 Rust 字段，避免将缺键误写成未知默认值。
    let flags = json!({
        "enable_ipv6":f.enable_ipv6, "accept_dns":f.accept_dns,
        "disable_p2p":f.disable_p2p, "p2p_only":f.p2p_only,
        "lazy_p2p":f.lazy_p2p, "need_p2p":f.need_p2p,
        "disable_udp_hole_punching":f.disable_udp_hole_punching,
        "disable_tcp_hole_punching":f.disable_tcp_hole_punching,
        "disable_sym_hole_punching":f.disable_sym_hole_punching, "disable_upnp":f.disable_upnp,
        "enable_exit_node":f.enable_exit_node, "enable_encryption":f.enable_encryption,
        "private_mode":f.private_mode, "latency_first":f.latency_first,
        "mtu":f.mtu, "instance_recv_bps_limit":f.instance_recv_bps_limit,
        "relay_network_whitelist":f.relay_network_whitelist, "relay_all_peer_rpc":f.relay_all_peer_rpc,
        "enable_kcp_proxy":f.enable_kcp_proxy, "disable_kcp_input":f.disable_kcp_input,
        "enable_quic_proxy":f.enable_quic_proxy, "disable_quic_input":f.disable_quic_input,
        "bind_device":f.bind_device, "dev_name":f.dev_name,
        "enable_udp_broadcast_relay":f.enable_udp_broadcast_relay,
        "multi_thread":f.multi_thread, "no_tun":f.no_tun,
        "proxy_forward_by_system":f.proxy_forward_by_system, "use_smoltcp":f.use_smoltcp
    });
    json!({
        "hostname": c.get_hostname(), "instance_name": c.get_inst_name(),
        "network_identity": c.get_network_identity(), "dhcp": c.get_dhcp(),
        "ipv4": c.get_ipv4().map(|v| v.to_string()),
        "ipv6_public_addr_auto": c.get_ipv6_public_addr_auto(),
        "peer": c.get_peers(), "listeners": c.get_listeners(),
        "mapped_listeners": c.get_mapped_listeners(), "routes": c.get_routes(),
        "exit_nodes": c.get_exit_nodes(), "proxy_network": c.get_proxy_cidrs(),
        "socks5_proxy": c.get_socks5_portal(), "port_forward": c.get_port_forwards(),
        "vpn_portal_config": c.get_vpn_portal_config(), "acl": c.get_acl(),
        "flags": flags, "flags_patch": c.get_flags_patch()
    })
}

fn main() {
    // 每行一个合成配置：一次解析、转储和再次解析，保留实际错误与读回值。
    for line in io::stdin().lock().lines() {
        let input: Value = serde_json::from_str(&line.unwrap()).unwrap();
        let output = match TomlConfig::new_from_str(input["toml"].as_str().unwrap()) {
            Ok(config) => {
                let first = effective(&config);
                let dump = config.dump();
                match TomlConfig::new_from_str(&dump) {
                    Ok(restored) => {
                        let second = effective(&restored);
                        json!({"name": input["name"], "accepted": true,
                            "effective": first, "dump": dump, "restored": second,
                            "roundtrip_equal": first == second})
                    }
                    Err(error) => json!({"name":input["name"], "accepted":true,
                        "roundtrip_equal":false,"restore_error":error.to_string()})
                }
            }
            Err(error) => json!({"name":input["name"], "accepted":false,"error":error.to_string()}),
        };
        println!("{}", output);
    }
}
