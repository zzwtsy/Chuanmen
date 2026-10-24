# P05 · 高级网络能力与导入导出

实施依据：2026-10-23 计划；状态以 [路线图](ROADMAP.md) 为准。前置为 P03 的存储/codec/草稿及 P04 的观测/日志工程退出。取消设备拓扑、真机互操作与真实网络效果验收；正式程序仍接真实核心，不能用测试替身代替能力实现。

## 1. 范围、能力状态与实施顺序

P03 首批 10 项，P05 首次接入 34 项。每批同时完成编辑器、语义校验、codec、运行接线、失败处理和工程测试；未完成字段不放置可操作的假能力。P03 首批中的 peer 在 P05-02 扩展复合成员与协议，internetRouting、dnsServers、ipv6Policy 在 P05-06 完整扩展，均不重复计数。

[能力台账](../design/config/android-capability-plan.json)分别记录 editorStage、implementationStage、engineeringStatus、工程证据和 nativeStatus。runtimeVerificationStage 为 null，表示本轮不安排设备运行验证；不表示该项不需要真实平台才能证明效果。未开始的实现记录 planned，开始后记录 in_progress；只有查明无法继续的具体技术条件才记录 blocked，普通待办不能记成阻塞。P05 阶段完成要求承诺范围工程交付闭合；阻塞条目不因文档解释而变成实现完成。

顺序：P05-01 → P05-02（A）→ P05-03 → P05-06（B，先路由后平台策略）→ P05-04（C）→ P05-05（D）→ P05-07 → P05-08。

| 批次 | 首次接入 key | 数量 | 责任任务 |
|---|---|---:|---|
| A 监听与建链 | listeners、mapped_listeners、flags.disable_p2p、flags.p2p_only、flags.lazy_p2p、flags.need_p2p、flags.disable_udp_hole_punching、flags.disable_tcp_hole_punching、flags.disable_sym_hole_punching、flags.disable_upnp | 10 | P05-02 |
| B 路由与 Android | routes、exit_nodes、proxy_network、flags.enable_exit_node、flags.enable_ipv6、flags.accept_dns、android.appRoutingMode、android.appPackages、android.dnsMode、android.autoReconnect | 10 | P05-03/06 |
| C 本地服务 | socks5_proxy、port_forward、vpn_portal_config | 3 | P05-04 |
| D 安全与传输 | flags.enable_encryption、flags.private_mode、acl、flags.latency_first、flags.instance_recv_bps_limit、flags.relay_network_whitelist、flags.relay_all_peer_rpc、flags.enable_kcp_proxy、flags.disable_kcp_input、flags.enable_quic_proxy、flags.disable_quic_input | 11 | P05-05 |

目录中复合对象的全部子字段属于对应任务，不能只实现总开关。冻结快照提供用户编辑范围，固定核心提供实际语义；默认值冲突以核心事实和明确 Android 策略映射处理，不改写快照或扩大到 STUN、远程管理等目录外能力。

## 2. 共同实现规则

- 使用同一个配置草稿、共享 codec 和 ProfileRepository；新字段不能直接改运行实例。运行参数全部经 Service 的受控重连事务应用，不引入字段热更。
- 目录、表单、搜索共用能力过滤，平台托管/不适用/未适配分开。原始文档保留未知项，未接入参数不自动执行。
- 对每个 key 记录固定核心位置、类型/默认/单位、构建 feature、宿主接线、错误码和测试位置。运行结果只能来自实际回执，UI 不以保存成功推导效果。
- 新监听、连接和服务复用原生会话拥有者，纳入取消、protect、回调排空和停止释放。工程测试通过不构成 Android 入站、回程、打洞或耗电证明。
- 共享 lib/profile 保存跨切片配置类型，lib/vpn 负责策略与 TUN，app/native 负责核心适配；编辑和导入导出位于 features/configuration，不创建独立模块。

## 3. 分任务

### P05-01 · 将能力矩阵接入完整配置

本任务交付字段映射、隐藏项决策和可供表单消费的能力目录。台账的任务归属已由本轮文档改写建立；本任务核对并补充源码事实，不重复创建记录。各字段的编辑器与运行接线按下述责任任务实施，不以完成台账代替完成能力。

**修改位置**：`docs/design/config/android-capability-plan.json`、同目录 README、技术架构、P03 字段目录及配置审计器。只向字段目录添加来源/能力决定，不提前创建未接线编辑器。审计器新增字段约束时同步其现有单元测试。

**开始条件**

1. P03-03 已提供 parse、patch、encodeForRuntime，测试能区分缺省值、显式空值、未知字段和平台覆盖字段。
2. P03-05 已提供根据字段目录生成分组、搜索结果和可编辑状态的入口。
3. P04-07 已提供安全错误记录入口；传入字段 key 和错误码不会记录字段值。
4. 上述接口尚未交付时，本任务保持未开始，不创建第二套 codec、表单目录或日志接口。

**P05-01a：建立 44 项与子字段的映射**

1. 读取 `docs/design/config/assistant-catalog.snapshot.json` 和 `android-capability-plan.json`。以快照中的 37 个核心编辑单元及 7 个 Android 设置为集合，逐 key 对应台账 `fields`；本地名称不计入这个集合。
2. 核对台账已有的 `implementationTask`：P03 的 10 项指向 P03-03；本节 A 批指向 P05-02；B 批中的 routes、exit_nodes、proxy_network、flags.enable_exit_node 指向 P05-03，其余 6 项指向 P05-06；C 批指向 P05-04；D 批指向 P05-05。peer 扩展使用 `extensionTask=P05-02`，P03 三项 Android 策略使用 `extensionTask=P05-06`；autoReconnect 的后续有限退避为 P06-02。扩展状态分别记录，不能另建重复 key。
3. 复合项增加 `members`，每个成员记录完整字段路径、类型、缺省语义、空值语义、默认值及来源。对照固定核心的配置结构和解析方法枚举子字段；快照没有但当前固定核心对象必需的成员标记为宿主适配成员。不能只列总开关，也不能把成员数算入 44 个编辑单元。
4. 核心来源记录 `app/native/inputs.json` 中的 commit、上游相对路径和符号名；Android 设置记录本仓库策略类型和消费者路径。行号可辅助定位，不能作为唯一定位方式。源码尚无对应消费者时写 `null`，并保留负责创建它的任务号。
5. 对照配置中的反向布尔项记录转换规则，例如 `disable_ipv6` 与 `flags.enable_ipv6` 的反转；记录整数单位及范围，不用“按核心处理”替代具体类型和取值。

**P05-01b：逐项处理 8 个历史隐藏项**

修改台账现有 `hiddenReview` 记录，不修改快照。以下表格规定要查找的调用链和交付决定；查不到证据时保持 `requires_platform_review` 并记录缺失符号或调用链，不能直接写成平台托管。

| key | 必须核对的源码事实 | 证据成立后的处理 | 后续任务 |
|---|---|---|---|
| flags.bind_device | Android host 是否禁用 interface bind；外层 socket 是否经 protect 和底层 Network 选择 | 由宿主选择物理网络，表单及搜索不提供该开关；运行投影使用宿主值 | P05-06 |
| flags.dev_name | TUN 是否由 VpnService.Builder 创建并以 fd 交给核心；核心是否仍按名称另建 TUN | 保留输入原文；运行投影禁止用户字段触发创建第二个 TUN | P05-06 |
| flags.enable_udp_broadcast_relay | 广播捕获入口的 cfg 条件、Android 编译结果及调用者 | 仅当固定版本确实无 Android 路径时标记该版本不适用，并记录 cfg 和符号；若存在路径则列为待适配，不能沿用旧隐藏理由 | P05-03 |
| ipv6_public_addr_auto | 地址生成、更新通知、地址/前缀读回、TUN 更新四个环节 | 按 P05-01c 交付调查结果；未适配时禁止进入可操作表单 | P05-06 |
| flags.multi_thread | JNI runtime 的创建者、数量及配置读取时点 | runtime 由宿主统一管理，运行投影不能为单个网络创建另一套 runtime | P05-02（P06-05 追加资源回归） |
| flags.no_tun | 固定核心的外部 fd 路径及 no_tun 分支含义 | 运行投影固定为外部 TUN 接入要求的值；禁止用户切换为无 VPN 数据路径 | P05-06 |
| flags.proxy_forward_by_system | 系统转发分支是否需要内核路由/转发权限；用户态代理分支入口 | 免 Root 模式使用用户态路径；运行投影不请求系统转发权限 | P05-03 |
| flags.use_smoltcp | Android host 的用户态协议栈构建 feature 及实际选择逻辑 | 运行投影使用该宿主要求的协议栈选择；不提供会破坏用户态代理的开关 | P05-03 |

每条 `hiddenReview` 增加来源定位、`implementationTask` 和运行覆盖说明。所有原始字段继续保留在保存文档中。运行值不同于原文时，预览按 key 列出“由 Android 管理”的差异；未经确认的字段阻止应用，不静默透传或删除。

**P05-01c：确定 ipv6_public_addr_auto 的适配路径**

1. 从固定核心配置解析入口追踪该字段，分别记录：地址生成函数、更新事件、地址/前缀读取接口、停止时的清理函数。每项填写源码路径和符号；不存在的接口明确写“缺失”。
2. 在技术架构中画出“核心地址事件 → 当前会话代次检查 → 平台规格 → TUN 重建/交接”的调用顺序，注明地址消失和旧代次事件的处理。任何步骤缺少实现入口时，列出需要修改的 JNI DTO、核心适配或 Service 接口。
3. 调查完成不等于适配完成。将适配列为 P05-06 的独立子任务，写出具体新增接口与测试；若固定核心缺少必要入口且需要升级核心才能继续，则标记 `engineeringStatus=blocked`，记录缺失入口及升级需求，停止这项适配。不得自行升级核心，也不得据此标记不适用。
4. 工程适配及相应测试完成后，在能力台账的 `nativeExtensions` 数组新增该 key 的原生能力记录，并允许表单和搜索展示。`fields` 仍为原有 44 项，`hiddenReview` 保留原型隐藏历史及新记录引用。测试至少断言首次地址、地址变更、地址消失、停止后迟到事件和 TUN 重建失败的处理。

**失败处理**：找不到固定源码符号时记录查找路径和缺失接口，保持 requires_platform_review；该项来源调查未结束，P05-01 不完成。查明存在接口但适配尚未开始时分配 planned 任务；查明必须升级核心才能实现时分配 blocked 任务。不能把后一种阻塞伪装成不适用。

**产物与完成断言**

- `android-capability-plan.json`：44 个 key 与快照集合完全相等、无重复；每项恰有一个首次实现任务；peer、三个 Android 策略及 autoReconnect 分别记录扩展任务；8 条隐藏项各有来源及处理决定或明确阻塞原因。
- `docs/design/config/README.md`：同步字段分期、隐藏项当前决定及原生扩展，不把新增原生项计入历史 44 项。
- `docs/design/technical-architecture.md`：记录运行投影覆盖规则和 IPv6 地址同步接口。上述事实调查未完成前，不写“已确认平台托管”。
- 工程状态只允许 planned、in_progress、implemented、blocked；首次范围的 completionTasks 全部完成才写 implemented。后续扩展由 extensionEngineeringStatus 单独记录，不倒推首次范围已经完成扩展。`engineeringEvidence` 指向具体实现与测试，不能只指向计划段落。
- 执行目录审计，并增加针对集合相等、任务归属、成员映射和隐藏项决定的校验。审计报错或仍有未填写来源的记录时，P05-01 不完成。已查明且有明确任务的实现阻塞允许结束本次调查，但会阻止 P05 阶段整体完成。
- `nativeStatus` 与原有运行证据保持独立；本任务不产生设备证据，不能把工程实现状态复制为 nativeStatus=verified。

### P05-02 · 完成连接、监听与建链策略

**开始条件**：P05-01 的 44 项映射、8 项复审和 IPv6 调查记录已完成；A 批每个成员的类型、默认和消费者已确定。

**修改位置**：`features/configuration` 的字段目录/编辑器，`lib/vpn` 的 codec，`app/native` 的核心配置接线；任务路径均相对现有单 app，不创建新 Gradle 模块。

1. 实现 A 批 10 项及 peer 复合对象的成员。监听地址列表和公布地址列表使用不同字段，不能互相填充；列表支持添加、删除、排序，每个错误定位到条目。
2. listeners 缺省交给固定核心默认，显式 [] 表示不监听，自定义列表保持顺序；编辑其他字段不能将缺省写成 []。基础模式的默认加入校验只约束该模式的新建操作，不阻止固定核心允许的无 peer 监听配置。
3. 按 P05-01 的来源记录校验 scheme、主机、端口与成员类型；界面布尔名称与核心反向 flag 的转换只在 codec 发生一次。不能在 UI 和 Rust 各反转一次。
4. 用运行投影接入所有开关；用户只保存时不触碰监听。应用时通过既有 Service 受控重连，监听启动失败走同一失败/释放事务。
5. 完成 flags.multi_thread 的宿主覆盖：沿用唯一 JNI runtime，禁止单网络值创建新 runtime；P06-05 只追加压力与释放断言，不承担本项首次实现。

**失败处理**：错误 URL/端口阻止应用且保留原输入；端口冲突返回监听字段错误，关闭本次已创建监听，不把整个 App 异常退出。固定核心不支持的组合明确拒绝，不自动关掉用户选择。

**完成断言**：缺省/空/自定义监听三种文档在修改 hostname 后语义不变；反向布尔往返为原值；无 peer 的合法监听配置通过解析；占用端口的宿主用例返回错误且无遗留监听；停止两次不重复释放。Android 打洞和 UPnP 效果未验证。

### P05-03 · 完成路由、子网与出口

**开始条件**：P05-02 完成；P05-01 已列出 routes、exit_nodes、proxy_network、flags.enable_exit_node 的成员及 Android 投影来源。

**修改位置**：配置编辑器、共享配置类型/codec、`lib/vpn/Runtime.kt` 与 `TunPlatform.kt`、native 路由/用户态代理接线。

1. 将路由接收、使用远端出口、本机提供出口分别建模。routes 的缺省自动接收与显式手工列表不混用；exit_nodes 不从本机出口开关推导。
2. 按固定核心对象实现 proxy_network 的网段、映射及相关成员。CIDR 解析保留前缀，拒绝非法地址族/前缀；同一网络重复条目去重，含冲突映射的条目给出两处字段位置，不自动选择其中一个。
3. 从同一个已保存修订生成核心配置与平台路由；自动路由来自当前代次的核心快照，旧代次路由不能加入新 TUN。
4. 子网代理走固定核心用户态路径，不写宿主/Android 内核转发或 Root 规则；完成 proxy_forward_by_system、use_smoltcp 的运行覆盖。原始值保留，预览列出覆盖 key。
5. enable_udp_broadcast_relay 按 P05-01 的固定源码结论处理：确无 Android 编译路径时保留版本限定的不适用决定；存在路径时接入并测试，无法接入则阻塞本任务，不删除该记录。

**失败处理**：路由冲突和缺少出口选择在创建新 TUN 前拒绝。核心启动后平台创建失败走既有停止/恢复事务，不保留“核心 Running、平台半应用”的成功状态。

**完成断言**：保存 B 不改变运行 A 的核心或 Builder 调用；明确启动 B 时二者修订一致；错误 CIDR 不创建 TUN；空/缺省路由区别保留；旧路由事件被拒绝；用户态代理停止后宿主监听计数回到基线。设备业务回程未验证。

### P05-04 · 完成本地服务与 WireGuard Portal

**开始条件**：P05-03 和 P05-06 均完成。虽然任务编号为 04，实际执行位于 06 之后，避免服务实现依赖未交付的平台策略。

**修改位置**：配置页 C 批编辑器、codec、native 服务创建/关闭路径和安全诊断映射。

1. 按 P05-01 成员清单分别实现 socks5_proxy、port_forward、vpn_portal_config；Portal 使用固定核心的 wireguard_listen、私钥及具名 clients，不沿用历史网页子网对象伪装当前结构。
2. 监听编辑器区分 loopback 与非 loopback 地址；非 loopback 保存和启用前展示“其他可达设备可访问”的持续说明。地址未填写时使用来源表已确定的默认，不自行改为 0.0.0.0。
3. TCP/UDP 转发逐条记录监听端和目标端，依固定核心支持协议校验；相同协议和地址端口冲突定位到条目。Portal 的客户端名称须唯一，地址/公钥/允许网段按实际对象成员校验。
4. 私钥生成调用固定核心已有密码实现；若无公开生成入口，使用该核心已经依赖的成熟密码库并提供对接函数，不用示例密钥或自行实现算法。显示/复制/导出私钥必须用户明确动作，普通 toString/日志只显示脱敏标记。
5. 所有服务归当前 session 拥有，纳入启动失败、取消和停止的关闭顺序；复制生成的客户端配置不启动额外会话。

**失败处理**：私钥错误不回显内容；端口被占用返回结构化错误，关闭本次已创建的其他服务；客户端导出失败不提示成功。

**完成断言**：三个对象的全部成员解析/编码往返；损坏密钥和重复客户端被拒绝；固定核心可解析生成的客户端配置；TCP/UDP 端口冲突有安全错误；停止和半途失败无遗留宿主监听。WireGuard 的 Android 握手与互操作未验证。

### P05-05 · 完成安全、性能与传输增强

**开始条件**：P05-04 完成；D 批来源和默认值已由 P05-01 确定。

**修改位置**：D 批结构化编辑器、共享 codec、native 配置消费及宿主测试。

1. 实现第 1 节 D 批 11 项，不把 private_mode、中继白名单、RPC 转发合并成一个安全开关。每个 UI 字段均映射到独立 key。
2. ACL 按固定核心结构编辑链、规则和成员，保留顺序；添加/删除/移动操作明确修改哪一条，序列化不按名称排序。空 ACL 与缺省 ACL 分开保存。
3. 新建默认通信加密开启；关闭须用户确认，取消确认保持原值。已有配置读取时保留用户值，不用新建默认覆盖。
4. instance_recv_bps_limit 只接受非负十进制整数文本，按来源表的单位显示；0 是否无限制由固定源码记录决定，不能自行约定。全链路使用无损 u64，超界拒绝，不经 Double。
5. KCP/QUIC 的启用代理和允许接入分别编辑，缺省/false/true 各自保留；固定核心拒绝的组合返回字段错误，不自动关闭其他选项。

**失败处理**：ACL 结构、地址或数值错误保留原输入并阻止应用；原生解析失败只显示路径与错误码，不把 ACL 全文写日志。

**完成断言**：ACL 两条规则交换后输出顺序相应变化；空/缺省区分；u64 最大值往返不损失，最大值加一和负数被拒绝；关闭加密的取消动作不保存；D 批每项输出均有真实消费者。CPU、吞吐、限速趋势与性能收益未测量。

### P05-06 · 完成 Android 路由与 DNS 策略

**开始条件**：P05-03 完成；P05-01c 已交付 IPv6 地址同步的接口清单或具体阻塞原因。

**修改位置**：`features/settings` 的当前配置入口、`features/configuration` 的 Android 分组、`lib/profile` 策略类型、`lib/vpn/TunPlatform.kt`、`Runtime.kt`、`VpnSessionCoordinator.kt` 及 native 地址/DNS 适配。

1. 应用范围使用 all/allowlist/denylist 互斥类型。all 不调用名单 API；allowlist 必须至少一个可见且已安装的包；denylist 可为空。名单中的包卸载或不可见时保留原字符串并阻止该名单策略应用，用户显式移除后再继续；不能静默扩大流量范围。
2. 互联网流量范围使用 overlay-only/exit-node。现有 includeInternet=false 转为 overlay-only；true 转为 exit-node 意图并保留已保存数据。若缺出口节点，显示“请选择出口节点”并阻止新应用，不把 true 改回 false。已经运行的旧配置不因保存模型迁移被主动停止。
3. custom DNS 必须至少一个合法 IP，按首次出现顺序去重；system DNS 读取本次选定底层网络的 LinkProperties，不能从 VPN 自身读取。没有上游返回 DnsUnavailable，禁止偷偷使用公共 DNS。
4. MagicDNS 开启时，组网域名进入核心分域处理，其他查询转交选定 system/custom 上游。P05-01 的来源记录必须定位这一接口；固定核心缺少分域入口时，记录需增加的宿主适配接口并实施，不能悄悄关闭 MagicDNS。若必须升级固定核心才能实现则将本任务设为 blocked，保留缺失符号及原因。
5. IPv6 分开保存底层传输开关、虚拟地址来源和 block/bypass/tunnel 策略。block 禁止 IPv6 绕过；bypass 仅在非 lockdown 时允许 Android 地址族绕过；tunnel 必须有当前代次的核心 IPv6 地址及路由，不能仅凭 ::/0 声称隧道可用。IPv6 tunnel 的 MTU 小于 1280 时拒绝应用，不静默调整用户 MTU。
6. 按 P05-01c 接入 ipv6_public_addr_auto：首次地址建立平台规格；变更时通过协调器受控重建 TUN；地址消失撤销旧规格并进入等待地址状态；旧代次事件丢弃；TUN 重建失败走实际错误/释放路径。完成后才加入 nativeExtensions 和可操作表单。
7. 完成 bind_device、dev_name、no_tun 的宿主运行覆盖，防止外部参数绕开 protect 或创建第二个 TUN。原文保留，预览显示覆盖 key。
8. autoReconnect 在本任务接入现有网络变化恢复：true 保留当前明确运行目标并在新底层网络可用后按现有流程恢复；false 在网络变化需要重建时停止并清除自动恢复意图，等待用户再次连接。P06-02 增加有限退避，P06-01 增加系统 Always-on 启动，不在本任务写空回调占位。
9. system DNS、底层 Network 或虚拟地址变化需要更新平台规格时走同一协调器，不从 UI 直接改 Builder。查看 B 的设置只编辑 B 草稿。

**失败处理**：先生成并校验完整策略，再创建 TUN；任何名单、DNS、地址或 MTU 错误都不得部分应用。普通模式 bypass 与未来 lockdown 的冲突在策略模型中保留显式错误；P06 负责传入真实系统状态。

**完成断言**：all 不提交名单；空 allowlist 拒绝、空 denylist 允许；缺包不降级；system 不读 VPN DNS；custom 去重保持顺序；组网/普通域名分别使用正确解析入口；IPv6 首次/变更/消失/迟到/失败五条序列有断言；autoReconnect=false 后推进网络事件不再发 connect。随后才开始 P05-04。

### P05-07 · 实现导入与兼容预览

**开始条件**：P05-02/03/06/04/05 全部达到完成断言，字段消费者已接入。

**修改位置**：`features/configuration` 新增系统文档导入动作和预览；共享 codec 与 `lib/profile` 增加批量原子导入方法。

1. 支持 UTF-8 TOML 单配置和本项目 JSON 配置包。配置包定义为 `format=chuanmen-profiles`、`schemaVersion=1`、`profiles` 列表，每项包含 localDisplayName、coreToml、androidPolicy、sourceCoreCommit；不运输本地 ID、核心实例 ID、Keystore 密钥、最后连接目标或运行状态。
2. TOML 上限 128 KiB，JSON 明文上限 8 MiB；读取每个块前累计字节并在超限时终止，不信任 URI 提供方的长度。解码使用严格 UTF-8，不能用替换字符悄悄修复。
3. 先解析到内存预览，不写仓库。逐项显示名称、已识别字段、未知/平台覆盖字段、错误和省略项；所有秘密遮蔽。语法错、未来 schema 或重复包成员键拒绝导入；可解析的未知核心字段允许保存但禁止运行。配置页提供“不支持的字段”列表，用户可逐 key 选择移除并确认后修改草稿，再显式保存；没有该动作时一直保留原文。移除仅影响所选配置，不提供任意执行原始 TOML 的旁路。
4. 默认全部新建，两种身份各生成新 UUID；重写运行实例身份时保留来源文档供预览，文件中的 instance_name 不成为内部句柄。覆盖必须用户逐项选择目标，保留目标 ID 和 CoreInstanceId；不按名称自动匹配覆盖。
5. 确认前计算合并后存储 JSON 大小；超过 P03 的 8 MiB 仓库上限时返回 StoreTooLarge，不通过删除已有配置腾空间。确认后将整批配置一次原子提交，任一项失败则整批不变。覆盖携带预览时捕获的 expectedRevision；预览后目标变化返回 Conflict 并要求重新预览，不部分导入。
6. 导入成功仅更新 saved；正在运行配置保持原 applied。返回/取消关闭输入流并丢弃预览，不更改会话。

**失败处理**：截断、读失败、语法错、未来 schema 和写入失败各有错误码；不把文件内容或原始异常写日志。JNI 运行请求另检查 256 KiB 上限，包能导入不保证每个配置能运行。

**完成断言**：超限第一个字节即停止读取；未来版本/重复键不写入；默认导入两次产生不同身份；覆盖冲突整批回滚；取消不改变列表/修订/会话；未知字段原文保存但应用返回 UnsupportedField。

### P05-08 · 实现导出、备份与迁移

**开始条件**：P05-07 的配置包、预览与批量原子导入已完成；P04-07 已提供只读日志流。

**修改位置**：配置导出界面及共享格式代码；native 新增 age 加解密适配，固定依赖到 Cargo.toml/Cargo.lock 并更新许可证；日志页连接 P04 导出接口。

1. 单配置可导出 EasyTier TOML，多配置导出 P05-07 的 JSON 包。TOML 不写 Android 字段。默认输出仅含已知可安全公开的字段；秘密和未知字段省略，并在预览及随附差异信息中列出 key，不用星号充当真实值。
2. 显式选择“包含秘密”后才允许完整 TOML；界面说明该明文文件含密码/私钥，用户再次确认才调用系统创建文档。完整备份使用 age v1 口令加密 JSON 包，不提供未经加密的完整多配置备份快捷动作。
3. 使用 Rust age 库的标准 passphrase 模式，精确固定版本及传递依赖；先做宿主标准格式互通/错口令测试，再接 JNI。口令不进仓库、Bundle、日志或磁盘；明文只在内存流处理，不写临时文件。加解密使用新增的分块 JNI 接口 begin/update/finish/cancel，每块原始数据最多 64 KiB；返回独立加密任务句柄，由导入导出操作拥有，完成/取消/异常均释放，不能复用 VPN session 句柄。每次 JSON/字节桥请求仍遵守 256 KiB 上限，不把 8 MiB 配置包塞入一次运行协议请求。解密输出在累计上限内暂存内存，只有 finish 确认完整性后才交给导入预览。
4. 补齐 P05-07 的 age 输入分支：加密输入最多 9 MiB，解密输出最多 8 MiB；先完成格式和口令校验，再进入相同预览与原子导入流程。不因解密成功自动保存或连接。
5. 系统文档输出在写入和关闭都成功后显示成功。发生错误或取消时尝试删除本次新建的未完成目标；删除失败提示用户目标可能残缺，不重试覆盖其他 URI。
6. 日志导出选择起止时间、来源和可选会话；过滤区间采用开始含、结束不含。只导出 P04 已脱敏记录，再执行秘密检查；不附完整配置、不自动上传。

**失败处理**：空口令拒绝启动完整备份；错口令、损坏 age、超限、取消、写入或关闭失败均不反馈成功；不保留可供以后“自动重试”的口令或明文缓存。

**完成断言**：默认导出不含合成秘密/未知字段且差异 key 完整；显式完整加密往返保留原始 TOML 未知内容与 Android 策略；错口令不写仓库或临时明文；UTF-8、64 KiB 分块、256 KiB 桥请求、9/8 MiB 总量边界和目标关闭失败有断言；半途取消后加密句柄计数归零；日志日期边界无重复/漏跨界记录。系统文件提供方的设备行为未验证。

## 5. 阶段交付与退出

P05-01 至 P05-08 的完成断言全部通过，44 项首批实现、peer 及 P03 三项 Android 策略扩展均有工程证据，8 个隐藏项均有源码决定且适用扩展完成；再运行 [工程检查组合](../development/setup.md#工程阶段检查组合)，保留配置语义、失败及关闭的测试定位。记录每项编译 feature、Android 宿主适配及设备效果未知，不能把“解析通过”写成实机互操作。

P06 接收稳定字段/策略、系统恢复目标及日志；P07 接收格式/来源/限制说明。适用项仍阻塞时 P05 保持未完成，范围缩减需明确另行决定。设备验收取消不构成实现范围缩减。
