# P04 · 总览、设备、实时流量与双来源日志

实施依据：2026-10-23 计划；状态以 [路线图](ROADMAP.md) 为准。前置为 P03 工程退出。只进行工程验收，不运行设备、人工 TalkBack 或真实网络探测验收。

## 1. 目标、已有实现与边界

当前已有 CoreSnapshot、Service 采样、真实 native TUN 计数、TunTrafficHistory 的 60 秒窗口、总览/设备投影和生命周期/核心诊断两种来源。P04 复用这些基础，补齐指标、详情、按需探测、日志存储及完整页面交互，不新建第二个 TUN 消费者或采样器。

UI 从同一仓库和会话源读取。所有观测绑定 sessionId、观测时间、新鲜度与来源；不可用不等于零，控制面在线不等于业务可达。静态 target/code 日志目前不是完整可读日志，这部分按 P04-07 扩展。

## 2. 数据与资源契约

| 契约 | 必须表达的内容 | 所有者 |
|---|---|---|
| 设备快照 | 稳定 peer 身份、名称、地址、路径、版本、NAT、连接统计及缺失原因 | lib/vpn、app/native |
| 计量值 | 来源范围、方向、单位、session/attachment、单调时间、新鲜度 | 现有 Service/native 计量路径 |
| ProbeResult | 目标、会话、开始时间、请求/成功数、RTT、超时/取消/不可用原因 | lib/vpn 的后台探测任务 |
| LogRecord | 递增序号、时间、来源、级别、静态模块、可选会话归属、安全消息 | 服务侧统一采集，日志功能消费 |
| 日志查询 | 来源/级别/关键词/会话过滤、稳定游标、丢弃计数 | features/logs |

字节和包计数使用精确整数，速率仅在展示计算阶段使用浮点。核心进程级日志不强行归入当前配置。新增 JNI 字段通过能力标识读取；旧/缺失字段保持 unavailable，不能让 UI 私自解析核心对象。

日志初始预算固定为内存最多 2,000 条、磁盘每文件 2 MiB、最多 5 个文件、保留最多 7 天；任一上限触发淘汰。单条记录最多 8 KiB，安全字段截断后才入队，磁盘在写入前轮转。脱敏先于缓冲和磁盘，导出再检查。达到容量丢弃并累加计数，不阻塞数据面。日志位于应用私有非备份目录。

## 3. 分任务

按 P04-01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 执行。Kotlin 路径相对 `app/src/main/java/com/github/zzwtsy/chuanmen`，测试放入 test 镜像包；Rust 修改位于 `app/native/src`，宿主测试位于 `app/native/tests`。已有计量算法和会话源继续使用，不从零重写。

### P04-01 · 建立可观测数据契约

**开始条件**：P03 已交付稳定 profileId、saved/applied 和唯一会话源；现有 CoreSnapshot 解码测试通过。

**修改位置**：`lib/vpn/CoreSnapshot.kt`、`BridgeProtocolCodec.kt`、`Runtime.kt`，native 的 `session.rs`、`protocol.rs`。

1. 为设备名称、虚拟地址、NAT、版本、连接数、路径、链路收发和时延建立映射表，写入技术架构“设备与路径指标”一节。每行填写固定核心路径/符号、单位、统计对象、本机或远端、累计或瞬时、缺失原因。
2. 优先使用已有 snapshot/route/peer；新增桥字段通过 capability 标识读取。没有来源的字段返回 Unsupported，不用本机 NAT、邻居流量或版本猜测远端值。
3. 每个采样携带 sessionId、attachment（适用时）、单调时间和数据状态。设备状态沿用 Service 的 DataFreshness 规则；不在各 ViewModel 重新设置另一套超时。P04-06 的探测只接受 Fresh。
4. u64 跨 JNI 以十进制字符串传输。累计计数保持整数；显示速率时才转浮点，超过 Kotlin 现有类型范围必须返回明确不可表示状态或使用无损类型，不截断为负数。
5. 旧会话和不匹配 attachment 的回执在共享投影处拒绝；UI 只能接收过滤后的数据。

**失败处理**：字段缺失/未知枚举只使对应指标不可用，不清空其他有效指标；整包损坏报告解码失败并把旧快照标旧数据，不能继续标 Fresh。

**完成断言**：缺 NAT 显示不可用；未知枚举不崩溃；u64 最大值不变负数；旧 session 不覆盖当前；中继路径不显示伪造的端到端吞吐。映射表每一项有来源或具体 Unsupported 原因。

### P04-02 · 合并首页虚拟网络信息

**开始条件**：P04-01 的数据契约及过滤已通过测试。

**修改位置**：`features/overview/OverviewUiState.kt`、`OverviewViewModel.kt`、`OverviewScreen.kt` 与根设备页导航。

1. 虚拟网络卡片集中显示当前查看配置名称、实际会话状态、虚拟地址和在线远端数量；只有查看 ID 等于运行 ID 时显示该会话实时数据。
2. 在线远端按唯一 peerId 去重并排除本机，不能把连接条数当设备数。Fresh 的路由/连接关系用于路径摘要；缺失时显示未知，不填直连 0 代替。
3. 没有远端但核心/TUN 就绪时显示“等待设备”，不改成连接失败。旧会话地址如需保留必须标“上次地址”，禁止复制或作为当前地址发起探测。
4. 点击设备摘要仅导航到同一查看配置的设备页；不发送 connect、apply 或 stop。删除独立重复的在线卡片，首页不出现流量范围切换。

**失败处理**：结果未知时显示待确认状态，连接动作遵循 P03-06；不能因为 peer 列表为空发起额外重连。

**完成断言**：本机加两个远端显示在线远端 2；一个 peer 有两条连接仍计 1；查看 B 时不显示运行 A 的实时地址；进入设备页的会话命令数为 0；历史/缺失地址复制禁用。

### P04-03 · 实现真实 TUN 计量

**开始条件**：P04-02 完成；保留现有 TunTrafficHistory 与 native 计数测试。

**修改位置**：native `session.rs` 的 TUN 统计接线、`lib/vpn/TunTrafficHistory.kt`、Service 的既有采样投影。

1. 上传只累计成功读取 TUN 的 IP 字节，下载只累计成功写回 TUN 的 IP 字节；失败读写不增加计数。不增加第二个 TUN 读取者，不把核心保活或 peer 外层字节加到首页。
2. 复用现有差分条件：相同 attachment、计数非递减、采样间隔 500–2000 ms 时生成速率。速率为字节差乘 1000 再除毫秒差。
3. attachment 更换清空基线和窗口；缺计数清除差分基线；计数回退、零间隔或超时点记为 null，不计算负值或将其写成 0。
4. 核对计数溢出路径，沿用饱和或显式不可用规则并补测试；不得在 Kotlin 再累计相同 native 数据。

**失败处理**：采样失败不停止正常会话；只将该点标缺测并记录安全错误。下一次有效计数先建立新基线，不能跨失败间隔生成尖峰。

**完成断言**：同代次一秒增加 1024 字节得到 1024 B/s；零增长得到真实 0；缺测后首点为 null；attachment 更换与回退均不产生负速率；宿主读写失败的字节计数不增加。

### P04-04 · 实现固定 60 秒趋势图

**开始条件**：P04-03 的同代次计量和缺测规则完成。

**修改位置**：`TunTrafficHistory.kt`、`features/overview/OverviewScreen.kt` 及其实际使用的功能内图表组件。

1. 继续按一秒槽保留最近 60 秒，重复槽替换旧点；窗口内漏采样插入 null；启动前不存在的历史不补点。UI 按点时间定位，不把缺测两端连线。
2. 同时绘制上传和下载，使用固定方向语义及文字单位；当前速率与图表来自同一数据源。显示 B/s、KiB/s、MiB/s，按 1024 换算。
3. 纵轴上限至少 1 B/s；峰值超上限立即扩至峰值；峰值低于上限一半连续 10 个有效采样后，每个有效点将上限乘 0.9，最低为窗口峰值或 1。峰值重新达到一半则清零缩小计数，缺测不推进计数。
4. 页面不可见时停止 UI 高频收集/动画，Service 的会话采样继续；返回时直接显示当前窗口，不重放后台动画。
5. 不增加拖动读值、时间选择、详情、暂停或图例开关；无障碍语义暴露当前上下行文本，不设置逐秒 live announcement。

**失败处理**：全部缺测显示无数据状态，真实 0 显示零线；纵轴计算遇到非有限数时丢弃该展示值并保留缺测，不让 Canvas 崩溃。

**完成断言**：61 个连续秒槽只保留后 60 个；中间 null 留断口；上限缩小的第 9/10 个有效点和缺测点行为确定；切换 attachment 清旧窗口；语义树没有被禁止的交互入口。

### P04-05 · 集中设备信息与操作

**开始条件**：P04-04 完成，P04-01 已提供每项设备指标来源。

**修改位置**：`features/devices` 的 Screen/UiState/ViewModel、`features/devices/components/DeviceCard.kt` 和共享 `components/CopyIpButton.kt`。

1. 列表以 sessionId+peerId 为稳定身份，显示名称、虚拟 IP、直连/中继/未知路径；原地展开连接摘要。点击详情只打开一个详情面，禁止逐指标跳转多级页面。
2. 详情显示 P04-01 有来源的 NAT、版本、连接与统计；缺失、过期和不支持分别显示说明。端到端丢包只来自 P04-06 的该次探测，不从邻居计数估算。
3. 列表和详情复用复制动作；只有当前有效地址可复制。剪贴板写入成功才反馈；API 33+ 使用系统反馈，不再叠加 App Snackbar。
4. 返回列表保留滚动位置和展开身份；会话切换关闭旧详情并取消其探测；peer 消失时保留已打开详情但标离线/旧数据，禁用探测。
5. 不提供“相关日志”按钮或按设备导航日志的隐式动作。

**失败处理**：剪贴板调用失败显示短错误，不提示成功；找不到 peer 时不得用相同列表下标的另一个设备替代。

**完成断言**：无地址禁用复制；平台写入失败不发成功反馈；旧 session 回执不刷新新详情；返回保留位置；peer 消失禁用探测；界面无相关日志路由。

### P04-06 · 实现按需连接探测

**开始条件**：P04-05 提供当前详情身份与取消生命周期。

**修改位置**：在 `lib/vpn` 新增 ICMP datagram 适配与任务管理，`features/devices` 增加显式探测动作及 ProbeResult 投影。

1. 只接受当前 Fresh 会话中已知 peer 的虚拟 IPv4；检查本 App 未被 VPN 应用名单排除且目标路由属于 VPN。条件不足返回 Unavailable，不自动连接、不临时改路由。
2. 用后台 ICMP datagram socket 发 4 个串行 echo 请求，每个发送后最多等待 1 秒；不使用 shell、Root、外部进程或 protect 到物理网。匹配目标、标识和序号，忽略不匹配包。
3. 全局只允许一个任务。第二次触发时返回 Busy 并保留原任务，不创建第二个 socket；用户取消后才可重新发起。
4. 统计 sent、received、每次 RTT；完成后 loss=(sent-received)/sent。sent=0 时丢包为未知；取消时展示部分结果并标取消，不包装成完整四次探测。
5. 离开详情、切换会话、目标失去 Fresh 或显式取消立即关闭 socket；迟到响应不得更新新任务。socket 权限错误或系统不支持时返回 Unavailable，不把 peer 标离线。

**失败处理**：send 失败不增加 sent；已经发送后超时增加未回复数。任何异常都走 finally 关闭资源，错误只带分类而非网络凭据。

**完成断言**：传输适配器测试覆盖 4/4、0/4、1/2 后取消、发送失败、Busy、错误序号与旧代次；每条结束路径 socket 关闭一次；Android 适配编译通过。JVM 替身不进入 main，不声称 Android ICMP 实测通过。

### P04-07 · 建立双来源日志管道

**开始条件**：P04-06 完成；P04-01 的安全字段及来源可复用。

**修改位置**：native `diagnostics.rs`、桥读取接口；共享 `lib` 下新增有实际消费者的日志采集/存储接口；`features/logs` 实现筛选及 UI。

1. 将现有 core diagnostics 与生命周期事件转换为 LogRecord；在单一汇聚点分配进程内递增序号。旧文件记录身份使用文件 ID+序号，不能与重启后的序号冲突。
2. 默认采集 Info/Warn/Error；Debug 必须显式开启。显示级别/关键词筛选只过滤已有记录，不能更改 native 采集等级。核心进程级事件的 session 为空，不强行归到当前网络。
3. 消息采用静态 code 到中文文本映射，只允许明确列出的安全字段。任意原始消息、配置、URI 凭据及异常原文不入队；提高等级也不放开该限制。
4. 内存环保留最近 2000 条，满时淘汰最旧条并增加 dropped；生产者不等待磁盘。每条 UTF-8 JSONL 记录包含换行在内最多 8 KiB，只截断安全消息字段并标 truncated，不能截断成非法 JSON。
5. 磁盘单文件写入后不得超过 2 MiB，超限前轮转；总计最多 5 文件，删除最早创建文件；超过 7 天按文件创建时间淘汰。启动和每次轮转时清理，系统时间回拨不使文件立即过期。文件写入失败进入 memory-only 并显示持久化错误，继续采集；用户重试后恢复磁盘写入，不无限缓存待补写记录。
6. 筛选按来源、最低级别、关键词和可选会话共同匹配；关键词只搜索安全消息。上滑时固定首个可见记录身份，新记录增加计数；点击新记录提示后滚到末尾并恢复跟随。锚点被淘汰时提示旧记录已清理并定位现存最早记录。
7. 为 P05-08 提供按筛选结果只读流式导出接口；不在本任务中上传日志或导出完整配置。

**失败处理**：安全字段转换失败丢弃该条并计数；磁盘错误不传播至 VPN 数据面；取消查询/导出关闭文件流，不取消采集器。

**完成断言**：第 2001 条到达后只有最近 2000 条且 dropped+1；8 KiB/2 MiB 边界不超限且 JSON 可解析；第 6 文件删除最旧；7 天边界和时钟回拨确定；合成秘密不存在于内存、文件、导出；上滑后新增记录不跳动，锚点淘汰有提示。

### P04-08 · 完成原生体验核对

**开始条件**：P04-01 至 P04-07 均达到完成断言。

**修改位置**：根导航与五个功能的 Screen/UiState；相关 Compose JVM 测试及本文件实施记录。

1. 用 320 dp/600 dp 宽度、100%/200% 字体、浅色/深色共 8 个组合检查主要操作可通过滚动访问，文本不遮挡操作区。测试使用 JVM，不能写成真机适配通过。
2. 检查返回顺序：详情返回列表、编辑返回原页面、对话框取消不写入；导航往返保留草稿、持续错误和查看配置身份。
3. 成功操作使用短反馈；保存失败、会话未知和日志写盘失败持续可见，直到成功重试或用户处理。不同页面统一标“当前查看”与“正在运行”。
4. 执行工程检查组合；在本文件登记每个任务的代码/测试路径及结果，并将缺失源码接口导致的未实现项列为阻塞。

**失败处理**：指标暂时无样本属于正常 unavailable；承诺指标没有生产接线属于未完成，两者不可混用。后者阻止阶段结束。

**完成断言**：8 个布局组合和各任务断言通过；实现、测试和对应字段来源可定位；没有假数据、第二采样器或测试替身进入 main。TalkBack、系统栏及真实网络行为未验证。

## 4. 交付归属与验收

共享快照、平台探测和采样位于 lib/vpn，原生映射位于 app/native；页面和日志查询/存储位于对应 features。跨功能实际复用的日志采集契约进入 lib，不依赖日志 ViewModel。测试按现有 test/native tests 归属，不建立旧 contracts/telemetry、core-bridge 或独立 vpn 模块。

阶段验收关注字段来源、查看/运行隔离、零/缺测、日志保密和容量、探测取消与用户可见行为。P05 消费这些观测契约，不改变本机 TUN 口径。阶段退出为 verified（工程范围），网络与设备效果未验证。

## 5. 实施记录（2026-10-25）

实施基于 `main` 的 `f16cb70b06f9473332a4dd01156744f23d2afbff`，本地任务分支为 `feat/p04-observability`；该 SHA 是基线，不是本次实现提交。生产继续使用唯一 Service/JNI 核心、配置仓库和客户端，没有新增 TUN 读取者或页面采样器。固定 EasyTier commit、Rust/NDK 与 ABI 保持不变，补丁身份为 `p04-observability-host-v1`；新直接依赖 libc 使用锁中已有的 0.2.189。

下表 Kotlin 生产路径相对 `app/src/main/java/com/github/zzwtsy/chuanmen`，Kotlin 回归在 `app/src/test/java/com/github/zzwtsy/chuanmen` 的对应包，Rust 路径相对 `app/native`。

| 任务 | 实现与接口 | 正式回归及可观察结果 |
|---|---|---|
| P04-01 | `lib/vpn/CoreSnapshot.kt`、`BridgeProtocolCodec.kt`、`Session.kt`；`src/session.rs`、`src/protocol.rs`。固定来源表见技术架构 8.2，新增 `observability-v1`；连接 u64 使用 BigInteger，TUN 超 Long 显式 unavailable | `ObservabilityContractTest` 验证 u64 最大值、超 Double 精度、单字段错误、未知 NAT/borrow、旧能力及错误归属；`VpnSessionCoordinatorTest` 验证旧 attachment、失败及超过 2 秒的查询不冒充 Fresh、不并行积压查询 |
| P04-02 | `features/overview/Overview{UiState,ViewModel,Screen}.kt`、根设备导航。查看名称、状态、地址、去重远端数及路径摘要集中展示；平台旧地址不可复制 | `OverviewViewModelTest` 验证本机与重复连接排除、中继远端计数、查看/运行隔离及导航零会话命令；`P04AddressFreshnessTest` 验证两个页面共享地址过期规则 |
| P04-03 | `host/android_tun.rs` 的成功 I/O 判断、`patches/session-host.diff` 普通/向量路径；`lib/vpn/TunTrafficHistory.kt` 和唯一 Service 查询 | `tests/tun_contract.rs` 验证失败读写及 Pending 不计量、整数饱和、复用 fd 不复用 attachment；`TunTrafficHistoryTest` 验证 1024 B/s、实测零、缺测切基线、回退与 attachment 重置 |
| P04-04 | `features/overview/components/TrafficTrend.kt` 使用实际时间坐标、缺测断线、实线/虚线、当前速率语义；纵轴由 Service 的 `TrafficScale` 推进 | `TunTrafficHistoryTest` 验证同槽替换、61 秒窗口、第 9/10 有效点与缺测；`TrafficTrendTest` 验证绘图坐标、断口、非有限值和零线；8 组合语义测试不提供额外交互 |
| P04-05 | `features/devices` 的稳定身份、展开摘要与单一详情面；共享 `components/CopyIpButton.kt` 成功后才反馈，API 33+ 使用系统反馈 | `DevicesViewModelTest` 验证指标归属、peer 消失保留旧详情并禁用操作、会话变化关闭详情；`CopyIpButtonTest` 注入平台写入失败，只有错误提示；`P04ExperienceTest` 验证返回列表位置及无相关日志入口 |
| P04-06 | `lib/vpn/ConnectionProbe.kt`、`AndroidIcmpTransport.kt`；`src/probe.rs` 同步设置 ICMP 非阻塞；Application 唯一任务，设备详情消费 | `ConnectionProbeTest` 的 8 项回归覆盖 4/4、0/4、1/2 后取消、发送失败、Busy、资格核对、错误响应、旧任务隔离和一次关闭；Rust 实际 datagram 验证非阻塞与借用所有权；Android 适配通过 lint/编译，未运行真实探测 |
| P04-07 | `lib/logging/LogRecord.kt`、`LogCollector.kt`；`features/logs/RotatingLogStore.kt`、日志 ViewModel/Screen；`src/diagnostics.rs` 静态安全 sink、显式 Debug、事件时间 | `LogCollectorTest` 验证 2000 条、丢弃、写盘故障/重试、Debug、秘密拒绝、8 KiB 截断和取消导出；`RotatingLogStoreTest` 验证 2 MiB/5 文件/7 天/回拨及重启身份；`LogsViewModelTest`、`LogsReadingUiTest` 验证组合筛选、稳定阅读、新记录提示、淘汰定位；native 验证原始消息/字段始终不输出 |
| P04-08 | 根 `ChuanmenApp.kt` 保留每页滚动状态并让日志独立使用有界 LazyColumn；`ChuanmenViewModel.kt` 保留策略编辑返回来源；配置草稿保存失败持续展示 | `P04ExperienceTest` 的 16 项覆盖 320/600 dp、100%/200% 字体、浅/深色共 8 组合；`NavigationBehaviorTest`、既有 `NavigationUiTest` 验证返回、取消和草稿/查看身份；`P04SaveFailureTest` 验证错误跨查看对象往返保留，成功重试才清除且不应用 |

正常缺测、固定核心没有端到端计量来源和平台探测拒绝有明确 unavailable；路由代价不改称 RTT，中继远端不借下一跳流量。P04 没有尚未接线的承诺字段；应用名单扩展和用户导出入口继续归 P05。日志只提供只读流式导出接口，没有上传或完整配置导出。

### 工程检查与范围

完整命令、基线 HEAD、工作区摘要、开始/结束时间、退出码及相对日志路径保存于忽略的 `app/build/p04-checks/*.json`，属于普通工程产物，不发布或冻结为新的实验包。最终结果登记在本节下表。

实现摘要为 `cd538bb85e90197416d777366a4b4f5f8aef8217f2a7783eda2cc93d7cac37de`，清单为 `app/build/p04-checks/implementation-files.json`，覆盖 app、scripts、Gradle、CI 和根构建输入；结果回填只修改文档。JVM/Compose、Python、最终 APK 和静态产物检查的工作区摘要为 `f406f2aba4a75a693fc97ec3c3e76b18b719c5dc19ef68e0fb4e0b41bf559d44`；native/JNI 使用各自记录中的摘要，其受测 native 源码与最终实现一致。

- JVM/Compose：228 项通过，无失败、错误或跳过，其中 16 项覆盖 8 个布局组合；真实宿主 JNI：5 项通过。
- 核心 Rust：20 项单元、1 项 runtime、4 项 TUN 契约通过；受控对端只运行 4 项宿主单元测试。
- Python：隐私 6 项、目录 10 项、原生工具 9 项、Android 工具 40 项，共 65 项通过；固定 Ruff、隐私和目录审计通过。
- debug/release lint、debug/release APK 与 androidTest APK 编译通过；实际双 ABI 库字节、ELF LOAD 和 APK ZIP 的 16 KB 静态对齐通过。

下表时间均为 2026-10-24 UTC（北京时间 2026-10-25），退出码全部为 0。`SDK_ROOT` 是本机 SDK 路径的脱敏占位；原生命令及 APK/library SHA-256 另见本地 `summary.json`。

| 命令 | 开始 → 结束（UTC） | 退出码 | 日志 |
|---|---|---|---|
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check scripts` | 18:06:38 → 18:06:39 | 0 | `app/build/p04-checks/static-1.log` |
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --check scripts` | 18:06:39 → 18:06:39 | 0 | `app/build/p04-checks/static-2.log` |
| `python3 scripts/check_privacy.py` | 18:06:39 → 18:06:40 | 0 | `app/build/p04-checks/static-3.log` |
| `python3 scripts/catalog/audit_config_catalog.py` | 18:06:40 → 18:06:40 | 0 | `app/build/p04-checks/static-4.log` |
| `python3 scripts/test_check_privacy.py` | 18:06:40 → 18:06:40 | 0 | `app/build/p04-checks/static-5.log` |
| `python3 -m unittest discover -s scripts/catalog -p 'test_*.py'` | 18:06:40 → 18:06:41 | 0 | `app/build/p04-checks/static-6.log` |
| `python3 -m unittest discover -s scripts/native -p 'test_*.py'` | 18:06:41 → 18:06:41 | 0 | `app/build/p04-checks/static-7.log` |
| `python3 -m unittest discover -s scripts/android -p 'test_*.py'` | 18:06:41 → 18:06:44 | 0 | `app/build/p04-checks/static-8.log` |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease --offline` | 18:06:01 → 18:06:48 | 0 | `app/build/p04-checks/jvm-1.log` |
| `cargo +1.99.0 test --manifest-path app/native/Cargo.toml --locked --offline --target-dir app/build/native/target` | 17:59:04 → 17:59:10 | 0 | `app/build/p04-checks/native-1.log` |
| `cargo +1.99.0 test --manifest-path scripts/android/native-peer/Cargo.toml --locked --offline --target-dir app/build/device-tests/peer-target` | 17:59:10 → 17:59:33 | 0 | `app/build/p04-checks/native-2.log` |
| `./gradlew :app:testHostNativeCodec --offline` | 17:59:41 → 17:59:45 | 0 | `app/build/p04-checks/jni-1.log` |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline` | 18:09:09 → 18:09:49 | 0 | `app/build/p04-checks/apk-1.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/debug/app-debug.apk` | 18:10:20 → 18:10:20 | 0 | `app/build/p04-checks/artifacts-1.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/release/app-release-unsigned.apk` | 18:10:20 → 18:10:20 | 0 | `app/build/p04-checks/artifacts-2.log` |
| `python3 scripts/native/build_native.py verify-artifacts` | 18:10:20 → 18:10:20 | 0 | `app/build/p04-checks/artifacts-3.log` |
| `cargo +1.99.0 fmt --manifest-path app/native/Cargo.toml --check` | 18:10:20 → 18:10:20 | 0 | `app/build/p04-checks/artifacts-4.log` |
| `git diff --check` | 18:10:20 → 18:10:20 | 0 | `app/build/p04-checks/artifacts-5.log` |


设备、模拟器、人工 TalkBack、系统栏、Android ICMP 权限、真实 VPN 网络行为、release 冒烟及 16 KB runtime 均未执行或验证。androidTest 仅编译。JVM 替身、故障注入和宿主 datagram 不构成设备效果结论；冻结记录、目录快照、handoff.json 和 SHA256SUMS 保持原字节。

## 6. 复审修复与结构优化（2026-10-25）

本次基于 `feat/p04-observability` 的 P04 暂存工作区继续实施，基线 HEAD 为 `f16cb70b06f9473332a4dd01156744f23d2afbff`。第 5 节保留当时的检查记录；以下修复覆盖复审新增的负向条件，不把原有通过结果解释为这些条件已经验证。

### 接口与行为

- 保留桥协议版本 1、配置存储格式和固定 EasyTier commit。补丁身份更新为 `p04-observability-host-v2`，追加 `observability-v2`，保留 v1 能力；固定工具链与锁定依赖不变。
- native 只输出成员、中继、未知角色；网络名用于内部归属比较，不跨桥。路由成员与已确认同网活动邻居去重后计在线数，排除本机；设备页单列公共中继，未知连接说明统计范围。
- 两份 `PeerConnStats` protobuf 追加 optional `latency_valid` 字段 6；`WindowLatency` 完成样本后发布有效标志。无样本与实测零可区分，旧桥无 v2 不推测 RTT。
- `SnapshotPeerIndex` 对每份快照缓存只读索引，`SessionObservation` 统一查看对象、核心新鲜度及平台探测资格。总览、设备和探测复用相同事实，不增加采样任务。
- `DevicesReducer` 统一 `Closed / Current / Stale` 详情迁移。结果归属及取消均核对完整目标与 taskId；旧详情可保留取消后的部分结果，并标明历史目标。
- 日志采用 `Following / Reading / Evicted`；筛选进入暂停阅读，像素偏移由 LazyListState 持有。页面不可见时停止投影缓冲，返回后按最新记录恢复边界。过滤隐藏不算清理，新日志计数相对未筛选缓冲的暂停边界计算。
- 结构化核心事件和通用 tracing 进入同一有界 sink；事件描述表统一安全 code、来源、等级、模块及允许字段。旧通用 LogCode 与 JSONL 保持兼容，未知事件拒绝并计数。
- `ObservationQueryState` 聚合在途 token、开始时间及 watchdog；满 2 秒只推进缺测，不释放查询资格；终态或停止才清理，迟到结果不标 Fresh。

### 任务与针对性回归

| 任务 | 实施位置与操作 | 失败后的状态 | 输入、操作与结果断言 |
|---|---|---|---|
| P04-R01 | 固定核心和 native 输入可用后，先添加角色与 RTT 有效性，再统一桥解码和共享索引，最后同步维护补丁及前后像摘要 | 未知角色不猜测；缺测、非法单指标不伪造零；来源不匹配阻止构建 | `SessionObservationTest` 验证仅公共中继时成员数为 0、仍等待成员；重复连接与间接路由正确计数；`ObservabilityContractTest` 和真实 `WindowLatency` 宿主测试区分缺测、实测零、旧能力与非法字段 |
| P04-R02 | 共享观测可用后，用纯 Reducer 替换 combine 中的状态写入，绑定本页任务，再接入成员/中继分区 | 目标变化隔离旧结果；设备消失保留只读旧详情；启动失败显示原因 | `DeviceProbeOwnershipTest` 分别更换 IP、attachment 及二者，旧 Complete 均不显示；1/2 后设备消失保留 Cancelled 历史结果；重开详情和切换查看对象不继承任务；`ConnectionProbeTest` 验证旧 taskId 不能取消同目标的新任务 |
| P04-R03 | 有非空混合来源复现后，显式建模阅读状态，再将像素偏移交给 UI，最后区分过滤与真实淘汰 | 空结果仍可筛选；筛选、写盘错误不恢复跟随或改变采集等级 | `LogsReadingUiTest` 使用真实 ViewModel 连续筛选、输入、切会话及追加日志，输入保持焦点和可见；原位置/像素断言继续保留；`LogsViewModelTest` 验证过滤边界不误算新日志或淘汰，并验证取消订阅后缓存不变、恢复订阅读取最新缓冲 |
| P04-R04 | 确认结构化订阅入口后，在实例启动前订阅，再映射静态安全事件，最后由工厂校验元数据与数字字段 | 订阅不可用保留通用安全诊断；写盘失败仅保留内存；停止确认后等待订阅退出 | Rust 测试验证不同失败 code、秘密不输出、滞后计数和 receiver 回收；`SafeLogEventFactoryTest` 覆盖 17 种结构化 code、中文搜索、非法元数据/字段拒绝、旧文件读取与安全导出；原有预算、Debug、轮转及取消导出回归保留 |
| P04-R05 | 既有协调器回归可运行后，先封装查询状态，再统一终态和停止清理，保持既有 TUN 协调 | 超时维持单在途并推进缺测；旧回执不恢复状态或资源所有权 | `VpnSessionCoordinatorTest` 验证 1999/2000ms 边界、无回执时每秒缺测且不重复提交、停止后的旧查询与 watchdog 不再生效；原有 TUN 收据丢失和关闭顺序回归保留 |
| P04-R06 | 定向回归通过后，执行完整工程检查，再同步当前契约和修复记录，最后更新路线图 | 必需检查缺失或失败时维持进行中，不把跳过计为通过 | `P04ExperienceTest` 新增八组合非空日志筛选、中继分区、只读详情及导航返回；阶段退出须有全部工程检查的实际命令、源码摘要和退出码 |

### 工程检查

本轮命令、源码基线、各次工作区摘要、时间、退出码及日志保存在忽略的 `app/build/p04-remediation-checks/`，汇总为 `summary.json`，源码清单为 `implementation-files.json`。这些是普通构建产物，不生成或冻结实验包。各检查使用自己的工作区摘要；最终核对受测 Kotlin、native 及固定上游后像与交付代码一致。运行过程中的编译失败和缓存只读限制保留原记录，后续通过不覆盖原尝试。

- JVM/Compose：251 项通过，无失败、错误或跳过；相对原 228 项增加 23 项，八组合交互用例由 16 项增至 24 项。
- 核心 Rust：24 项单元、1 项 runtime、4 项 TUN 契约，共 29 项通过；受控对端仅运行 4 项宿主单元测试。
- 真实宿主 JNI：5 项通过；Python：隐私 6、目录 10、原生工具 9、Android 工具 40，共 65 项通过。
- 固定 Ruff、隐私与目录审计、双 variant lint 通过；debug/release/androidTest APK 构建通过。
- 当前维护补丁对 14 个干净固定原文件应用后，前后摘要全部一致；实际双 ABI 库字节及 ELF/APK ZIP 的 16 KB 静态对齐通过。

下表时间均为 2026-10-24 UTC（北京时间 2026-10-25），退出码全部为 0；`SDK_ROOT` 为已安装 SDK 的脱敏占位。完整命令参数与摘要见各记录。末次文档检查及原暂存区、冻结资料字节核对另见汇总。

| 命令 | 开始 → 结束（UTC） | 退出码 | 日志 |
|---|---|---|---|
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check scripts` | 19:18:10 → 19:18:11 | 0 | `app/build/p04-remediation-checks/static-1.log` |
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --check scripts` | 19:18:11 → 19:18:11 | 0 | `app/build/p04-remediation-checks/static-2.log` |
| `python3 scripts/check_privacy.py` | 19:18:11 → 19:18:11 | 0 | `app/build/p04-remediation-checks/static-3.log` |
| `python3 scripts/catalog/audit_config_catalog.py` | 19:18:11 → 19:18:11 | 0 | `app/build/p04-remediation-checks/static-4.log` |
| `python3 scripts/test_check_privacy.py` | 19:18:12 → 19:18:12 | 0 | `app/build/p04-remediation-checks/static-5.log` |
| `python3 -m unittest discover -s scripts/catalog -p 'test_*.py'` | 19:18:12 → 19:18:12 | 0 | `app/build/p04-remediation-checks/static-6.log` |
| `python3 -m unittest discover -s scripts/native -p 'test_*.py'` | 19:18:12 → 19:18:12 | 0 | `app/build/p04-remediation-checks/static-7.log` |
| `python3 -m unittest discover -s scripts/android -p 'test_*.py'` | 19:18:13 → 19:18:15 | 0 | `app/build/p04-remediation-checks/static-8.log` |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease --offline` | 19:27:01 → 19:27:38 | 0 | `app/build/p04-remediation-checks/jvm-final-2.log` |
| `cargo +1.99.0 test --manifest-path app/native/Cargo.toml --locked --offline --target-dir app/build/native/target` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/native-2.log` |
| `cargo +1.99.0 test --manifest-path scripts/android/native-peer/Cargo.toml --locked --offline --target-dir app/build/device-tests/peer-target` | 19:12:19 → 19:13:51 | 0 | `app/build/p04-remediation-checks/peer-1.log` |
| `./gradlew :app:testHostNativeCodec --offline` | 19:20:02 → 19:20:05 | 0 | `app/build/p04-remediation-checks/jni-1.log` |
| `python3 app/build/p04-remediation-checks/verify_patch.py` | 19:29:43 → 19:29:44 | 0 | `app/build/p04-remediation-checks/patch-rebuild-2.log` |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline` | 19:29:49 → 19:30:26 | 0 | `app/build/p04-remediation-checks/apk-1.log` |
| `python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/debug/app-debug.apk` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/artifacts-1.log` |
| `python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/release/app-release-unsigned.apk` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/artifacts-2.log` |
| `python3 scripts/native/build_native.py verify-artifacts` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/artifacts-3.log` |
| `cargo +1.99.0 fmt --manifest-path app/native/Cargo.toml --check` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/artifacts-4.log` |
| `git diff --check` | 19:32:05 → 19:32:05 | 0 | `app/build/p04-remediation-checks/artifacts-5.log` |

设备、模拟器、真实 VPN 网络、Android ICMP 权限、人工 TalkBack、release 冒烟和 16 KB runtime 不安排执行，androidTest 仅编译；以上结果不证明设备网络效果。原暂存区和冻结资料保持原字节。
