# P02 · Rust 桥接、VpnService 与真实数据通路

> 历史实施记录：旧模块、实验及工具源码从 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329` 在独立目录取回。其中模块、demo、实验及旧命令按当时工程解释。现行结构见 [ADR 0005](../design/adr/0005-single-app.md)，当前命令见 [开发环境](../development/setup.md)。 P02 已按用户确认完成，原验证范围与未覆盖项保留，后续阶段暂停。

> 实施预算约 20 个工作日，按五个批次推进；阶段状态以 [路线图](ROADMAP.md) 为准。这是第一条真实垂直链路，完成退出验收后才能说客户端具备实际 VPN 运行能力。

## 1. 目标

把 P00 实验变成有生命周期、错误恢复和会话隔离的生产基础。
由 Android Service 唯一持有运行会话，核心负责 EasyTier 协议，UI 负责意图和呈现。
建立真实数据流，但不在本阶段承诺所有高级配置已可用。
本计划覆盖 P02-01～08；最小真实连接、切网、应用恢复和资源释放都属于本阶段退出门槛。
预算按一位熟悉 Android 和 Rust 的工程师、配合 AI 实施与评审计算；原生关闭协议或设备前置不满足时，先关闭阻塞，再调整排期。

## 2. 前置门槛

- P00 在 [报告限定范围](../verification/p00/report.md) 已证明固定版本的 fd 借用、真实 socket 保护及双向通信；正式桥和会话生命周期仍待本阶段实现。
- P01 已限定验收；已有 `:app -> :contracts`、demo 配置切片及工具，live 构建仍明确阻断。启动时核对 [P01 退出评审](p01-foundation.md#p01-退出评审2026-10-14)与路线图。
- 对选定版本逐条复核 [核心证据](../research/android-core-evidence.md)。
- 明确 tag 与主干的桥能力不同，不能假定两个版本具有同一 RPC API。
- 准备受控对端、测试应用和可记录包流向的环境。

### 2.1 固定实施基线

本轮沿用 P00 选定来源和工具链，固定输入由 [原生候选清单](../../gradle/native-candidate.json)定位；Android 版本以 [应用构建配置](../../app/build.gradle.kts)为准。

| 项目 | 本轮基线 |
|---|---|
| EasyTier | commit `7592951b808c4839cb9d77cc4bc642f124915951`；源码版本 `2.7.0`，不视为稳定发行版 |
| NDK / Rust | NDK `30.0.16248370`、Rust `1.99.0`；同时核对候选清单中的 compiler SHA |
| ABI | `arm64-v8a`、`x86_64`；分别声明构建、加载和数据通路结果 |
| Android | minSdk 24、targetSdk / compileSdk 37；最低 SDK 声明不等于低版本验收 |
| 运行模型 | 单进程、单活动 VPN；真实会话由 Service 唯一持有 |

正式 native 工程锁定源码、依赖、features、补丁和构建参数，生成自己的产物身份。P00 冻结记录及候选的 `production_approved=false` 保持历史含义；P02 补丁另行记录评审与批准，不复用实验 APK 或 `.so` 解锁 live。核心升级与生命周期实施分开进行。

### 2.2 模块、输入与接口决策

- 在实现与消费者就绪时建立 `:core-bridge`、`:vpn` 和 `native/`，依赖沿用 [目录边界 ADR](../design/adr/0003-directory-boundaries.md)。扩展 `:contracts` 现有 `CoreEngine`；`EnginePort` 只作为 bridge 内部 JNI 边界，不增加第二份同义公共引擎接口。
- 本项目提供一个 cdylib，复用固定版本的 FFI 和完整原生宿主；APK 只加载本项目入口，核对最终链接中 runtime 与日志初始化的归属。
- 沿用 `SessionId`、`OperationToken`、`Observation`，补齐授权、地址等待、TUN 准备、重连及操作结果未知。运行快照保留配置修订、实际地址、peer / 路由 / NAT、版本、计数和新鲜度。
- liveDebug 使用专用受控配置入口和内存 `ProfileRepository`，补齐网络密码、TCP/UDP 连接节点及最小平台策略。配置切片负责产生不可变保存修订，服务只消费确定的修订；该入口不引用 demo 仓库或 fake。
- 秘密对象的 `toString()`、错误和日志输出必须脱敏，测试采用合成秘密。完整表单、加密持久化、存储迁移和进程恢复归 P03；P02 进程终止后的新装配回到无活动会话，需要重新提供配置和显式连接。
- UI 经服务客户端发送命令、观察服务状态；控制器、核心和清理作用域属于 Service，不能随 ViewModel 清理。最小 live 展示消费真实状态和地址，模拟文案由 demo 装配提供；尚未接入的读数保持缺测。

以下是本项目应实现的能力，不是上游当前 JNI 已具备的完成语义：

| 能力 | 输入与结果契约 |
|---|---|
| `getBuildInfo` / `validateConfig` | 版本、完整来源 SHA、ABI、features 和协议版本；配置验证返回结构化错误，不启动实例 |
| `start` | 不可变保存修订和操作身份；接受回执与启动完成事件分开，protector 就绪后才允许外层连接 |
| `attachTunAndAwait` / `detachTunAndAwait` | 借用 fd、会话与 attachment 身份；安装、移除或明确失败均有实际完成确认 |
| `stopAndAwait` | 关闭安装队列，等待会话任务、TUN I/O 和在途回调结束；重复停止共享同一次清理完成结果 |
| `queryRuntime` / 快照订阅 | 查询当前实例、操作、配置归属和 TUN 状态，用于核对丢失回执；快照携带来源时间与代次 |
| TUN 计数 / 核心日志 | 成功 I/O 的整数计数和有界脱敏日志；缺失数据附原因，不以零值替代 |

DTO 携带协议版本及会话、操作归属。未知枚举保留未知语义；不兼容的生命周期协议在握手时拒绝启动。生命周期完成回执独立于可合并快照和可丢弃日志，慢 UI 消费者不能阻塞数据面或丢掉清理确认。

### 2.3 所有权、平台策略与初始预算

| 资源 | 所有者 | 释放条件 |
|---|---|---|
| TUN `ParcelFileDescriptor` | Service / TUN 协调器 | 对应 attachment 的 native 借用结束确认后关闭 |
| 安装队列中的 fd 与 TUN I/O | native 在指定会话内借用 | 安装失败、取消、替换和停止都要确认已无使用者；原始整数 fd 不承担身份作用 |
| 外层传输 socket | 创建它的核心任务 | protect / 绑定只借用；错误、取消和停止由任务关闭，仍在回调中的借用不能提前失效 |
| protector global reference / 在途回调 | bridge | 关闭会话入口并等待在途回调结束后释放引用；旧回调只处理自己的资源 |
| 生命周期订阅 | 服务与 bridge 各自持有句柄 | 保留至终态和借用终止确认消费完成，再释放 |
| 共享 runtime | native 进程级宿主 | 记录固定预热基线；每个会话的任务和资源在停止时释放 |

核心启动或受控重连递增 `SessionId.generation`；同一核心中的每次 TUN 替换递增 attachment 身份。回调同时核对其会话、操作及对应 attachment，避免旧 fd 编号复用影响新资源。

Service 使用 `BIND_VPN_SERVICE` 保护系统入口，应用控制入口限制同 UID，通知动作采用显式、不可变 PendingIntent。每次连接核对 `VpnService.prepare()`，分别处理 VPN 授权和通知权限。API 34+ 在授权后采用 `systemExempted`，资格异常进入失败收束；采用 `START_NOT_STICKY`，显式关闭 Always-on 支持。依据：[官方前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[VPN 指南](https://developer.android.com/develop/connectivity/vpn)。

物理网络快照来自非 VPN 的 `Network`、能力和 LinkProperties；保护及绑定确认后才继续 socket 连接。固定源码的 protector 是进程级钩子，未注册时上游会直接放行，所以正式桥必须在启动前强制注册，并拒绝失效 Service 或旧代次回调。依据：[固定 protector 源码](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/socket_protector.rs)。

默认只覆盖组网路由；受控默认路由用例用于验证外层 socket 绕行，不构成出口功能验收。静态和 DHCP 地址使用核心实际结果；DNS 验证受控解析服务的实际查询与回包，IPv6 采用明确阻断策略并验证。完整 IPv6、Private DNS 等组合、出口和高级协议能力按 P05/P06 验收。

初始超时预算如下，用单调时间和可注入策略驱动，测试使用虚拟时间。超时表示尚未取得确认，不证明工作没有执行。

| 步骤 | 初始预算 | 超时后的约束 |
|---|---:|---|
| 核心启动 | 30 秒 | 取消启动并收束已取得资源；保留迟到结果的归属检查 |
| 地址等待 | 30 秒 | 不发布 Running；保留实际未分配或冲突原因，进入收束 |
| TUN 安装 / 移除确认 | 5 秒 | 查询实际 attachment 状态；借用未确认结束时保留 PFD |
| 停止确认 | 10 秒 | 保持停止未确认及资源事实，阻止再次启动；终态订阅继续保留 |

串行事件循环只推进状态和调度后台任务，不等待阻塞 JNI/I/O；取消、撤销与停止可以及时进入。网络消失使当前绑定失效并触发统一收束；网络恢复时仅在运行意图仍有效且旧会话已确认停止后受控重连。手动断开撤销运行意图，普通错误不触发无限重试。

TUN 参数变化先验证新 spec，再移除并确认旧借用、关闭旧 PFD、建立新 TUN 并等待安装确认；交接期间如实显示重建。未证实支持热应用的核心参数统一完整重连。应用失败后保留 saved 和用户编辑，恢复上次可运行内容由显式命令触发。结果未知时旧快照仅作历史，阻止再次应用并保留停止入口；先查询实际状态，无法核对时只有停止确认后才能受控重建。

## 3. 分任务

### 批次 A 实施记录（2026-10-14）

本批次实现领域契约与受控输入，实际模块图仍是 `:app -> :contracts`。代码及 fake 行为不构成原生或设备验收。

- [Session.kt](../../contracts/src/main/kotlin/com/github/zzwtsy/chuanmen/contracts/Session.kt) 补齐授权等待、地址等待、TUN 准备、重连与结果未知阶段；引擎进度、完成和资源事实分别表达，停止回执携带同会话排空证明。已应用快照有 Fresh/Stale 标记。
- [Runtime.kt](../../contracts/src/main/kotlin/com/github/zzwtsy/chuanmen/contracts/Runtime.kt) 提供协议版本、attachment、接口参数、64 位 TUN 计数、结构化错误、确认结果、运行查询快照及初始阶段预算；资源所有权约束同步到[技术架构](../design/technical-architecture.md#51-fd-所有权)。
- [Profile.kt](../../contracts/src/main/kotlin/com/github/zzwtsy/chuanmen/contracts/Profile.kt) 增加脱敏网络密码、受控 TCP/UDP 对端和最小 DNS/IPv6 策略。[受控入口](../../app/src/liveDebug/java/com/github/zzwtsy/chuanmen/feature/configuration/controlled/ControlledProfileInput.kt) 只编入 liveDebug，不接收外部 Intent、不持久化；测试覆盖 TCP/UDP × 静态/DHCP 输入。
- [内存仓库](../../app/src/main/java/com/github/zzwtsy/chuanmen/feature/configuration/storage/MemoryProfileRepository.kt) 被 demo 与受控入口复用，防止调用方集合改写保存修订；本地改名与核心身份、修订分离，保存不调用引擎。
- demo fake 验证进度不提前提交 Running、地址等待中取消、旧代次隔离、部分失败、停止超时、结果未知及查询核对丢失的应用回执。历史地址明确标记，历史快照不提供当前流量。
- 正式源码边界覆盖 `liveDebug/liveRelease`；检查仍不允许正式源码引用 demo，且保留 live 构建阻断。

检查入口为 `./scripts/android/check-fast.sh --offline`，JVM 报告见 `contracts/build/reports/tests/test/` 和 `app/build/reports/tests/testDemoDebugUnitTest/`。检查结果只覆盖契约、受控输入、fake 和 demo 界面；没有执行 JNI、VPN 或真机数据通路验收。B 的正式 JNI 实现、固定输入与双 ABI 构建是下一批次；P02-01～08 尚未整体验收。

### 批次 B/C 实施记录（2026-10-14）

已建立实际 `:core-bridge`、`:vpn` 和 `native/`。正式桥提供固定来源握手、配置验证、核心启动、实际 TUN 安装/移除、停止排空、独立引用释放及查询；Service 持有真实控制器和 PFD，应用只访问同 UID 客户端。受控配置不再装配 fake，liveDebug 产物门槛核对本次正式双 ABI 构建。

固定双 ABI 构建和 APK 16 KB 静态对齐检查已实测。API 36/arm64 的正式 JNI 设备用例通过版本查询与实际 pipe 借用/关闭确认；pipe 不代表系统 VPN 或数据通路。正式补丁当前修订为 `p02-session-host-v2`，新增实际 runtime 线程生命周期计数；修订后的设备回归待完成，不能继承上一产物的设备结果。

受控运行 `p02-formal-datapath-02` 已通过正式 Service 的静态 IPv4、TCP/UDP 外层各承载 TCP/UDP 业务回显，共 4 个请求。设备请求/回包摘要与对端记录逐项一致；两场景均有 TUN 双向计数，保护失败为 0，Activity 重建保持会话，随后确认断开。被测 liveDebug APK SHA-256 为 `01824ae24274e9c793b5eb6ae744674fac07ce5e739954f76aec150e1cf07091`，测试 APK 为 `0384f22fb11d530729c86bac60a7b0ebef831a2bcac9adbdf4f1406c65b5026b`。这些结果只属于该产物与范围，后续 APK 须重跑；原始输出留在忽略的 `build/experiments/p02-vpn-core/datapath-02/`。

设备前置的负向记录包括 USB 安装拒绝、MIUI 阻止 Activity 启动，以及权限恢复后对端预算已结束导致回包失败。设备门槛同时核对实际开始/通过用例、本轮应用报告、APK 字节和唯一对端回包，不以 Gradle 或 adb 的退出码代替实际执行。

领域、桥和服务回归覆盖等待实际地址、安装确认、晚到停止、未知结果保留资源、网络意图恢复及移除后替换顺序。真实 peer/路由/NAT/版本、固定 60 秒流量及有界日志已接入最小 UI；生命周期回执、合并快照和可丢弃核心日志分别处理。[所有权表](../verification/p02/ownership.md)与[设备判据](../../experiments/p02-vpn-core/README.md)同步维护。后续限定设备结果见下文；IPv6、实际切网、最终产物的平台复验及完整退出评审尚未完成，P02 继续 `in_progress`。

API 24、37 / x86_64 模拟器的正式基本生命周期已分别通过：正常授权拒绝、取消后忽略迟到授权、静态地址与真实 TUN 安装、Activity 重建以及确认停止。两台被测 APK SHA-256 均为 `6ad1998d8142161e1e3ff7529b262db45acb04781ed7772bedd5236ffb78015f`，测试 APK 为 `6c9790b4a0be4f2218698b913152553980b428fb34ae09f2d29c4cf973007723`，运行目录分别为 `emulator24-lifecycle-01` 与 `emulator37-lifecycle-03`。这组验证没有连接业务对端，不能扩展为双 ABI 数据通路验收。此前通知授权导致 Activity 处于 PAUSED 和资源 ID 包名不一致的两轮失败分别保留。

会话行为新增应用成功前保持历史修订、应用失败后的显式恢复、启动中撤销、JNI 释放未知阻止重连及旧会话回执隔离，协调器 18 项与流量窗口 4 项回归通过。资源回归的 `resources-01` 因线程测量来源不足失败；`resources-02` 因真机锁屏阻止 Activity 启动而超时，未进入 Service 或资源循环。前者促成原生实际线程计数，后者促成设备前置与亮屏检查，两轮均不登记 50 轮通过。

后续回归补齐网络回调迟于保护失败时保留恢复意图，以及未知核心状态不能冒充安装或移除确认，协调器现有 22 项与流量窗口 4 项均通过。新增未知安装用例先复现错误，再验证修复；核对预算耗尽仍走停止确认，不提前关闭借用。

运行 `native-contract-03` 在 API36/arm64 正式 APK 完成 3 项 JNI 契约：实际版本/协议、pipe 成功 I/O 与独立释放、保护拒绝及异常、无效 fd 安装失败、TUN 移除后替换、旧 attachment 隔离和两个并发停止确认。APK SHA-256 为 `7480cbf19bc18fe9f9fa4ac806cd60ef3fd972164cfb8a25db08f0680f250a81`；测试 APK 为 `5a0a8861f7805f5ace1ce3a838bd7f22ca65a107198cc4bb2a03dc96cc0f0574`。该用例仍不建立平台 VPN，不扩展为数据通路结论。

运行 `dns-02` 在同一正式 APK 完成 TCP/UDP 外层各一次正常系统 resolver 查询。实际 VPN DNS 为受控 `10.222.0.1`，两次 A 回答均与对端域名摘要和实际事务关联；每场景成功 TUN tx/rx 为 434/98 字节、保护失败为 0。测试 APK 为 `931398f6302f9da71508ea65be2aa08038760d03b5998a435bb25d1c8325d502`。对端通过固定正式来源的核心用户态 UDP 虚拟端口 53 回答，无宿主 TUN、特权 DNS 端口或系统路由变更；停止已确认。范围不含 Private DNS、AAAA 连通或完整双栈。

资源运行 `resources-03` 完成 3 轮预热、6 次回包后超时，`resources-04` 完成 11 轮、22 次回包后连接被拒绝，两轮停止样本均没有持续资源增长或未释放借用。固定版本的 UDP 本机关闭没有远端 FIN 确认，旧对端连接在探活结束前可能仍参与相同 IP 的路由选择；延长 TCP 首次连接预算没有消除失败。后续用例增加对端实际旧连接及可达路由归零确认，保留逐轮收束时长；不得据此声明无间隔快速重连通过。

运行 `resources-05` 在上述正式 APK 与测试 APK 完成 4 轮预热和 50 轮资源回归，共 108 次关联回包。固定 fd 基线为 226，正式轮次为 226～228；共享 runtime worker 保持 2。每轮停止后本机实例、借用、回调、JNI 引用和 PFD 为 0，服务订阅与任务各为 1，额外观察者归零；对端实际连接及可达路由连续三次归零。最长对端收束为 11305 ms。对端已确认停止；全量样本与构建前固定源码快照保留在忽略的运行目录。

运行 `policy-01` 在相同 APK 完成真实 DHCP（实际 `10.222.0.2`）、默认路由与 MTU 1400 应用、无效 `0.0.0.0` 地址引发的 TUN 安装失败及显式恢复。失败阶段为 Failed，旧修订为历史，资源确认归零；恢复后的核心代次为 4、运行修订为 2，已保存修订仍为 3，本地名称和草稿均保留。DHCP、默认路由与恢复各有 TCP/UDP 两次业务回包，共 6 次对端关联；外层为 TCP，保护失败为 0。该结果不扩展为实际切网或 IPv6 验收。

运行 `network-03` 在同一主 APK、测试 APK `de068ce61df6d7339e4c64f09fecda08d0e7233c958d5fb82bec3f5e92d3a474` 完成实际 Wi-Fi 断开/恢复与 IPv6 阻断。初始代次 1、恢复代次 3；平台网络参数与已确认会话稳定后一次业务请求成功，手动停止后再次切网保持 Idle、实例/PFD 为 0。物理 IPv6 TCP/UDP 在 VPN 前后分别成功，VPN 期间均失败且对端无对应请求，共 8 次正向回显。此前 `network-01` 缺就绪对端未执行，`network-02` 在恢复期间参数再次变更导致业务请求中止，保留原失败；没有增加业务重发。Wi-Fi 已恢复启用，对端已停止。范围为当前 API36/arm64 真机，不扩展为其他 OEM 或完整 IPv6。

机器声明由 [experiment.json](../../experiments/p02-vpn-core/experiment.json) 登记；此前仅 README 声明的历史运行保持调试记录，不补造运行前声明摘要。新设备入口保存本次编译输入的精确源码副本，并在构建和设备执行前后核对输入及 APK 摘要；旧 APK 结果仍只属于各自身份。

### P02-01 · 建立窄原生桥

1. 扩展现有 CoreEngine，在 :core-bridge 定义内部 EnginePort、版本化 DTO 解码及 Capability 映射。
2. 在 native/ 对固定 EasyTier 版本做最薄适配，禁止把内部 Rust 类型泄漏给 UI。
3. 显式实现启动、停止、配置解析、状态快照、事件订阅和版本查询。
4. 每个 DTO 带协议版本，未知值可降级；不能靠随意解析日志做状态机。
5. 缺少设备 / 统计入口时补受控适配，不凭空调用 callJsonRpc。
6. 区分调用被接受、工作完成、数据可用与失败，不以整数返回值混淆含义。
7. JNI 边界集中处理字符串、缓冲区和可恢复错误，阻止异常越界；release 的 panic=abort 或 native 崩溃按进程终止处理，不能用 Kotlin try/catch 宣称恢复。
8. 对上游改动记录 patch 和兼容测试，保留将来升级可定位的差异。

### P02-02 · 建立 fd 与回调所有权

1. 为 TUN fd、传输 socket、JNI global reference 和订阅句柄列所有权表。
2. 对当前借用 fd 的宿主，Kotlin 保留 ParcelFileDescriptor，native 不自行关闭。
3. 停止 / detach 获确认且 native 读写已结束后再关闭借出的 PFD；若采用另一所有权方案必须写 ADR。
4. 不调用 detachFd 后让两端都不关闭，也不让双侧同时拥有关闭责任。
5. 识别 setTunFd 的成功可能仅表示进入队列，等待真正安装完成的确认。
6. 核心重建递增会话 generation，TUN 替换递增 attachment 身份；旧回调只能清理自己的资源，不能改变新会话。
7. 为初始化部分失败、队列关闭、TUN 安装失败和重复关闭设计幂等路径，释放此前已取得的资源。
8. 阻塞 I/O 和 Rust 工作线程不得占用 Android 主线程。
9. 多个停止调用等待同一次清理完成，不能因已发出停止请求而提前返回成功。
10. 结束确认所需的订阅保留到收束完成，再按所有权顺序释放。

### P02-03 · 保护底层 socket 与网络绑定

1. 核实所选版本提供的 socket protector hook 及 JNI 暴露情况。
2. Rust 创建连接 socket 时，经桥调用当前有效 Service 的保护能力。
3. 等待 protect 结果再继续连接，失败应反馈为明确错误而非盲目重试。
4. TCP、UDP、重新打洞、重建连接及高级协议使用同一可靠路径。
5. 网络绑定按物理网络快照实施，不能把全进程绑定当作每个 socket 的替代。
6. 对已关闭 Service、网络消失和回调迟到建立超时与取消行为。
7. 用受控数据流证明传输 socket 没有再次进入自己的 VPN。

### P02-04 · 实现 Service 会话状态机

1. :vpn 的 SessionController 串行处理连接、断开、应用、撤销和网络变化。
2. 为每次运行分配 sessionId，关联 profileId 和 savedRevision。
3. 首次及再次连接调用系统 VPN 准备流程，处理拒绝、取消及授权被替换。
4. 按 §2.3 建立前台服务、权限与通知动作；生命周期入口汇入同一控制器，Service 清理作用域保留到收束完成。
5. 核心启动、实际地址、TUN 安装和平台路由准备均确认后才进入运行状态。
6. 无对端时展示“等待其他设备”，不能把引擎运行等同于端到端可达。
7. stop 幂等；取消连接后迟到的 ready 事件不能再次把状态改成运行。
8. 服务停止、onRevoke 与底层错误都走同一资源收束逻辑。

### P02-05 · 建立地址、DNS 与 TUN 协调器

1. 从核心真实结果生成 VpnInterfaceSpec：地址、路由、DNS、MTU 和应用策略。
2. 区分静态地址、自动分配结果和未分配，不能用表单草稿填充运行地址。
3. 对每次 spec 变更计算差异，按 §2.3 的移除确认、关闭、建立和安装确认顺序统一安排 TUN 重建。
4. 应用列表、路由、DNS 等平台层变更不能假定热修改已生效。
5. 记录重建前后 fd 和会话归属，避免旧 TUN 关闭新 TUN。
6. 动态地址为空、冲突或失效时展示可恢复错误，不提交半成品 spec。
7. DNS 和 IPv6 策略至少有可证明的基本路径，剩余组合进入 P05。
8. 以受控重连实现尚未证实支持热更新的核心参数。

### P02-06 · 实现最小控制面与运行快照

1. 按固定版本暴露 peer、路由、NAT、核心版本和基础状态。
2. 建立真实可用计数器；不可靠字段带 unavailable 状态和具体原因。
3. 把控制面状态与业务可达性分开，延迟和丢包先保留数据来源。
4. 返回经过结构化映射的状态，:app 不直接访问原始 RPC 或 fd。
5. 对频繁快照设置有界队列与合并策略，避免慢消费者阻塞数据面。
6. 针对过时结果检查 sessionId，切网络后不能混入旧 peer 与计数。
7. 记录 bridgeVersion、coreVersion、coreRevision 与 ABI，供诊断和兼容判断。

### P02-07 · 实现应用与恢复事务

1. 应用目标必须是一个已保存修订，不能直接消费正在变化的表单。
2. 保留上一次可运行配置和 Android 策略快照。
3. 采用明确步骤：验证、停止旧会话、启动目标、确认结果、更新运行快照。
4. 失败时区分“旧会话仍在”“已停止”“恢复中”“恢复失败”，不撒成功状态。
5. 恢复只改变运行数据，不能覆盖用户本地配置名称或尚未保存的草稿。
6. 查看 B 网络时 A 仍运行；只有显式连接 / 应用动作才改变运行目标。
7. 显式断开之后不因网络恢复事件或旧失败重试自动再连。
8. 用受控重连实现正确性，不在本阶段引入并行双 VPN 切换。
9. 按 [应用结果确认契约](../design/technical-architecture.md#43-应用结果的确认)实现成功、失败与结果未知的区分，以及查询核对或停止确认后的受控重建；回执丢失场景须有行为回归。

### P02-08 · 构建真实诊断证据

1. 收集生命周期事件、错误码、会话关联和经过脱敏的核心日志。
2. 记录 fd、线程、核心实例数量，支持多轮开关后的资源核对。
3. 补全 scripts/android/verify-device.sh 的基本连通、授权与生命周期检查。
4. 为无法自动操作的系统授权步骤输出人工指令，并将缺失结果登记为未执行。
5. 真机未连接或缺少测试对端时返回未执行，不让脚本报通过。
6. 为 native 升级和 VPN 故障整理实际使用的步骤；需要重复维护的流程再评估提炼为 skill。
7. 保留有界、脱敏的控制面事件，包含单调相对时间、会话代次、操作身份和配置修订；不采集业务包正文或秘密配置。
8. 事件格式与消费接口稳定后，将真实故障转为控制器回归 fixture，先覆盖启动中撤销、停止后晚到回调和应用回执丢失。

## 4. 文件与接口交付物

以下交付物在相应实现或实验就绪后建立；目录列表不代表当前已有生产能力。

| 位置 / 接口 | 最小内容 |
|---|---|
| core-bridge/EnginePort | bridge 内部窄 JNI 边界、线程约束、错误与能力映射 |
| native/ | 固定核心、适配代码、构建参数和补丁记录 |
| vpn/SessionController | 唯一会话所有者、串行状态机与恢复事务 |
| vpn/VpnInterfaceCoordinator | spec 差异、会话与 attachment 归属、TUN 重建与关闭顺序 |
| contracts/RuntimeSnapshot | 真实地址、状态、修订归属与未知值 |
| docs/verification/p02/ownership.md | fd、socket、订阅与引用的所有权表 |
| docs/verification/p02/device-runs/ | 经过筛选和脱敏的正向、失败、切网与循环测试记录，绑定被测构建 |
| scripts/android/verify-device.sh | 已实现的真实设备检查与明确缺失条件 |

## 5. 依赖与并行边界

### 5.1 五个实施批次

批次按依赖顺序推进，日期为相对工作日。A 的接口与所有权约束锁定后进入 B；B 的正式桥契约和补丁评审完成后进入 C；C 的真实连通是 D/E 数据通路验收的前置。控制器回归与诊断实现可随对应批次进行，E 集中完成退出验收。

| 批次 | 任务映射与工作内容 | 完成门槛 |
|---|---|---|
| A · 第 1～2 天：契约与输入 | P02-01/02/07：扩展领域契约，定义错误、确认结果、运行快照与所有权；准备 TCP/UDP、静态地址和 DHCP 受控配置 | fake 驱动启动、取消、停止、失败与结果未知；配置身份和本地名称独立，保存修订与运行事实分开 |
| B · 第 3～7 天：正式原生桥 | P02-01/02/03/06：实现带会话、操作和 attachment 身份的确认协议，补齐队列取消、部分失败、保护回调、成功 TUN I/O 计数和有界诊断；固定构建输入 | 双 ABI 构建与桥契约回归通过；入队不提前 Ready，停止回执证明借用终止；正式补丁有独立身份并完成评审 |
| C · 第 8～11 天：Service 与首次连通 | P02-03/04/05：接入正式装配、授权、通知、本地 Binder 客户端和 TUN 协调器；完成实际地址及平台路由确认 | 正式 APK 与受控对端完成真实 TCP/UDP 通信；取消、拒绝和撤销统一收束；Activity 重建保持服务会话 |
| D · 第 12～16 天：切网、重建与应用 | P02-02/04/05/07：实现非 VPN 物理网络更新、按运行意图恢复、TUN 替换和已保存修订的应用事务 | 手动断开保持停止；旧事件不能影响新会话；应用失败与未知结果保持事实可辨，显式恢复保留 saved、名称和草稿 |
| E · 第 17～20 天：快照、诊断与退出 | P02-06/08，回归 P02-01～07：接入最小真实状态展示、运行快照、设备入口及 native/live CI，执行资源循环和故障重放 | 连续 50 轮资源回归及 §6 全部退出场景通过，验收记录绑定正式 APK、native 和源码身份 |

### 5.2 协作与交付门槛

- 桥与 Service 可在接口锁定后并行，原生 ABI 修改需同步通知两侧。
- 状态机测试使用 fake；真实桥契约测试使用受控核心，二者结果分开归档。
- 可控集成测试使用正式 Service 与控制器装配，只替换核心边界，检查事件接线和实际状态变化。
- UI 团队可继续基于相同契约做 P03/P04，不改变实际 Engine 语义迁就原型。
- 一次只让一个负责人修改 SessionController 和所有权协议。
- P03 的配置保存与 P04 的展示可并行，真实应用依赖本阶段退出结果。
- 不并行改核心版本和解决生命周期 bug，避免无法归因。
- 保留 demo 默认设备入口与退出码，增加显式 live 模式及受控对端输入。缺设备、授权、对端或本次报告时登记未执行，不从旧报告补齐通过。
- 保留 demo 快速检查，新增 bridge/vpn 回归、固定双 ABI native 构建、live 装配检查和 APK 对齐检查，接入统一 CI 与 quality-gate；真机结果独立登记。
- 将 live 的恒失败门槛改为核对实际服务、桥接、正式补丁评审及产物身份；缺项继续阻断。native 命令实现并实测后再登记到开发环境。
- 同步受影响的模块边界检查、开发环境及能力台账；能力状态只按取得的证据更新，受控输入可连接不构成完整配置已支持。

### 5.3 结构收尾与退出准备

本批次在 A～E 的功能实现基础上清理跨变体耦合与阶段工具归属，保留四个模块、demo/live 分离、服务会话所有权和现有公开契约。当前暂存的 P02 实现保持完整，收尾改动单独审阅；阶段仍为 `in_progress`，等待本次远端 CI 和维护者退出评审。

| 顺序 | 实施内容 | 验收条件 |
|---|---|---|
| F-A 测试归属 | 受控输入测试由 `testDemo` 迁到 `testLiveDebug`，删除 demo 对 liveDebug 源码挂载；保留两类测试共用的 `demoUiTest`；显式启用 AGP 默认未创建的 liveRelease 单测 | demo 独立编译；liveDebug 执行原有受控输入断言；liveRelease 单测不引用调试入口 |
| F-B 构建与阶段门槛 | `VerifyLiveEngine` 仅检查正式 native 输入与双 ABI 产物，接线由正式编译验证；补丁审查与测试迁到 P02 实验目录，CI 独立执行 | 缺 ABI/身份错误仍阻断 live；审查缺失或过期仍阻断阶段 CI；编译快照不依赖阶段审查文件 |
| F-C 模块依赖检查 | Python 保留源码边界；check-fast 加载 Gradle init script，从实际模型检查直接依赖和配置归属 | 合法设备测试依赖通过，生产直连 bridge、demo 引用 VPN、反向/生产自依赖、缺失边和 JVM 平台越界均失败；配置缓存复用 |
| F-D 入口与移交 | 同步当前事实、命令、目录职责及 P03 接管；冻结记录不改写 | 当前入口与代码一致，现行调用无失效路径，历史证据和暂存边界保持完整 |

Gradle 模型允许的项目依赖为：app 的 `implementation → contracts`、`liveImplementation → vpn`、`androidTestLiveImplementation → core-bridge`；bridge 的 `api → contracts`；VPN 的 `api → contracts`、`implementation → core-bridge`；contracts 无项目依赖。配置阶段先执行全部延迟依赖声明，再采集直接依赖；任务只消费普通输入值，不解析外部制品或在执行期访问 Project。自依赖豁免从 AGP 的 host/device 测试组件读取 compile/runtime 配置，手工可解析配置及仿测试名称均拒绝。

#### 临时内容的保留与退出

| 内容 | 当前归属 | 退出条件与接管方 |
|---|---|---|
| `ControlledEntry` 调试表单、release 占位、`ControlledProfileInput` 及对应测试 | liveDebug/liveRelease 与 testLiveDebug | P03-02/04/05 完成持久化与正式编辑接入后删除，必要断言转入正式配置回归 |
| `MemoryProfileRepository` | 当前配置切片共享实现 | P03 的正式 live 改用持久化仓库；内存实现保留 demo/测试用途 |
| 受控对端、DNS fixture、构建支持 | P02 实验最小对端 | 随正式设备回归继续维护 |
| 固定九次运行的 `review.py`、`test_review.py` | P02 实验记录复核 | 保留至远端 CI 与维护者退出评审完成；随后删除脚本、测试及对应 CI 调用，不保留转发入口 |
| `check_review.py`、`test_check_review.py` | P02 实验阶段审查 | 保留独立审查入口；后续 native 来源变化建立新记录与审查，旧记录保持原样 |
| 已冻结报告、来源身份、正向及重要负向记录 | 验证目录 | 保留执行时的字节与结论；更正或替代使用新增记录 |

本批次验收执行受影响 Python 回归、固定 Ruff、check-fast、demo APK/设备测试编译、liveDebug APK/设备测试编译、liveDebug/liveRelease 单测与 APK 对齐检查；目录、隐私和冻结完整性另行检查。比较收尾前后的正式主 APK 和双 ABI 库摘要：相同字节继续按原产物引用历史设备记录，不新增真机通过声明；产物不同则保持退出未完成，为新产物补齐对应验收，不扩大旧复核脚本的例外集合。

每次阶段退出按实际 diff 逐项决定特殊安排的保留、删除或移交，记录文件、接管阶段和退出条件。P02 本地收尾不代表远端 CI、独立审阅或发行批准；提交、推送与 PR 按后续授权执行。

#### 本地收尾验证（2026-10-15）

| 本次执行 | 结果与定位 |
|---|---|
| `check-fast.sh --offline` | 通过；Android 工具 53 项、live 执行契约 10 项、native 工具 7 项、临时 Gradle 工程 9 项；日志位于 `build/android/check-fast/` |
| 模块模型与配置缓存 | 合法图及设备测试依赖通过；错误配置、反向/自依赖、缺失边、Android 平台依赖/插件均被拒绝；临时工程连续两次执行确认配置缓存复用，实际工程再次执行同一任务集合也复用配置缓存 |
| JVM 变体 | contracts 14 项、bridge 12 项、VPN 29 项、demo 49 项；liveDebug 7 项包含原受控输入 4 项，liveRelease 3 项。demo/release 测试编译目录和 XML 均无 `ControlledProfileInputTest` |
| Android 编译 | demoDebug APK、demoDebug/liveDebug 设备测试 APK、liveDebug 主 APK 编译通过；报告位于 `build/android/build-demo/` 及各模块 `build/reports/tests/` |
| 真实构建任务的隔离反例 | 临时工程复用当前构建脚本和 native 字节，无阶段目录及审查材料时 `:app:verifyLiveEngine` 通过；缺 ABI、过期产物身份均以 `LIVE_ENGINE_MISSING` 失败。日志位于 `build/android/p02-closeout/gate-*.log`，不改动实际报告或冻结材料 |
| native 与打包 | `check-inputs`、`verify-artifacts`、`check_apk.py` 通过，双 ABI 打包字节和 ELF/APK 16 KB 静态对齐通过；不是 16 KB runtime 验收 |
| 阶段工具迁移 | 当前阶段审查、实验目录 2 项回归通过；导出当前复现闭包后再次通过 2 项回归，未执行设备或历史 APK 复建 |
| 工程与文档 | Ruff 固定版本 lint/format、actionlint 1.7.12、证据工具 42 项回归、配置目录、隐私和严格文档检查通过；P00/P02 冻结完整性通过。P02 `--require-stage` 因 `P02-08` 待验收返回 2，符合当前阶段状态 |

收尾前后 liveDebug 主 APK SHA-256 均为 `7480cbf19bc18fe9f9fa4ac806cd60ef3fd972164cfb8a25db08f0680f250a81`；arm64-v8a、x86_64 库分别保持 `95e4c3f54e032e80725f6d20024b1f1c7d8458294a490fac6ca8b65ff3cd0f87`、`b507aba0a82fbd9b44362ae4f1b3400a1849c7db2f8015c36139320ce9d0dc5d`。比对 130 份冻结文件及 Git index，均未变化。历史设备结论继续限定于原产物及原场景。

本次未重新执行设备验收、Rust 双 ABI 编译、远端 CI、独立复建或维护者退出评审；未生成或发布新证据包。新生成的设备测试 APK 不继承历史设备验收。本地实施完成后仍须按当前提交运行远端 CI，完成退出评审后再退役固定九次运行的复核工具。

#### 门禁回归补充（2026-10-15）

- P02 实验目录和补丁审查 JSON/Markdown 单独变化也要求 Android/阶段 CI；CI 策略共 7 项回归通过，一般实验与文档的既有分类保留。
- Gradle 回归覆盖 `withDependencies`、`defaultDependencies` 和回调修改其他配置，合法延迟依赖通过，生产直连 bridge、demo 引用 VPN 及纯 JVM 平台越界均拒绝；不存在的纯 JVM 制品不触发解析，连续两次运行复用配置缓存。
- 手工可解析自依赖及仿测试配置名被拒绝；当前真实 AGP 工程正常测试接线通过，临时工程重放实际 AGP 的 `manualProduction → app` 自依赖反例时明确失败。修复不修改应用、native 或冻结记录，阶段仍待远端 CI 与维护者退出评审。

#### PR 交付前复验（2026-10-18）

本次将正式连接闭环与结构收尾作为同一任务交付，按当前暂存内容复验构建入口、模块边界、变体隔离及阶段门槛。检查结果如下：

| 检查层次 | 结果与定位 |
|---|---|
| 工具与 JVM | `doctor.sh --offline --native`、`check-fast.sh --offline`、`build-demo.sh --offline` 均返回 0；固定 Ruff 和模块模型回归通过。Gradle 复用未变化输入的测试及编译缓存，测试数量沿用对应 XML，不新增用例执行声明 |
| 变体与打包 | demo/live 设备测试 APK、liveDebug 主 APK、liveDebug/liveRelease 单测检查通过；正式双 ABI 身份与 ELF/APK 16 KB 静态对齐通过 |
| Rust 与阶段工具 | 正式宿主 7 项、受控对端 DNS 3 项测试、阶段工具 2 项回归、补丁审查通过；日志位于 `build/git-workflow/p02-closeout/` |
| 交付检查 | CI 策略 7 项、证据工具 42 项、配置审计 10 项和 P00 工作流 6 项回归通过；actionlint 1.7.12 通过；暂存树的隐私、严格文档及 P00/P02 证据完整性检查通过 |

构建后的 liveDebug 主 APK 与双 ABI 库均保持上文记录的摘要；既有设备结果继续对应原产物和原场景。本次未重新执行设备验收、Rust 双 ABI 编译或独立复建，未生成新证据包。P02 阶段检查仍因 `P02-08` 返回 2；远端检查以 PR 最新 head 的实际 Actions 结果为准，维护者退出评审完成前保持 `in_progress`，固定历史运行复核工具按上文退出条件保留。

## 6. 验证与明确通过标准

| 场景 | 通过标准 |
|---|---|
| TCP / UDP 实用流量 | TCP、UDP 外层分别承载 TCP、UDP 业务流量；测试应用与受控对端完成双向传输，请求身份、回包与 TUN 证据可对应 |
| 重复开关 | 固定真机连续 50 轮启动停止，无双会话、fd 持续增长或崩溃 |
| 资源收束 | 部分初始化失败、安装失败和队列关闭释放已取得资源；多个停止调用等待同一次清理，native 读写和在途回调结束后才关闭对应借用资源 |
| 停止后的实际效果 | 真机停止确认后无活动实例、TUN 借用和旧会话传输；会话线程、fd、订阅与 JNI 引用释放，共享 runtime 保持预热后的固定基线 |
| 停止未确认 | 超时不发布停止成功，仍持有的资源可定位；保留终态订阅并阻止再次启动，不能以固定 sleep 代替释放确认 |
| 正式装配 | 可控集成测试只替换核心边界，正式 Service / 控制器完成启动、撤销、停止与回执接线 |
| 拒绝 / 撤销 | 拒绝不启动；撤销后资源收束，无后台无限重试 |
| 保护与绑定 | 保护 false、异常、失效服务及网络消失均阻止该次连接；TCP/UDP 创建、监听、接受及重建路径按实际启用范围验证，默认路由覆盖用例证明外层 socket 绕行 |
| 授权与前台服务 | 每次连接核对准备流程；授权结果和前台服务资格匹配；通知动作及应用控制入口无未授权调用，停止确认后移除本应用通知 |
| 安装异步确认 | setTunFd 排队成功时不提前显示运行；安装失败进入可解释状态 |
| 迟到事件 | 旧 session 的 ready / error / stats 不覆盖新运行状态 |
| 网络切换 | 受控 Wi-Fi 断开与恢复后按策略恢复，手动断开后保持停止 |
| TUN 重建 | 地址、路由与 fd 代际一致，业务恢复后旧 fd 已释放 |
| 地址真实性 | 静态和 DHCP 地址以核心实际读回、TUN 参数及业务回包核对；未分配或冲突不发布 Running |
| DNS / IPv6 基本策略 | 受控 DNS 服务收到查询并返回可核对结果；IPv6 按所选阻断策略实测，参数读回不替代查询或流向验证 |
| 应用失败 | 草稿、已保存和运行快照保持可辨，重命名不被回滚 |
| 应用结果未知 | 回执丢失不伪报失败或成功；旧快照标为历史，再次应用被阻止，显式停止可用，查询或停止确认后的重建消除未知状态 |
| 故障重放 | 格式与消费接口稳定后，脱敏事件重放能复现启动中撤销、停止后晚到回调和应用回执丢失，结果由控制器行为断言验证 |
| 主线程 | 连接、停止和大日志输入不产生应用自身主线程长时间阻塞 |
| Activity / 进程生命周期 | Activity 重建及 UI 关闭不结束活动服务；进程终止后新装配无伪造 applied 或自动模拟恢复，Always-on 不作为本阶段能力 |
| 快照与诊断背压 | 生命周期回执保持可确认，快照合并与日志丢弃可计量；慢消费者不阻塞数据面，缺测保留原因，整数计数不经浮点存储 |
| 构建及打包 | 正式源码身份、补丁、features、双 ABI 库与 APK 打包一致；16 KB ELF / APK 对齐通过，缺项不能解锁 live |
| 诊断安全 | 共享证据不含网络密码、私钥、认证 token 或完整敏感配置 |

领域回归采用虚拟时间，覆盖重复操作、启动中取消/撤销、旧回调、部分失败、并发停止和回执丢失；原生契约回归另核对真实队列、任务及 fd 借用结束。fake、正式装配集成、原生、构建和设备结果分别报告。

API 36/arm64 真机承担受控数据通路门槛，API 24、37 模拟器验证基本生命周期。双 ABI 构建与 16 KB ELF / APK 对齐分别核对；其他 OEM、16 KB runtime、完整 IPv6、长期性能和电量维持未验收，按 P05/P06 推进。

50 轮是本阶段的真机资源回归门槛。P00 性能结果仅作限定基线，正式 P02 产物不继承其通过状态，也不由模拟结果推导真机性能。
Android TUN 的读写与 socket 保护原则参考 [官方 VPN 指南](https://developer.android.com/develop/connectivity/vpn)。

阶段实验开始前，按 [验证规范](../development/verification.md)声明范围、判据、次数/时长预算、停止条件及关闭安排；身份不匹配、借用资源无法确认释放或出现持续增长时停止并保留失败结果。发布记录绑定正式 App/native/源码身份，先筛选与脱敏，再检查和冻结；不覆盖 P00 已发布记录或扩大根运输清单。

## 7. 失败回退与本阶段不做

- 缺少 socket protect 回调：补足固定版本的桥，不能把回环问题归给用户配置。
- 没有稳定的热更接口：统一受控重连，并在应用入口说明会短暂断开。
- 核心停止无确认：先修所有权与关闭协议，不用固定 sleep 猜测完成。
- 任一关键原生风险未关闭：保持 demo/live 区隔，不展示假的“已连接”。
- 不实现无限后台保活、Root 网络管理或跨用户 / 工作资料接管。
- 完整配置 UI、加密持久化与存储恢复归 P03，完整监控页面归 P04，出口和高级网络互操作归 P05；P02 先完成受控输入下的正式运行基础。
- 不重写核心加密、P2P 或路由协议；上游缺陷采用最小补丁与升级记录。

## 8. 本阶段证据模板

~~~text
任务：P02-__
App / 核心 / bridge SHA：
正式补丁评审 / 构建输入 / APK 与 native 摘要：
环境层次 / 设备匿名编号 / Android / ABI / 页大小：
profileId（脱敏）/ savedRevision / sessionId / generation / operation / attachment：
触发动作与操作顺序：
状态事件时间线：
TUN 地址 / 路由 / fd 归属证据：
外层与业务协议 / 对端请求身份和回包证据：
资源预热基线 / 循环次数、失败次数、残留：
结论与未验证平台：
~~~

### 平台复验与故障重放（2026-10-15）

正式主 APK `7480cbf19bc18fe9f9fa4ac806cd60ef3fd972164cfb8a25db08f0680f250a81` 已分别在 API37、API24 / x86_64 自有模拟器通过正常拒绝、取消后迟到授权、连接、Activity 重建及手动停止。API37 测试 APK 为 `de068ce61df6d7339e4c64f09fecda08d0e7233c958d5fb82bec3f5e92d3a474`；API24 为 `faa63e6ef91f9ea3ca1f84fa6431b3b7fe49895aa3f9b99da0712743ac0fb8b8`。两者均确认实例和 PFD 归零，范围不包含模拟器业务对端连通。

[版本化故障夹具](../../vpn/src/test/resources/fault-replay)采用 `p02-events-v1`，只保存单调相对时间、事件分类和行为预期。正式协调器在虚拟时间消费三组输入：启动中撤销及迟到启动、停止后旧回执、应用回执丢失及未知查询后的显式停止和恢复。逐事件核对阶段、已确认修订、新鲜度和持有的 TUN 数，结束核对所有 PFD 关闭及活动会话为零；协调器共 25 项、流量窗口 4 项回归通过。夹具为合成故障，不冒充真机事件采集。

系统撤销在 API37 自有模拟器经正常设置 Forget VPN 已通过；旧尝试误选 Dismiss，保留失败，最终确认按钮同时核对资源 ID 和文字。实例、PFD 和前台通知归零，授权撤销且 ServiceRevoked 保持。正式补丁的范围与来源身份已接入 live 构建门槛。九份关闭运行的[实施复核](../verification/p02/report.md)已核对绑定同一主 APK，并发布筛选结果；复核不新增设备执行。P02-01～07 的限定本地结论通过，P02-08 仍待本次远端 CI 与最终退出评审，路线图不提前标 verified。
