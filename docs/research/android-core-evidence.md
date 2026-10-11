# Android 与 EasyTier 核心研究证据

外部资料核对时间：2026-10-11（Asia/Shanghai）；读取工具时间锚点：2026-10-10 22:43:59 UTC。本文保留当时读取的官方资料、推导与开放问题，文中的“当前/最新”均指该核对日期，后续维护不代表重新检索。

后续实验结果见 [P00 报告](../verification/p00/report.md)，实际主应用工具链见 [开发环境](../development/setup.md)，阶段状态见 [路线图](../plan/ROADMAP.md)。外部资料、实验构建和产品实现分别提供证据；研究时的待验证事项按这些入口核对，不能直接视为当前仍全部未验证或全部已完成。

原型、旧参考目录和本文建议都不作为真机能力证据。研究时没有启动 VPN、构建 APK、加载 JNI 或执行对外网络实验。当前主干接口存在，不能推出稳定发布包含同一接口；配置助手出现某项，也不能推出 Android 产品已经支持它。

## 1. 最重要的研究结论

| 结论 | 等级 | 实施影响 |
|---|---|---|
| 最新非预发布为 v2.6.4；当前主干已发生核心/宿主与 JNI 能力变化 | 已证实 | P00 选定一份 tag/commit + 补丁集，不能混用稳定二进制与主干 DTO |
| mobile TUN 路径不在 drop 时关闭外部 fd | 已证实 | 建议 Kotlin 持有 PFD，native 借用；停止完成后才能关闭 |
| 当前 setTunFd 成功返回只证明进入安装队列 | 已证实 | 必须建立 TUN-ready/停止回执，不能立即显示已应用 |
| main 有等待确认的 Rust protect hook，JNI facade 未暴露相应注册方法 | 已证实 | 自有 bridge 补齐 Android 回调并实测，不能只复用一份 Kotlin 示例 |
| LoggerRpc 仅调节/查询级别，没有日志订阅 RPC | 已证实 | 两来源日志必须建立真正的 sink/文件读取链路 |
| peer NAT、版本、收发统计和探测丢包信息在主干 DTO/实现中存在 | 已证实 | 逐一验证所选版本暴露与字段口径，缺失值不能伪造 0 |
| 60 秒图应统计本机 TUN I/O，不能读取 fd 两次 | 拟议设计与 I/O 推理 | P02/P04 在现有读写路径计数，不能用全部 peer 隧道流量替代 |
| Play 新应用/更新当前需 target API 36+；Android 17/API 37 已发布 | 已证实 | P00 评估 target 37、P07 重核当时要求；测试矩阵包含 API 37 |
| Material3 stable 1.4.0、1.5.0-beta01；完整 Expressive API 不能概括为稳定 | 已证实 | 稳定组件实现已定视觉，局部 beta 依赖按 ADR 管理 |

## 2. 上游版本、发布物与许可

### ET-01 / ET-02：仓库与发布基线

实际读取：

- [官方仓库元数据](https://api.github.com/repos/EasyTier/EasyTier)
- [官方最新非预发布记录](https://api.github.com/repos/EasyTier/EasyTier/releases/latest)
- [v2.6.4 标签解析](https://api.github.com/repos/EasyTier/EasyTier/git/ref/tags/v2.6.4)
- [main 提交](https://api.github.com/repos/EasyTier/EasyTier/commits/main)
- [固定主干 Cargo.toml](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/Cargo.toml)

读取结果：v2.6.4 于 2026-05-12 15:25:14 UTC 发布，`prerelease=false`；tag 指向 `8428a89d2dabc94c97d370ec607c6ca142473626`。发布附件含 arm64、arm、x86、x86_64 Android APK，但本次未下载、安装或验证这些 APK。

main 读取时为 `7592951b808c4839cb9d77cc4bc642f124915951`，提交时间 2026-10-10 17:35:33 UTC。主干 workspace 有 `easytier-core`、`easytier-proto` 和原生 `easytier` 等模块；manifest 标注核心 2.7.0，**这不是 2.7.0 已发布的证据**。主干与稳定版根 Cargo 文件均读取到 Rust 最低版本 1.95；构建工具版本仍须与选定 SHA 一起冻结。

官方 JNI README 仍写有较旧的 Rust/NDK 要求，不能优先于实际 Cargo.toml、rust-toolchain、依赖和构建产物。选择 main 会获得较新适配点，同时承担未发布变更风险；选择 stable 则必须核算补齐 bridge 所需补丁成本。P00 用构建与真机结果做决定。

### ET-14：许可证

已读取 [EasyTier 核心 LICENSE](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/LICENSE)，仓库元数据识别为 LGPL-3.0；核心 Cargo 使用 `license-file = "LICENSE"`。

能确认：应保留对应许可证、通知、所用源码和补丁的可追溯关系。不能仅凭 `.so` 扩展名推断所有分发义务已满足，也不能仅凭该许可证推出独立 App 的全部源码必须采用某一许可证。发布前需根据最终链接/打包和源码提供方式确认义务，并审计额外依赖的许可证。

## 3. JNI、原生宿主与版本差异

### ET-03：稳定版和主干公开入口对照

实际读取：

- [稳定版 JNI lib.rs](https://github.com/EasyTier/EasyTier/blob/8428a89d2dabc94c97d370ec607c6ca142473626/easytier-contrib/easytier-android-jni/src/lib.rs)
- [稳定版 FFI lib.rs](https://github.com/EasyTier/EasyTier/blob/8428a89d2dabc94c97d370ec607c6ca142473626/easytier-contrib/easytier-ffi/src/lib.rs)
- [主干 JNI facade](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-android-jni/src/lib.rs)
- [主干 Kotlin 接口](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-android-jni/kotlin/com/easytier/jni/EasyTierJNI.kt)
- [主干 JSON RPC 桥](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-ffi/src/json_rpc.rs)

| 能力 | v2.6.4 已读 JNI facade | 当前 main 已读 JNI facade |
|---|---|---|
| 验证 TOML、启动实例、设置 TUN fd | 存在 | 存在 |
| 保留指定实例 / 收集运行信息 / 读取最后错误 | 存在 | 存在 |
| 单独删除实例、列举实例 | 未见对应独立 JNI 导出 | 存在 |
| 通用 protobuf JSON RPC | 未见 `callJsonRpc` JNI 导出 | 存在；生命周期仍用专用入口 |
| 配置服务器客户端管理 / 回调 | 未见相应 JNI 导出 | 存在 |
| Android socket protector 注册、完整 TUN 指标、TUN 安装完成事件 | 未见这些完整契约 | 已读 facade 中也未见这些完整契约 |

“未见导出”限定于本次读取的公开 JNI/FFI 文件，不等于 Rust 内部完全不存在能力。若要使用内部能力，需要额外桥接，不应在 Kotlin 页面中假装方法已经可调用。RPC serviceName 的实际注册名和 JSON 字段以所选版本描述符与一次真实调用为准，不能混用旧 `cli` schema 和新 `api.instance` schema。

### ET-04 / ET-10：复用边界

已读取 [NativeHostRuntime](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/host_runtime.rs)、[FFI 静态上下文](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-ffi/src/state.rs) 和 [FFI Cargo features](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-ffi/Cargo.toml)。

已证实：原生宿主承担进程级 socket/DNS 机械实现；FFI 有静态共享 Runtime 与实例管理器，库同时可构建为 cdylib/rlib。拟议：项目用一个可审计的 native 入口和窄 `CoreEngine` 适配，复用现成宿主；UI 不绑定上游完整内部类型。是否打包多个 `.so`、是否重复初始化日志/运行时必须检查最终链接产物，不能仅凭构建脚本列了两个文件就全部加载。

## 4. TUN 和 socket protection

### ET-05 / ET-06：fd 与就绪语义

实际读取：

- [主干 set_tun_fd FFI](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-ffi/src/instance_api.rs)
- [主干 mobile TUN 安装队列](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/instance/runtime_host/tun_mobile.rs)
- [主干 virtual_nic.rs](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/instance/virtual_nic.rs)
- [稳定版 virtual_nic.rs](https://github.com/EasyTier/EasyTier/blob/8428a89d2dabc94c97d370ec607c6ca142473626/easytier/src/instance/virtual_nic.rs)

mobile TUN 使用外部 raw fd，并设置 `close_fd_on_drop(false)`；主干 `attach_fd` 用 `try_send` 投递，另一异步任务执行 NIC 安装，安装错误被记录。因而 `setTunFd=0` 不保证安装完成。核心与平台之间还需同步 DHCP 地址、路由和关闭时序。

建议的 Kotlin-owning/native-borrowing 模型、TUN-ready 事件、stop/detach 完成回执和代次标识是**本项目新增契约**。本次没有通过调用证明任一关闭 API 已能安全释放所有任务；这些是 P00/P02 必过的实验。

流量计数应放在现有 TUN 成功 I/O 路径。另开读取器会消费原本应交给核心的报文，不会得到旁路副本。现有全 peer/隧道指标与本机 TUN 流量语义不同；用户要求的趋势图不能直接取一个名称相似的计数器。

### ET-07：保护回调存在，但 Android 桥仍需完成

已读取 [socket_protector.rs](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/socket_protector.rs)、[TCP socket 路径](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/socket/tcp.rs)、[通用 bind 路径](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/tunnel/common.rs)。

源码规定：保护 future 必须等到保护实际完成才返回；失败应阻止继续创建/暴露 socket。源码也显示：没有安装 protector 时函数直接成功返回。已读 JNI facade 不提供注册这个回调的公开接口。因此 P00 必须搭通 Android `VpnService.protect(fd)`，并由一次包含默认路由覆盖的真实实验排除回环；P02 扩展到所有启用协议、重连与入站路径。

Android 官方 [VpnService API](https://developer.android.com/reference/android/net/VpnService) 说明 protect 失败条件、不关闭 socket、绕过 VPN 的语义；它不是加密方法。`setUnderlyingNetworks()` 是向系统报告实际底层网络，不能替代 socket 绑定和迁移。

## 5. 配置、运行指标和日志

### ET-08：设备字段与统计口径

已读取 [api_instance.proto](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-proto/proto/api_instance.proto)、[common.proto](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-proto/proto/common.proto)、[探测实现](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-core/src/peers/conn/peer_conn_ping.rs) 与 [连接统计投影](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-core/src/peers/conn/peer_conn.rs)。

主干具有 NodeInfo/Route 的版本、StunInfo 的 TCP/UDP NAT、PeerConnStats 的收发字节/包与 latency，以及 connection loss_rate。loss_rate 由 ping/pong 成败窗口导出。可验证的 UI 文案是“探测丢包率”，不能说“业务数据丢包率”。同一设备可能多连接；经中继远端的业务流量不能等于中继邻居总流量。字段缺失、未知 enum、旧版本未上报必须有明确空态。

这些是源码的数据供给证据，尚未证明目标 APK 在指定 NAT 环境中能实时得到每一字段。P00 固定请求/响应 fixture，P04 真机对照 EasyTier 官方 CLI/可观测结果。

### ET-09 / ET-11：配置变更与构建能力

已读取 [api_config.proto](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-proto/proto/api_config.proto)、[实例 RPC 注册](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-core/src/management/full/compiled.rs) 和 [build_capabilities.rs](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-core/src/instance/build_capabilities.rs)。

主干有 PatchConfig，但并非任意核心字段的通用事务；其中定义了特定 patch 字段。编译 capability 校验可能拒绝未包含的 DHCP、用户态栈、代理、Portal 等能力。当前 JNI 存在某个通用调用入口，并不证明热更新字段已和 Android TUN 同步。

实施默认受控重连；只有针对选定版本、字段、失败/取消场景和 TunPlan 同步取得证据后，才加入热更新允许名单。所有候选功能都需要“官网编辑范围 → 核心编码 → 构建 feature → Android 适配 → 真机用例”五段映射。原型 8 个隐藏项必须在 P00 重审；特别是 ipv6_public_addr_auto 属于暂未完成适配，不能直接宣称 Android 不需要。原型 37 + 7 是快照编辑单元数，不能当作生产能力永久上限。

### ET-12：日志不是级别 RPC 的同义词

已读取 [api_logger.proto](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-proto/proto/api_logger.proto)、[JNI logger 初始化](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier-contrib/easytier-android-jni/src/logger.rs)、[核心日志模块](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/common/log/mod.rs) 与 [file sink](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/easytier/src/common/log/file.rs)。

已证实 API 只提供 Get/SetLoggerConfig，JNI 初始化 android_logger，核心具有自己的日志设施和文件 sink。拟议两来源日志事件流需要桥接 callback/sink 或受控文件读取，并处理一次初始化、背压、轮转与敏感数据。不能把定时读日志级别误实现为日志收集，也不能在 release 无数据时塞入模拟日志。

## 6. Android 平台和发布事实

### A-01 / A-02：VPN 生命周期

[官方 VPN 指南](https://developer.android.com/develop/connectivity/vpn) 和 [VpnService API](https://developer.android.com/reference/android/net/VpnService) 已读取。

已证实：VPN 使用系统授权，授权可撤销；服务有前台运行要求；Always-on 的系统生命周期与网关维护由系统和 App 分工负责；应用允许/排除名单互斥且改变名单需要新建 VPN 连接。系统 revoke 回调不能假设总在主线程，停止需回收 socket 和 PFD。Always-on/锁定模式不是“勾一个开机启动”即可完成的能力。

待实测：所选 target、厂商系统与锁定模式下的启动路径、应用名单、断网恢复、数据不泄漏策略，以及设备实际授权状态。文档行为不能替代 OEM 设备测试。

### A-05 / A-12：target 与最新系统

已读取 [当前 Play target 要求](https://developer.android.com/google/play/requirements/target-sdk)，官方页面最后更新 2026-10-01，明确从 2026-08-31 起普通手机新应用/更新需要 API 36+。该要求是商店门槛；不是 minSdk，也不是所有分发渠道相同要求。

已读取 [Android 17 正式发布](https://developer.android.com/blog/posts/android-17-is-here)，发布日期 2026-06-16，正文明确 Android 17/API 37 与大屏自适应要求。因此项目应在 P00 比较 target 36 与直接 target 37 的成本，并将 API 37 纳入测试。P07 按实际交付日期再次核对政策；若实施跨入后续年度，不继续机械引用本次快照。

### A-06：16 KB

已读取 [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)。事实包括：AGP 8.5.1+ 的打包对齐、NDK r28+ 默认 ELF 对齐、所有预编译库都需兼容，以及不能假设页面永远是 4096 字节。需要同时验 ELF、AAB/APK 和实际运行。

另检索到 [Google 官方 2025-05 公告](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html) 的搜索摘要说明 2025-11-01 起面向 Android 15+ 的新应用和更新需支持 16 KB。直接打开被官方站点重定向验证页阻挡，**未读取全文**；该摘要不作为已完整核对的政策全文。技术验收仍以已读取的兼容文档与 P07 商店检查为准。

### A-07 / ET-13：前台服务类型不能照抄示例

已读取 [官方 FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types) 和 [启动 FGS](https://developer.android.com/develop/background-work/services/fgs/launch)。官方把系统 VPN 设置中已配置的 VPN App 列入 systemExempted 适用条件，并说明不满足条件会抛异常；specialUse 需要用途说明和商店声明。

[当前上游 TauriVpnService](https://github.com/EasyTier/EasyTier/blob/7592951b808c4839cb9d77cc4bc642f124915951/tauri-plugin-vpnservice/android/src/main/java/TauriVpnService.kt) 使用 specialUse。它只证明上游当前选择，不证明本项目各条启动路径有同样适用条件。P00 应在授权前后、用户连接、系统恢复/Always-on 路径确认类型和权限；P07 提交真实使用说明。

### A-08—A-11：进程、状态和存储

已读取 [进程和线程](https://developer.android.com/guide/components/processes-and-threads)、[架构建议](https://developer.android.com/topic/architecture/recommendations)、[DataStore](https://developer.android.com/topic/libraries/architecture/datastore)、[网络状态回调](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)。

已证实：组件默认同进程；Service 本身不是后台线程；系统可回收进程；官方建议清晰数据层、单向状态流和生命周期感知观察；DataStore 面向小量状态，较复杂关系数据应另选合适存储。模块职责与依赖、默认单进程和服务唯一状态所有者来自这些约束和本项目规模，不是官方规定所有 VPN 必须这样设计。当前目录与依赖取舍见 [目录边界 ADR](../design/adr/0003-directory-boundaries.md)。

## 7. Material 3 Expressive 与动态色

### A-03：稳定性核对

实际读取 [Compose Material3 发布页](https://developer.android.com/jetpack/androidx/releases/compose-material3)，页面顶部 2026-10-07 状态为 stable 1.4.0、beta 1.5.0-beta01。1.4.0 稳定版发布于 2025-09-24；1.4.0-beta01 发布说明记录了 ExperimentalMaterial3ExpressiveApi 从该线移除。

可得结论：稳定 Material3 已有可用于当前设计的组件与 motion 能力；“Expressive 风格”不要求每个组件都使用实验 API。但若选用 1.5 独有 Expressive API，需面对 beta 的兼容和版本组合风险。P00/P01 冻结选用 API；P07 再看当时是否已经稳定。不要引用页面旧示例中的 compiler 版本作为现代 Kotlin/Compose 全套依赖方案。

### A-04：Monet

已读取 [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)。官方说明 Android 12+ 可以使用动态 ColorScheme，更低版本回退自定义明暗方案。颜色角色必须成对使用；自定义图表或组件仍需自行检查对比度。原型的种子色演示不代表原生 App 已读取系统动态色。

### A-13—A-15：复制、可访问性与密钥

已读取 [复制与粘贴](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[Compose 可访问性默认值](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) 与 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)。Android 13+ 有系统复制反馈，需要避免 App 再发一条重复提示；Compose 默认可交互组件遵循最小触控目标，定制 Canvas/点击区域仍需自行检查。Keystore 提供密钥使用和不可导出边界，但不意味着使用 Room 或 DataStore 后存储自动加密。具体存储方案属于项目设计与迁移测试责任。

## 8. 研究时提交给 P00 的开放问题

下列问题保留研究时的调查范围。P00 已对固定来源、限定数据通路、配置解析、指标与错误路径形成报告；最低系统、其他设备/ABI、16 KB 运行及高级互操作等缺口仍以报告和后续阶段为准。正式产品不能继承实验 APK 的验收身份。

1. 采用哪个 tag/commit？需要哪几个最小补丁？为什么不用另一个候选？
2. 一份 release native 产物是否能在候选最低 API 和 ARM64/16 KB 环境加载？
3. protect 注册、线程回调、撤销失败路径是否覆盖全部启用协议？
4. 外部 TUN fd 借用期如何结束？有没有真正的安装/移除/停止完成回执？
5. DHCP 地址、动态路由、MTU、DNS 和应用名单如何形成一致 TunPlan？
6. 所选版本能从哪个入口得到 NAT、版本、路径、收发、探测统计？缺失如何表达？
7. 核心日志是否能接入一个有界 sink？当前全局 logger 初始化是否冲突？
8. 本机 TUN 计数如何在唯一 I/O 路径实现、验证方向与字节含义？
9. Portal/出口/子网代理各自需要哪些构建与宿主能力，哪些留到 P05 验证？
10. minSdk、targetSdk、compileSdk、Material3/Compose、NDK/Rust 的实际可编译组合是什么？

任何一个关键数据路径问题没有证据，都不应通过扩大 UI 开关数量来假装已解决。可以继续用 fake engine 实现页面，但开发和发布状态必须清楚区分。

## 9. 来源索引与复核方法

机器可读索引在 [sources-android.json](sources-android.json)，记录来源 ID、精确 URL、读取日期、Git revision、工具引用标识（如有）和可支持结论。GitHub 内容通过只读官方仓库工具读取；没有网页引用标识的源码仍以固定 SHA URL 定位。

重新核对时，先读取锁定 SHA 与选中发布标签；只有决定升级时才对比当前 main。Android 政策和组件发布页属于可变来源，应记录日期并在 P07 重查。不要把原型目录里较早的 coreSemanticsRevision、网页 bundle SHA 和新核心 build SHA 强行改为同一个值；它们分别代表不同证据对象。
