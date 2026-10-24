//! 配置转换不访问会话、TUN 或 runtime；原文和语义投影分别保留，错误只带安全位置与分类。
use easytier::common::config::{ConfigLoader, TomlConfigLoader};
use serde_json::{Value, json};

pub const MAX_DOCUMENT_BYTES: usize = 128 * 1024;
const EDITABLE: &[&str] = &[
    "hostname",
    "dhcp",
    "ipv4",
    "peer",
    "network_identity.network_name",
    "network_identity.network_secret",
    "flags.mtu",
];

pub struct CodecError {
    pub code: &'static str,
    pub key: Option<String>,
    pub offset: Option<usize>,
}

fn error(code: &'static str) -> CodecError {
    CodecError {
        code,
        key: None,
        offset: None,
    }
}

fn parse(raw: &str) -> Result<Value, CodecError> {
    if raw.len() > MAX_DOCUMENT_BYTES {
        return Err(error("document_too_large"));
    }
    // serde_json::Value 支持 u64，不先经过仅支持 i64 的 toml::Value。
    let value: Value = toml::from_str(raw).map_err(|failure| CodecError {
        code: "toml_syntax",
        key: None,
        offset: failure.span().map(|span| span.start),
    })?;
    let loader = supported_loader(&value)?;
    if lookup(&value, "ipv4").is_some() && loader.get_ipv4().is_none() {
        return Err(CodecError {
            code: "invalid_config",
            key: Some("ipv4".into()),
            offset: None,
        });
    }
    if let Some(mtu) = lookup(&value, "flags.mtu") {
        if !mtu.as_u64().is_some_and(|mtu| (576..=9000).contains(&mtu)) {
            return Err(CodecError {
                code: "invalid_config",
                key: Some("flags.mtu".into()),
                offset: None,
            });
        }
    }
    Ok(value)
}

fn supported_loader(value: &Value) -> Result<TomlConfigLoader, CodecError> {
    // 固定核心会拒绝部分未知 flags。文档允许保留这些字段，但运行入口仍显式拒绝它们。
    // 校验只读已接入字段，不能为通过核心解析而改写原始文档或把未知参数偷偷运行。
    let mut supported = json!({});
    for key in EDITABLE.iter().copied().chain([
        "instance_id",
        "instance_name",
        "listeners",
        "flags.no_tun",
        "flags.enable_ipv6",
    ]) {
        if let Some(value) = lookup(&value, key) {
            let mut value = value.clone();
            if key == "peer" {
                if let Some(peers) = value.as_array_mut() {
                    for peer in peers {
                        if let Some(fields) = peer.as_object_mut() {
                            fields.retain(|key, _| key == "uri");
                        }
                    }
                }
            }
            patch(&mut supported, key, value)?;
        }
    }
    let supported_raw = toml::to_string(&supported).map_err(|_| error("encoding_failed"))?;
    TomlConfigLoader::new_from_str(&supported_raw).map_err(|_| error("invalid_config"))
}

fn encode(value: &Value) -> Result<String, CodecError> {
    let raw = toml::to_string(value).map_err(|_| error("encoding_failed"))?;
    parse(&raw)?;
    Ok(raw)
}

fn lookup<'a>(root: &'a Value, path: &str) -> Option<&'a Value> {
    path.split('.').try_fold(root, |value, key| value.get(key))
}

fn patch(root: &mut Value, path: &str, value: Value) -> Result<(), CodecError> {
    let mut keys = path.split('.').peekable();
    let mut current = root;
    while let Some(key) = keys.next() {
        let table = current.as_object_mut().ok_or_else(|| error("field_type"))?;
        if keys.peek().is_none() {
            if value.is_null() {
                table.remove(key);
            } else {
                table.insert(key.into(), value);
            }
            return Ok(());
        }
        current = table.entry(key).or_insert_with(|| json!({}));
    }
    Err(error("field_type"))
}

// 与 Android PeerEndpoint 的首批范围一致；未知 URL 可保存在文档中，但不能透传运行。
fn supported_peer_uri(uri: &str) -> bool {
    if !(uri.starts_with("tcp://") || uri.starts_with("udp://")) {
        return false;
    }
    let Ok(raw) = toml::to_string(&json!({"peer": [{"uri": uri}]})) else {
        return false;
    };
    let Ok(loader) = TomlConfigLoader::new_from_str(&raw) else {
        return false;
    };
    loader.get_peers().first().is_some_and(|peer| {
        let url = &peer.uri;
        url.host_str().is_some_and(|host| !host.is_empty())
            && url.port().is_some_and(|port| port > 0)
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
            && url.path().is_empty()
            && !uri
                .split("://")
                .nth(1)
                .is_some_and(|authority| authority.contains('@'))
    })
}

fn unsupported(root: &Value) -> Vec<String> {
    fn walk(value: &Value, prefix: &str, result: &mut Vec<String>) {
        if let Some(table) = value.as_object() {
            for (key, item) in table {
                let path = if prefix.is_empty() {
                    key.clone()
                } else {
                    format!("{prefix}.{key}")
                };
                match path.as_str() {
                    "instance_id"
                    | "instance_name"
                    | "hostname"
                    | "dhcp"
                    | "ipv4"
                    | "network_identity.network_name"
                    | "network_identity.network_secret"
                    | "flags.mtu"
                    | "flags.no_tun"
                    | "flags.enable_ipv6" => {}
                    "listeners" if item.is_array() => {}
                    "peer" if item.is_array() => {
                        for peer in item.as_array().unwrap() {
                            if let Some(fields) = peer.as_object() {
                                if !fields
                                    .get("uri")
                                    .and_then(Value::as_str)
                                    .is_some_and(supported_peer_uri)
                                {
                                    result.push("peer.protocol".into());
                                }
                                if fields.keys().any(|field| field != "uri") {
                                    result.push("peer.members".into());
                                }
                            } else {
                                result.push("peer".into());
                            }
                        }
                    }
                    "network_identity" | "flags" => walk(item, &path, result),
                    _ => result.push(
                        if path.len() <= 96
                            && path
                                .chars()
                                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.')
                        {
                            path
                        } else {
                            "unknown_field".into()
                        },
                    ),
                }
            }
        }
    }
    let mut result = Vec::new();
    walk(root, "", &mut result);
    result.sort();
    result.dedup();
    result
}

// 数值统一以带类型的十进制字符串穿过 JNI，布尔、字符串和缺省不互相冒充。
fn projection(value: &Value) -> Value {
    match value {
        Value::Number(number) => json!({"type": "number", "decimal": number.to_string()}),
        Value::Array(values) => Value::Array(values.iter().map(projection).collect()),
        Value::Object(values) => Value::Object(
            values
                .iter()
                .map(|(key, value)| (key.clone(), projection(value)))
                .collect(),
        ),
        _ => value.clone(),
    }
}

// 命令引用基准数组中的唯一索引，结果顺序由条目顺序决定，不通过 URL 推断身份。
fn patch_peers(tree: &mut Value, entries: &Value) -> Result<(), CodecError> {
    let entries = entries.as_array().ok_or_else(|| error("invalid_patch"))?;
    let old = match tree.get("peer") {
        None => Vec::new(),
        Some(value) => value.as_array().ok_or_else(|| error("field_type"))?.clone(),
    };
    let mut used = vec![false; old.len()];
    let mut result = Vec::new();
    for entry in entries {
        let object = entry.as_object().ok_or_else(|| error("invalid_patch"))?;
        let kind = object
            .get("kind")
            .and_then(Value::as_str)
            .ok_or_else(|| error("invalid_patch"))?;
        let mut node = if kind == "keep" || kind == "replace_uri" {
            let index = object
                .get("source_index")
                .and_then(Value::as_u64)
                .and_then(|index| usize::try_from(index).ok())
                .ok_or_else(|| error("invalid_patch"))?;
            if index >= old.len() || used[index] {
                return Err(error("invalid_patch"));
            }
            used[index] = true;
            old[index].clone()
        } else if kind == "insert" {
            json!({})
        } else {
            return Err(error("invalid_patch"));
        };
        if kind != "keep" {
            let uri = object
                .get("uri")
                .and_then(Value::as_str)
                .ok_or_else(|| error("invalid_patch"))?;
            if !supported_peer_uri(uri) {
                return Err(error("invalid_config"));
            }
            node.as_object_mut()
                .ok_or_else(|| error("field_type"))?
                .insert("uri".into(), json!(uri));
        }
        let expected_keys = if kind == "replace_uri" { 3 } else { 2 };
        if object.len() != expected_keys {
            return Err(error("invalid_patch"));
        }
        result.push(node);
    }
    patch(tree, "peer", Value::Array(result))
}

pub fn execute(action: &str, original: &str, changes: Option<&Value>) -> Result<Value, CodecError> {
    let mut tree = parse(original)?;
    let mut raw = original.to_string();
    if action == "config_patch" {
        let changes = changes
            .and_then(Value::as_object)
            .ok_or_else(|| error("invalid_patch"))?;
        for (key, value) in changes {
            if key == "peer_edits" {
                patch_peers(&mut tree, value)?;
            } else {
                if key == "peer" {
                    return Err(error("invalid_patch"));
                }
                if !EDITABLE.contains(&key.as_str()) {
                    return Err(error("unsupported_field"));
                }
                patch(&mut tree, key, value.clone())?;
            }
        }
        raw = encode(&tree)?;
    }
    let unsupported = unsupported(&tree);
    let mut comparable = tree.clone();
    for key in [
        "instance_id",
        "instance_name",
        "listeners",
        "flags.no_tun",
        "flags.enable_ipv6",
    ] {
        patch(&mut comparable, key, Value::Null)?;
    }
    // 空 flags 表与缺省 flags 等价，托管参数的原文变化不产生待应用。
    if comparable
        .get("flags")
        .and_then(Value::as_object)
        .is_some_and(|table| table.is_empty())
    {
        comparable.as_object_mut().unwrap().remove("flags");
    }
    let runtime_semantic = if unsupported.is_empty() {
        let loader = supported_loader(&tree)?;
        let network = loader.get_network_identity();
        // 使用固定核心的有效值；源语义仍保留缺省、空值及原始类型。
        projection(
            &json!({"kind":"resolved", "network_name":network.network_name,
            "network_secret":network.network_secret, "hostname":loader.get_hostname(),
            "dhcp":loader.get_dhcp(), "ipv4":loader.get_ipv4().map(|ip| ip.to_string()),
            "mtu":loader.get_flags().mtu,
            "peers":loader.get_peers().iter().map(|peer| peer.uri.to_string()).collect::<Vec<_>>() }),
        )
    } else {
        json!({"kind":"blocked", "source":projection(&comparable)})
    };
    let mut overrides = Vec::new();
    if action == "config_runtime" {
        if !unsupported.is_empty() {
            return Ok(
                json!({"codec_version":2, "runnable": false, "unsupported": unsupported, "overrides": []}),
            );
        }
        for (key, value) in [
            ("listeners", json!([])),
            ("flags.enable_ipv6", json!(false)),
            ("flags.no_tun", json!(false)),
        ] {
            if lookup(&tree, key) != Some(&value) {
                overrides.push(key);
            }
            patch(&mut tree, key, value)?;
        }
        if let Some(identity) = changes.and_then(Value::as_object) {
            for (key, value) in identity {
                if !["instance_id", "instance_name"].contains(&key.as_str()) || !value.is_string() {
                    return Err(error("invalid_identity"));
                }
                if lookup(&tree, key) != Some(value) {
                    overrides.push(key);
                }
                patch(&mut tree, key, value.clone())?;
            }
        }
        raw = encode(&tree)?;
    }
    // semantic 用带类型的树比较，排版及注释变化不会制造新的运行修订。
    Ok(
        json!({"codec_version":2, "raw": raw, "projection": projection(&tree), "semantic": projection(&tree), "runtime_semantic": runtime_semantic,
        "unsupported": unsupported, "runnable": unsupported.is_empty(), "overrides": overrides}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const BASE: &str =
        "dhcp = true\n[network_identity]\nnetwork_name = '合成网络 🌏'\nnetwork_secret = ''\n";

    /// 缺省和空密码的源值分开保留；非 secure 模式下固定核心将两者解析为相同运行值。
    #[test]
    fn empty_and_missing_secret_are_distinct_without_runtime() {
        let before = easytier_ffi::android_host::runtime_threads();
        let creations = crate::session::creation_count();
        let explicit =
            execute("config_parse", BASE, None).unwrap_or_else(|_| panic!("parse failed"));
        let missing = execute(
            "config_parse",
            &BASE.replace("network_secret = ''\n", ""),
            None,
        )
        .unwrap_or_else(|_| panic!("parse failed"));
        assert_eq!(
            explicit["projection"]["network_identity"]["network_secret"],
            ""
        );
        assert!(
            missing["projection"]["network_identity"]
                .get("network_secret")
                .is_none()
        );
        assert_ne!(explicit["semantic"], missing["semantic"]);
        assert_eq!(explicit["runtime_semantic"], missing["runtime_semantic"]);
        assert_eq!(before, easytier_ffi::android_host::runtime_threads());
        assert_eq!(creations, crate::session::creation_count());
    }

    /// 修改主机名后未知字段、显式 false、空列表和 u64 上界语义保留，未知字段阻止运行。
    #[test]
    fn patch_keeps_unknown_unsigned_and_empty_values() {
        let raw = format!(
            "{BASE}[flags]\ninstance_recv_bps_limit = 18446744073709551615\nfuture_flag = false\nfuture_list = []\n"
        );
        let patched = execute("config_patch", &raw, Some(&json!({"hostname": "本机"})))
            .unwrap_or_else(|failure| {
                panic!("patch failed: {} {:?}", failure.code, failure.offset)
            });
        assert_eq!(
            patched["projection"]["flags"]["instance_recv_bps_limit"]["decimal"],
            "18446744073709551615"
        );
        assert_eq!(patched["projection"]["flags"]["future_flag"], false);
        assert_eq!(patched["projection"]["flags"]["future_list"], json!([]));
        let runtime = execute("config_runtime", patched["raw"].as_str().unwrap(), None)
            .unwrap_or_else(|_| panic!("runtime failed"));
        assert_eq!(runtime["runnable"], false);
        assert!(runtime.get("raw").is_none());
    }

    /// 平台覆盖仅作用于运行输出，原始文档保留；生成的 TOML 再经固定核心解析。
    #[test]
    fn runtime_overrides_leave_source_intact() {
        let raw = format!("{BASE}[flags]\nenable_ipv6 = true\nno_tun = true\nmtu = 1400\n");
        let runtime =
            execute("config_runtime", &raw, None).unwrap_or_else(|_| panic!("runtime failed"));
        assert_eq!(runtime["projection"]["flags"]["enable_ipv6"], false);
        assert_eq!(runtime["projection"]["flags"]["no_tun"], false);
        assert_eq!(
            execute("config_parse", &raw, None).unwrap_or_else(|_| panic!("parse failed"))["projection"]
                ["flags"]["enable_ipv6"],
            true
        );
        assert!(TomlConfigLoader::new_from_str(runtime["raw"].as_str().unwrap()).is_ok());
    }

    /// 非法 TOML 和超限输入只返回分类与位置，错误数据不保存合成秘密原文。
    #[test]
    fn invalid_and_oversized_documents_are_rejected() {
        let failure = execute("config_parse", "password = 'synthetic-secret", None)
            .err()
            .unwrap();
        assert_eq!(failure.code, "toml_syntax");
        assert!(failure.key.is_none());
        assert_eq!(
            execute("config_parse", &"x".repeat(MAX_DOCUMENT_BYTES + 1), None)
                .err()
                .unwrap()
                .code,
            "document_too_large"
        );
    }
    /// 首批黄金样例逐键修改并经固定核心解析；纯配置动作的会话创建计数保持为零。
    #[test]
    fn first_batch_golden_fields_parse_without_session_creation() {
        let cases: Value = serde_json::from_str(include_str!(
            "../../src/test/resources/profiles/p03-fields.json"
        ))
        .unwrap();
        let before = crate::session::creation_count();
        let mut checked = 0;
        for case in cases.as_array().unwrap() {
            let key = case["key"].as_str().unwrap();
            if key.starts_with("android.") {
                continue;
            }
            let mut changes = json!({});
            changes.as_object_mut().unwrap().insert(
                if key == "peer" {
                    "peer_edits".into()
                } else {
                    key.into()
                },
                if key == "peer" {
                    Value::Array(
                        case["value"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|node| json!({"kind":"insert", "uri":node["uri"]}))
                            .collect(),
                    )
                } else {
                    case["value"].clone()
                },
            );
            let patched = execute("config_patch", BASE, Some(&changes))
                .unwrap_or_else(|failure| panic!("golden field {key}: {}", failure.code));
            let raw = patched["raw"].as_str().unwrap();
            assert!(TomlConfigLoader::new_from_str(raw).is_ok());
            let tree = parse(raw).unwrap_or_else(|_| panic!("roundtrip failed"));
            assert_eq!(lookup(&tree, key), Some(&case["value"]));
            checked += 1;
        }
        assert_eq!(checked, 7);
        assert_eq!(before, crate::session::creation_count());
    }

    /// 不支持协议仍可解析保存且阻止运行；修改 URL 时保留尚未接入的同一节点成员。
    #[test]
    fn peer_members_and_unimplemented_protocol_are_preserved() {
        let raw =
            format!("{BASE}[[peer]]\nuri = 'quic://192.0.2.1:11010'\nfuture_member = false\n");
        let parsed = execute("config_parse", &raw, None).unwrap_or_else(|_| panic!("parse failed"));
        assert!(
            parsed["unsupported"]
                .as_array()
                .unwrap()
                .contains(&json!("peer.protocol"))
        );
        let patched = execute(
            "config_patch",
            &raw,
            Some(&json!({"peer_edits": [{"kind":"replace_uri", "source_index":0, "uri": "tcp://192.0.2.2:11010"}]})),
        )
        .unwrap_or_else(|_| panic!("patch failed"));
        assert_eq!(patched["projection"]["peer"][0]["future_member"], false);
        assert!(!patched["runnable"].as_bool().unwrap());
    }

    /// 未知日期、未知表、缺省和显式空值在修改主机名后保持类型及值，不能降为普通字符串。
    #[test]
    fn patch_preserves_unknown_datetime_type() {
        let raw = format!("{BASE}[future]\nwhen = 2026-10-23T12:00:00Z\nempty = ''\n");
        let before = parse(&raw).unwrap_or_else(|_| panic!("parse failed"));
        let patched = execute("config_patch", &raw, Some(&json!({"hostname":"本机"})))
            .unwrap_or_else(|_| panic!("patch failed"));
        assert_eq!(
            lookup(
                &parse(patched["raw"].as_str().unwrap()).unwrap_or_else(|_| panic!("parse failed")),
                "future"
            ),
            lookup(&before, "future")
        );
        assert!(
            patched["raw"]
                .as_str()
                .unwrap()
                .contains("2026-10-23T12:00:00Z")
        );
    }

    /// 地址和 MTU 已接入字段必须拒绝无效值；平台托管参数和排版变化不产生运行差异。
    #[test]
    fn validates_address_and_ignores_managed_runtime_difference() {
        for changes in [json!({"ipv4":"not-an-ip"}), json!({"flags.mtu":1})] {
            assert!(execute("config_patch", BASE, Some(&changes)).is_err());
        }
        let first = execute("config_parse", BASE, None).unwrap_or_else(|_| panic!("parse failed"));
        let second = execute(
            "config_parse",
            &format!("{BASE}[flags]\nno_tun=true\nenable_ipv6=true\n"),
            None,
        )
        .unwrap_or_else(|_| panic!("parse failed"));
        assert_eq!(first["runtime_semantic"], second["runtime_semantic"]);
    }
    /// 裸地址保留旧 /24 行为，显式 /0、/20、/32 均由固定核心读取为原前缀，不归一化原文。
    #[test]
    fn explicit_ipv4_prefix_is_preserved_by_host_adapter() {
        for (address, expected_prefix) in [
            ("10.42.0.9", 24),
            ("10.42.0.9/0", 0),
            ("10.42.0.9/20", 20),
            ("10.42.0.9/32", 32),
        ] {
            let patched = execute(
                "config_patch",
                BASE,
                Some(&json!({"dhcp":false, "ipv4": address})),
            )
            .unwrap_or_else(|_| panic!("patch failed"));
            let loader = TomlConfigLoader::new_from_str(patched["raw"].as_str().unwrap()).unwrap();
            assert_eq!(loader.get_ipv4().unwrap().network_length(), expected_prefix);
            assert_eq!(patched["projection"]["ipv4"], address);
        }
    }
    /// 指定原节点索引后重排和修改 URL，未知成员仍属于各自节点。
    #[test]
    fn reordering_peers_keeps_unknown_members_with_original_node() {
        let a = "tcp://192.0.2.1:11010";
        let b = "tcp://192.0.2.2:11010";
        let c = "tcp://192.0.2.3:11010";
        let raw = format!(
            "{BASE}[[peer]]\nuri = '{a}'\nfuture_tag = 'A'\n[[peer]]\nuri = '{b}'\nfuture_tag = 'B'\n"
        );
        for (uris, expected) in [(vec![b, a], vec!["B", "A"]), (vec![b, c], vec!["B", "A"])] {
            let peers = vec![
                json!({"kind":"keep", "source_index":1}),
                json!({"kind":"replace_uri", "source_index":0, "uri":uris[1]}),
            ];
            let patched = execute("config_patch", &raw, Some(&json!({"peer_edits":peers})))
                .unwrap_or_else(|_| panic!("patch failed"));
            for (index, tag) in expected.iter().enumerate() {
                assert_eq!(patched["projection"]["peer"][index]["future_tag"], *tag);
            }
        }
    }

    /// 文档保留超出首批范围的 URL；运行拒绝凭据、路径、查询、片段和缺失端口，合法 TCP/UDP 可以编码。
    #[test]
    fn peer_url_runtime_scope_matches_android_endpoint() {
        for uri in [
            "tcp://user:synthetic@192.0.2.1:11010",
            "tcp://@192.0.2.1:11010",
            "tcp://192.0.2.1:11010/",
            "tcp://192.0.2.1:11010?option=1",
            "tcp://192.0.2.1:11010#part",
            "tcp://192.0.2.1",
            "udp://192.0.2.1:0",
            "ws://192.0.2.1:11010",
        ] {
            let raw = format!("{BASE}[[peer]]\nuri = '{uri}'\n");
            let parsed =
                execute("config_parse", &raw, None).unwrap_or_else(|_| panic!("parse failed"));
            assert_eq!(parsed["projection"]["peer"][0]["uri"], uri);
            let runtime =
                execute("config_runtime", &raw, None).unwrap_or_else(|_| panic!("runtime failed"));
            assert_eq!(runtime["runnable"], false);
        }
        for uri in [
            "tcp://192.0.2.1:11010",
            "udp://node.example:11010",
            "tcp://[2001:db8::1]:11010",
        ] {
            let raw = format!("{BASE}[[peer]]\nuri = '{uri}'\n");
            let runtime =
                execute("config_runtime", &raw, None).unwrap_or_else(|_| panic!("runtime failed"));
            assert_eq!(runtime["runnable"], true);
        }
    }
    /// 修改 URL 并同时新增节点时，原节点的未知嵌套布尔和 u64 仍保留在原节点上。
    #[test]
    fn replacing_and_adding_peer_keeps_source_members() {
        let raw = format!(
            "{BASE}[[peer]]\nuri='tcp://192.0.2.1:11010'\nfuture={{enabled=false,wide=18446744073709551615}}\n"
        );
        let next = execute(
            "config_patch",
            &raw,
            Some(&json!({"peer_edits":[
                {"kind":"replace_uri", "source_index":0, "uri":"udp://192.0.2.2:11010"},
                {"kind":"insert", "uri":"tcp://192.0.2.3:11010"}
            ]})),
        )
        .unwrap_or_else(|_| panic!("patch failed"));
        assert_eq!(next["projection"]["peer"][0]["future"]["enabled"], false);
        assert_eq!(
            next["projection"]["peer"][0]["future"]["wide"]["decimal"],
            "18446744073709551615"
        );
        assert!(next["projection"]["peer"][1].get("future").is_none());
        assert_eq!(next["runnable"], false);
    }

    /// 重复 URL 仍按源索引区分；删除其中一个不会把其成员转移，非法索引明确拒绝。
    #[test]
    fn duplicate_uris_and_invalid_origins_are_unambiguous() {
        let raw = format!(
            "{BASE}[[peer]]\nuri='tcp://192.0.2.1:11010'\ntag='A'\n[[peer]]\nuri='tcp://192.0.2.1:11010'\ntag='B'\n"
        );
        let next = execute(
            "config_patch",
            &raw,
            Some(&json!({"peer_edits":[{"kind":"keep","source_index":1}]})),
        )
        .unwrap_or_else(|_| panic!("patch failed"));
        assert_eq!(next["projection"]["peer"][0]["tag"], "B");
        for entries in [
            json!([{"kind":"keep","source_index":0},{"kind":"keep","source_index":0}]),
            json!([{"kind":"keep","source_index":2}]),
            json!([{"kind":"keep","source_index":-1}]),
        ] {
            assert!(execute("config_patch", &raw, Some(&json!({"peer_edits":entries}))).is_err());
        }
    }

    /// 固定核心默认值及规范化后的值运行等价，但源语义仍区分显式输入。
    #[test]
    fn runtime_values_resolve_defaults_and_preserve_source_semantics() {
        let first = execute("config_parse", BASE, None).unwrap_or_else(|_| panic!("parse failed"));
        let second = execute(
            "config_parse",
            &format!("hostname=''\n{BASE}[flags]\nmtu=1380\n"),
            None,
        )
        .unwrap_or_else(|_| panic!("parse failed"));
        assert_eq!(first["runtime_semantic"], second["runtime_semantic"]);
        assert_ne!(first["semantic"], second["semantic"]);
        let third = execute("config_parse", &format!("{BASE}[flags]\nmtu=1400\n"), None)
            .unwrap_or_else(|_| panic!("parse failed"));
        assert_ne!(first["runtime_semantic"], third["runtime_semantic"]);
    }
}
