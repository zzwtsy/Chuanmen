# 配置目录与实施台账

P00 的解析与受控真机观察已在限定范围完成，见 [最终报告](../../verification/p00/report.md)。本目录的 capability-plan 仍是正式产品实施状态基线，P00 实验通过不把它改成全量生产支持。

## 范围及证据

这里保留官网实际部署助手的冻结快照，以及独立的 Android 原生能力实施台账。两份 JSON 不能合并为一个“已经支持”的布尔值。

| 文件 | 用途 |
|---|---|
| [assistant-catalog.snapshot.json](assistant-catalog.snapshot.json) | 原型使用的来源、字段、默认值、分组及历史显隐理由；按原样冻结 |
| [android-capability-plan.json](android-capability-plan.json) | 记录阶段、平台决定与工程实现证据；工程实现和设备效果分别维护，不汇总实验探针的通过状态 |

来源：[官网配置助手](https://easytier.cn/assistant/)。入口抓取时间：`2026-10-10T21:57:46.170012+00:00`。部署 commit 未公开，以资源 SHA-256 定位观察版本。

- 入口 SHA-256：`2ff541efed99e1e873f68ad5640cf12d93b1af7276ef19e171849a51b4a13eae`。
- Bundle SHA-256：`6f8b57a22e2a348d9d1e6b078d04ddf80d1895adcccc2a91c3a45f188ccd41d5`。
- 本地目录 SHA-256：`eaac5fd840290719d0ad270ff4ed36730274d946166646824e4757bd05148e63`。
- 快照内核心语义参考 commit：`83baead0599110bee0d47f4c70b63b23753252a7`。该 commit 与 P00 实验所选 Android 核心版本不同；后者见 [P00 报告](../../verification/p00/report.md)。

目前为 **37 个核心编辑单元、7 个 Android 设置、6 组、52 个官网来源字段、8 个历史隐藏编辑项**。复合项会聚合多个来源字段，不能拿编辑单元数当作 flags 或原生特性数量。

`availability=available` 只记录原型编辑目录，`apply=core-hot` 等仅是早前语义研究标签。P00 已核对限定核心与实验配置路径；正式接入或升级仍须验证接口、字段默认值与效果，生产能力以台账中的产品证据为准。

## 台账字段与状态的准确含义

P03 首批 10 项的工程实现及复审修复已通过检查，engineeringStatus 为 implemented；接口、测试、实现摘要与检查结果见 [P03 复审修复记录](../../plan/p03-configuration.md#7-复审修复记录2026-10-24)。P05 其余 34 项及后续扩展保持 planned。P02 的 controlledInputStage 与原运行证据保留；它们只能证明原限定范围，当前设备效果未验证。

| 字段 | 填写和判断规则 |
|---|---|
| editorStage / implementationStage | 首次编辑器与生产接线交付阶段；P03 为 10 项，P05 为 34 项。P03 的 peer 与互联网/DNS/IPv6 仅交付该阶段明确的子集 |
| implementationTask | 字段映射与接线的主责任务，不表示只完成这一任务就关闭整项 |
| completionTasks | 首次交付范围必须全部完成的任务 ID，按该阶段执行顺序排列；P03 先 01/03 再 02。少一项都不能写 implemented |
| engineeringStatus | planned=未开始；in_progress=已开始但未满足断言；implemented=completionTasks 均完成且工程证据可定位；blocked=已查明缺失接口/依赖等条件使当前实现无法继续。尚未写代码不是 blocked |
| engineeringEvidence | 实施后填写代码路径/符号、测试路径/用例、检查对应源码 SHA 和结果定位；计划本身不算实现证据；实施记录须绑定实际代码、用例及命令结果 |
| extensionTask / extensionEngineeringStatus / extensionEngineeringEvidence | 后续扩展独立记状态与证据；peer 复合成员/协议由 P05-02 扩展，P03 三项 Android 策略由 P05-06 扩展，autoReconnect 的有限退避由 P06-02 扩展。首次 implemented 不表示扩展也已完成 |
| nativeStatus / evidence | 保留既有原生验收状态和证据；本轮工程通过不会改成运行 verified |
| runtimeVerificationStage | null 表示本轮取消设备验证，没有未来补跑阶段；不表示“不适用”或“已通过” |
| minimumValidation | 原运行验收判据保留用于理解 nativeStatus 尚未完成的范围；不是本轮执行清单，现行工程断言见责任任务 |
| hiddenReview | 固定 8 条历史隐藏记录；productionDecision=requires_platform_review 表示调查尚未完成，不能按 reasonClass 推导已确认平台托管 |
| nativeExtensions | 未来经适配交付的原生新增项；不放入历史 fields 的 44 项集合，不覆盖快照 |

运行变更统一受控重连，applyPolicy=controlled-reconnect；本轮不建立热应用白名单。实施任务若缺少来源，先执行该任务明列的源码调查步骤，记录确切缺失入口；不能用“后续核对”作为完成说明。

## 可见目录

下表描述首次工程接入阶段；设备效果均未验证，本轮不安排设备执行。P03 首批 10 项，P05 其余 34 项；三个 Android 策略在 P05-06 扩展。任务及工程状态见台账，逐项隐藏复审步骤见 [P05-01](../../plan/p05-advanced-networking.md#p05-01--将能力矩阵接入完整配置)。

| 组 | 编辑单元 / 项目键 | 官网来源键 | 首次工程接入 / 设备效果 |
|---|---|---|---|
| 网络与地址 | 网络名称 · `network_identity.network_name` | `network_name` | P03，设备未验证 |
| 网络与地址 | 网络密码 · `network_identity.network_secret` | `network_secret` | P03，设备未验证 |
| 网络与地址 | 本设备名称 · `hostname` | `hostname` | P03，设备未验证 |
| 网络与地址 | 自动分配 IPv4 · `dhcp` | `dhcp` | P03，设备未验证 |
| 网络与地址 | 虚拟 IPv4 地址 · `ipv4` | `network_length, virtual_ipv4` | P03，设备未验证 |
| 网络与地址 | 允许底层 IPv6 连接 · `flags.enable_ipv6` | `disable_ipv6` | P05，设备未验证 |
| 网络与地址 | MagicDNS 组网域名 · `flags.accept_dns` | `enable_magic_dns` | P05，设备未验证 |
| 节点与直连 | 连接节点 · `peer` | `peer_urls` | P03（P05 扩展），设备未验证 |
| 节点与直连 | 监听地址 · `listeners` | `listener_urls` | P05，设备未验证 |
| 节点与直连 | 公布的外部地址 · `mapped_listeners` | `mapped_listeners` | P05，设备未验证 |
| 节点与直连 | 普通自动 P2P · `flags.disable_p2p` | `disable_p2p` | P05，设备未验证 |
| 节点与直连 | 仅与已直连节点通信 · `flags.p2p_only` | `p2p_only` | P05，设备未验证 |
| 节点与直连 | 有流量时再建立 P2P · `flags.lazy_p2p` | `lazy_p2p` | P05，设备未验证 |
| 节点与直连 | 要求对端主动建立 P2P · `flags.need_p2p` | `need_p2p` | P05，设备未验证 |
| 节点与直连 | UDP 打洞 · `flags.disable_udp_hole_punching` | `disable_udp_hole_punching` | P05，设备未验证 |
| 节点与直连 | TCP 打洞 · `flags.disable_tcp_hole_punching` | `disable_tcp_hole_punching` | P05，设备未验证 |
| 节点与直连 | 对称 NAT 打洞 · `flags.disable_sym_hole_punching` | `disable_sym_hole_punching` | P05，设备未验证 |
| 节点与直连 | 自动端口映射 · `flags.disable_upnp` | `disable_upnp` | P05，设备未验证 |
| 路由与服务 | 路由接收方式 · `routes` | `enable_manual_routes, routes` | P05，设备未验证 |
| 路由与服务 | 互联网出口节点 · `exit_nodes` | `exit_nodes` | P05，设备未验证 |
| 路由与服务 | 分享本地子网 · `proxy_network` | `proxy_cidrs` | P05，设备未验证 |
| 路由与服务 | SOCKS5 监听 · `socks5_proxy` | `enable_socks5, socks5_port` | P05，设备未验证 |
| 路由与服务 | 端口转发 · `port_forward` | `port_forwards` | P05，设备未验证 |
| 路由与服务 | WireGuard Portal · `vpn_portal_config` | `enable_vpn_portal, vpn_portal_client_network_addr, vpn_portal_client_network_len, vpn_portal_listen_port` | P05，设备未验证 |
| 路由与服务 | 允许本机作为出口 · `flags.enable_exit_node` | `enable_exit_node` | P05，设备未验证 |
| 安全与访问 | 通信加密 · `flags.enable_encryption` | `disable_encryption` | P05，设备未验证 |
| 安全与访问 | 限制外部网络接入 · `flags.private_mode` | `enable_private_mode` | P05，设备未验证 |
| 安全与访问 | 访问控制 ACL · `acl` | `acl` | P05，设备未验证 |
| 中继与性能 | 优先低延迟路径 · `flags.latency_first` | `latency_first` | P05，设备未验证 |
| 中继与性能 | 隧道 MTU · `flags.mtu` | `mtu` | P03，设备未验证 |
| 中继与性能 | 本网络接收限速 · `flags.instance_recv_bps_limit` | `instance_recv_bps_limit` | P05，设备未验证 |
| 中继与性能 | 允许中继的网络 · `flags.relay_network_whitelist` | `enable_relay_network_whitelist, relay_network_whitelist` | P05，设备未验证 |
| 中继与性能 | 帮助其他网络交换建链信息 · `flags.relay_all_peer_rpc` | `relay_all_peer_rpc` | P05，设备未验证 |
| 中继与性能 | KCP 代理 TCP 流 · `flags.enable_kcp_proxy` | `enable_kcp_proxy` | P05，设备未验证 |
| 中继与性能 | 允许 KCP 代理接入 · `flags.disable_kcp_input` | `disable_kcp_input` | P05，设备未验证 |
| 中继与性能 | QUIC 代理 TCP 流 · `flags.enable_quic_proxy` | `enable_quic_proxy` | P05，设备未验证 |
| 中继与性能 | 允许 QUIC 代理接入 · `flags.disable_quic_input` | `disable_quic_input` | P05，设备未验证 |
| Android 应用设置 | 应用范围 · `android.appRoutingMode` | `Android 自有设置，不写入核心 TOML` | P05，设备未验证 |
| Android 应用设置 | 选中的应用 · `android.appPackages` | `Android 自有设置，不写入核心 TOML` | P05，设备未验证 |
| Android 应用设置 | 互联网流量范围 · `android.internetRouting` | `Android 自有设置，不写入核心 TOML` | P03（P05 扩展），设备未验证 |
| Android 应用设置 | 非组网 IPv6 策略 · `android.ipv6Policy` | `Android 自有设置，不写入核心 TOML` | P03（P05 扩展），设备未验证 |
| Android 应用设置 | DNS 策略 · `android.dnsMode` | `Android 自有设置，不写入核心 TOML` | P05，设备未验证 |
| Android 应用设置 | 自定义 DNS 地址 · `android.dnsServers` | `Android 自有设置，不写入核心 TOML` | P03（P05 扩展），设备未验证 |
| Android 应用设置 | 网络恢复后自动重连 · `android.autoReconnect` | `Android 自有设置，不写入核心 TOML` | P05，设备未验证 |

## 基础配置与本地名称

基础输入严格指向 `network_identity.network_name`、`network_identity.network_secret`、`peer`。地址方式以摘要展示；静态数据不被基础模式覆盖。“基础配置 / 完整配置”只是视图，不是两个配置模型。

原型本地名称 `profile.label` 统一映射到原生 `ProfileMetadata.localDisplayName`，原生只保留这一份可写元数据。它不属于官网字段、不应写入 `instance_name`，也不计入 44 个网络/平台编辑单元。全局主题、日志显示偏好等也不冒充每个网络的核心参数。

## 8 个历史隐藏项的复审

以下是已分配的调查与实现任务，不是新的平台事实结论。具体调查入口、决定条件和断言以 [P05-01](../../plan/p05-advanced-networking.md#p05-01--将能力矩阵接入完整配置) 为唯一任务说明。

| key | 当前可确认的状态 | 调查任务 | 决定后的实现归属 |
|---|---|---|---|
| flags.bind_device | 仍需核对 Android interface bind/protect 调用链 | P05-01b | P05-06，宿主物理网络选择与运行覆盖 |
| flags.dev_name | 仍需核对外部 fd 与核心创建 TUN 的分支 | P05-01b | P05-06，阻止通过名称创建第二 TUN |
| flags.enable_udp_broadcast_relay | 历史理由为平台编译限制，当前决定尚未更新 | P05-01b | P05-03，按固定源码判断不适用或实施用户态适配 |
| ipv6_public_addr_auto | 尚未适配，不能判为永久不适用 | P05-01c | P05-06，地址事件、读取、TUN 同步及失败处理 |
| flags.multi_thread | 仍需核对共享 runtime 与配置读取时点 | P05-01b | P05-02 完成运行覆盖；P06-05 追加资源回归 |
| flags.no_tun | 仍需核对外部 TUN 接入所需配置值 | P05-01b | P05-06，固定外部 TUN 接入模式 |
| flags.proxy_forward_by_system | 仍需核对内核转发与用户态分支 | P05-01b | P05-03，免 Root 用户态代理接线 |
| flags.use_smoltcp | 仍需核对 Android 编译 feature 和实际选择 | P05-01b | P05-03，宿主协议栈选择与运行覆盖 |

仍属平台托管、不适用或未适配的项不进入可操作表单或搜索；ipv6_public_addr_auto 只有完成适配与工程断言后才能加入 nativeExtensions 并展示。保存原文不删除隐藏字段，运行投影仅使用已确认的宿主值；无法解释的字段阻止应用。当前八项调查均未开始，不能将本表的任务描述写成已完成平台支持。

## 审计命令

从仓库根运行。候选快照由审计任务指定；临时报告放在忽略目录：

```bash
python3 scripts/catalog/audit_config_catalog.py
python3 scripts/catalog/audit_config_catalog.py --catalog candidate.json --baseline docs/design/config/assistant-catalog.snapshot.json --report build/catalog/candidate-audit.json
```

退出码 `0`：目录约束通过；`1`：约束错误或相对基线有需要评审的差异；`2`：输入/读取/报告路径错误。比较报告只写键与差异属性，不打印密码等值。脚本不会联网，也不会自动覆盖快照。默认快照同时检查项目能力台账；外部候选默认只查目录，需要时用 `--capability-plan candidate-capabilities.json` 指定对应台账，并检查报告中的 `capability_plan_checked`。

完整语义核查还包括反向布尔、missing/empty、单位、依赖、默认值和未知字段；脚本通过不能代替官网新版本比对、核心解析器或真机测试。参考 [配置审计技能](../../../.agents/skills/easytier-config-audit/SKILL.md)。
