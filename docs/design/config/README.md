# 配置目录与实施台账

P00 的解析与受控真机观察已在限定范围完成，见 [最终报告](../../verification/p00/report.md)。本目录的 capability-plan 仍是正式产品实施状态基线，P00 实验通过不把它改成全量生产支持。

## 范围及证据

这里保留官网实际部署助手的冻结快照，以及独立的 Android 原生能力实施台账。两份 JSON 不能合并为一个“已经支持”的布尔值。

| 文件 | 用途 |
|---|---|
| [assistant-catalog.snapshot.json](assistant-catalog.snapshot.json) | 原型使用的来源、字段、默认值、分组及历史显隐理由；按原样冻结 |
| [android-capability-plan.json](android-capability-plan.json) | 正式产品能力尚未验收；记录阶段、平台决定与后续产品证据入口，不汇总实验探针的通过状态 |

来源：[官网配置助手](https://easytier.cn/assistant/)。入口抓取时间：`2026-10-10T21:57:46.170012+00:00`。部署 commit 未公开，以资源 SHA-256 定位观察版本。

- 入口 SHA-256：`2ff541efed99e1e873f68ad5640cf12d93b1af7276ef19e171849a51b4a13eae`。
- Bundle SHA-256：`6f8b57a22e2a348d9d1e6b078d04ddf80d1895adcccc2a91c3a45f188ccd41d5`。
- 本地目录 SHA-256：`eaac5fd840290719d0ad270ff4ed36730274d946166646824e4757bd05148e63`。
- 快照内核心语义参考 commit：`83baead0599110bee0d47f4c70b63b23753252a7`。该 commit 与 P00 实验所选 Android 核心版本不同；后者见 [P00 报告](../../verification/p00/report.md)。

目前为 **37 个核心编辑单元、7 个 Android 设置、6 组、52 个官网来源字段、8 个历史隐藏编辑项**。复合项会聚合多个来源字段，不能拿编辑单元数当作 flags 或原生特性数量。

`availability=available` 只记录原型编辑目录，`apply=core-hot` 等仅是早前语义研究标签。P00 已核对限定核心与实验配置路径；正式接入或升级仍须验证接口、字段默认值与效果，生产能力以台账中的产品证据为准。

## 可见目录

下表描述正式产品的效果验收，阶段状态见路线图。P03 建立编辑和序列化闭环，P05 完成高级能力实证；“未验证”不否定 P00 已记录的限定解析或真机观察。

| 组 | 编辑单元 / 项目键 | 官网来源键 | 正式产品效果验收 |
|---|---|---|---|
| 网络与地址 | 网络名称 · `network_identity.network_name` | `network_name` | P03，未验证 |
| 网络与地址 | 网络密码 · `network_identity.network_secret` | `network_secret` | P03，未验证 |
| 网络与地址 | 本设备名称 · `hostname` | `hostname` | P03，未验证 |
| 网络与地址 | 自动分配 IPv4 · `dhcp` | `dhcp` | P03，未验证 |
| 网络与地址 | 虚拟 IPv4 地址 · `ipv4` | `network_length, virtual_ipv4` | P03，未验证 |
| 网络与地址 | 允许底层 IPv6 连接 · `flags.enable_ipv6` | `disable_ipv6` | P05，未验证 |
| 网络与地址 | MagicDNS 组网域名 · `flags.accept_dns` | `enable_magic_dns` | P05，未验证 |
| 节点与直连 | 连接节点 · `peer` | `peer_urls` | P03，未验证 |
| 节点与直连 | 监听地址 · `listeners` | `listener_urls` | P05，未验证 |
| 节点与直连 | 公布的外部地址 · `mapped_listeners` | `mapped_listeners` | P05，未验证 |
| 节点与直连 | 普通自动 P2P · `flags.disable_p2p` | `disable_p2p` | P05，未验证 |
| 节点与直连 | 仅与已直连节点通信 · `flags.p2p_only` | `p2p_only` | P05，未验证 |
| 节点与直连 | 有流量时再建立 P2P · `flags.lazy_p2p` | `lazy_p2p` | P05，未验证 |
| 节点与直连 | 要求对端主动建立 P2P · `flags.need_p2p` | `need_p2p` | P05，未验证 |
| 节点与直连 | UDP 打洞 · `flags.disable_udp_hole_punching` | `disable_udp_hole_punching` | P05，未验证 |
| 节点与直连 | TCP 打洞 · `flags.disable_tcp_hole_punching` | `disable_tcp_hole_punching` | P05，未验证 |
| 节点与直连 | 对称 NAT 打洞 · `flags.disable_sym_hole_punching` | `disable_sym_hole_punching` | P05，未验证 |
| 节点与直连 | 自动端口映射 · `flags.disable_upnp` | `disable_upnp` | P05，未验证 |
| 路由与服务 | 路由接收方式 · `routes` | `enable_manual_routes, routes` | P05，未验证 |
| 路由与服务 | 互联网出口节点 · `exit_nodes` | `exit_nodes` | P05，未验证 |
| 路由与服务 | 分享本地子网 · `proxy_network` | `proxy_cidrs` | P05，未验证 |
| 路由与服务 | SOCKS5 监听 · `socks5_proxy` | `enable_socks5, socks5_port` | P05，未验证 |
| 路由与服务 | 端口转发 · `port_forward` | `port_forwards` | P05，未验证 |
| 路由与服务 | WireGuard Portal · `vpn_portal_config` | `enable_vpn_portal, vpn_portal_client_network_addr, vpn_portal_client_network_len, vpn_portal_listen_port` | P05，未验证 |
| 路由与服务 | 允许本机作为出口 · `flags.enable_exit_node` | `enable_exit_node` | P05，未验证 |
| 安全与访问 | 通信加密 · `flags.enable_encryption` | `disable_encryption` | P05，未验证 |
| 安全与访问 | 限制外部网络接入 · `flags.private_mode` | `enable_private_mode` | P05，未验证 |
| 安全与访问 | 访问控制 ACL · `acl` | `acl` | P05，未验证 |
| 中继与性能 | 优先低延迟路径 · `flags.latency_first` | `latency_first` | P05，未验证 |
| 中继与性能 | 隧道 MTU · `flags.mtu` | `mtu` | P05，未验证 |
| 中继与性能 | 本网络接收限速 · `flags.instance_recv_bps_limit` | `instance_recv_bps_limit` | P05，未验证 |
| 中继与性能 | 允许中继的网络 · `flags.relay_network_whitelist` | `enable_relay_network_whitelist, relay_network_whitelist` | P05，未验证 |
| 中继与性能 | 帮助其他网络交换建链信息 · `flags.relay_all_peer_rpc` | `relay_all_peer_rpc` | P05，未验证 |
| 中继与性能 | KCP 代理 TCP 流 · `flags.enable_kcp_proxy` | `enable_kcp_proxy` | P05，未验证 |
| 中继与性能 | 允许 KCP 代理接入 · `flags.disable_kcp_input` | `disable_kcp_input` | P05，未验证 |
| 中继与性能 | QUIC 代理 TCP 流 · `flags.enable_quic_proxy` | `enable_quic_proxy` | P05，未验证 |
| 中继与性能 | 允许 QUIC 代理接入 · `flags.disable_quic_input` | `disable_quic_input` | P05，未验证 |
| Android 应用设置 | 应用范围 · `android.appRoutingMode` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | 选中的应用 · `android.appPackages` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | 互联网流量范围 · `android.internetRouting` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | 非组网 IPv6 策略 · `android.ipv6Policy` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | DNS 策略 · `android.dnsMode` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | 自定义 DNS 地址 · `android.dnsServers` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |
| Android 应用设置 | 网络恢复后自动重连 · `android.autoReconnect` | `Android 自有设置，不写入核心 TOML` | P05，未验证 |

## 基础配置与本地名称

基础输入严格指向 `network_identity.network_name`、`network_identity.network_secret`、`peer`。地址方式以摘要展示；静态数据不被基础模式覆盖。“基础配置 / 完整配置”只是视图，不是两个配置模型。

原型本地名称 `profile.label` 统一映射到原生 `ProfileMetadata.localDisplayName`，原生只保留这一份可写元数据。它不属于官网字段、不应写入 `instance_name`，也不计入 44 个网络/平台编辑单元。全局主题、日志显示偏好等也不冒充每个网络的核心参数。

## 8 个历史隐藏项的复审

| 键 | 原型理由 | P00/P05要求 |
|---|---|---|
| `flags.bind_device` | 当前 Android native host 设置 allow_interface_bind=false，物理网卡绑定由平台适配管理。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `flags.dev_name` | 本客户端采用免 Root VpnService；TUN 由 Android 创建，接口名称由平台管理。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `flags.enable_udp_broadcast_relay` | 此实现捕获物理网卡 UDP 广播的路径限定 Windows + tun；当前 Android 客户端不提供。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `ipv6_public_addr_auto` | 当前客户端尚未提供公网 IPv6 自动分配与 TUN 路由同步适配；保留来源映射但不展示为可操作选项。 | 尚未适配不等于不适用；先调查公网IPv6与TUN路由同步，可适用则恢复编辑并实现 |
| `flags.multi_thread` | JNI 共享运行时由应用初始化，不作为单网络的可编辑开关。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `flags.no_tun` | 本客户端运行模式固定为免 Root VPN；不提供改变数据路径的 NO_TUN 模式开关。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `flags.proxy_forward_by_system` | 免 Root VpnService 不提供 Linux 内核转发管理；本客户端采用用户态代理。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |
| `flags.use_smoltcp` | 当前 Android native host 要求用户态协议栈，属于宿主接管选项。 | 对选定版本重新举证，区分平台托管、当前实现限制与产品运行模式；保留已有保存数据 |

隐藏项在表单与搜索中都不可出现，但导入/保存不能无提示删除它们。保留原始数据不等于把未验证字段直接交给运行核心；保留文档与生成有效运行配置是两个步骤。

## 审计命令

从仓库根运行。候选快照由审计任务指定；临时报告放在忽略目录：

```bash
python3 scripts/catalog/audit_config_catalog.py
python3 scripts/catalog/audit_config_catalog.py --catalog candidate.json --baseline docs/design/config/assistant-catalog.snapshot.json --report build/catalog/candidate-audit.json
```

退出码 `0`：目录约束通过；`1`：约束错误或相对基线有需要评审的差异；`2`：输入/读取/报告路径错误。比较报告只写键与差异属性，不打印密码等值。脚本不会联网，也不会自动覆盖快照。默认快照同时检查项目能力台账；外部候选默认只查目录，需要时用 `--capability-plan candidate-capabilities.json` 指定对应台账，并检查报告中的 `capability_plan_checked`。

完整语义核查还包括反向布尔、missing/empty、单位、依赖、默认值和未知字段；脚本通过不能代替官网新版本比对、核心解析器或真机测试。参考 [配置审计技能](../../../.agents/skills/easytier-config-audit/SKILL.md)。
