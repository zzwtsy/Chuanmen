# P01 · 工程基础与可重复开发环境

> 阶段状态以 [路线图](ROADMAP.md) 为准。预计 1 周。已有模板和工具可复用，本文列出补齐 P01 的工作与验收；拟建接口和脚本须创建并验证后才可用。现有命令见 [开发环境](../development/setup.md)。

## 1. 目标

建立可以被人和 AI 重复构建、定位与验证的原生项目。
先划清配置、会话、桥接、持久化和 UI 边界，再以最小演示页面验证状态流。
避免从网页 DOM 移植业务代码；原型只作为交互与状态参考。

### 已有基础与剩余门槛

| 范围 | 已有基础与结果定位 | 待评审事项与后续边界 |
|---|---|---|
| Android 工程 | `:app -> :contracts`、demo 配置切片、四页导航、生命周期收集、主题组件及具名预览；JVM/UI 与限定 API 36 设备结果见下方历史摘要 | [退出评审](#p01-退出评审2026-10-14)已核对 demo 边界与第 6 节标准；API 24–25 未实测，系统栏与完整无障碍尚未验收，完整设备矩阵和 TalkBack 归 P06 |
| 工具链 | Wrapper、版本目录、Daemon JVM；[新 checkout 复建](#本地实施与复验记录2026-10-12)通过；候选核心/NDK/Rust/ABI 与来源摘要已固定并自动核对，live 明确阻断 | 复建复用了依赖缓存，不声称空缓存或第二主机；P02 正式接入与生产批准另行验收 |
| 质量检查 | 四个 Android 入口、工具回归与模块/import 检查已建立，参数和报告见[开发环境](../development/setup.md)；Python 与 Android CI 已[远端通过](#远端-ci-通过记录2026-10-14) | [退出评审](#p01-退出评审2026-10-14)已核对执行报告与源码身份；测试计数以对应运行报告为准，Git 工作流门禁与阶段退出分别判断 |
| 协作与文档 | 根 AGENTS.md、目录边界 ADR、任务模板、开发环境与 PR 规范；[退出评审](#p01-退出评审2026-10-14)已核对工程门槛 | 第一方代码已确定为 AGPL-3.0-only，见根 LICENSE 与 README；第三方许可和正式发行义务另行保持 |

**P01 已在工程基础与限定 demo 范围完成退出验收**：第 6 节标准已复验，第一方代码许可证确定为 AGPL-3.0-only，结果见[退出评审](#p01-退出评审2026-10-14)。阶段状态以路线图的 `verified` 为准。空缓存/第二主机不额外作为 P01 门槛，API 36 demo 通过也不代替完整设备或正式 VPN 验收。

历史模板、当前 demo 与 P00 探针分别覆盖各自范围；本地领域测试不表示正式 VpnService 或生产桥接已验收。当前装配决策见 [ADR 0004](../design/adr/0004-demo-assembly.md)。

## 2. 前置门槛

- P00 已决定核心版本、宿主适配策略、ABI 和工具链候选。
- 真实 TUN 通路已具备可复现证据，或有明确批准的并行阻塞记录。
- 团队已统一包名、应用展示名和代码许可证；签名密钥不在仓库中。
- 阅读根 AGENTS.md、总体设计和开发环境；复用已核验模板命令，正式产品接入不能继承实验 APK 的验收身份。
- 建立可运行 Android 构建/JVM 检查的 CI 环境，并明确后续真机任务的执行位置。

## 3. 分任务

### P01-01 · 在已有模板上建立首条业务切片

1. 以已有 :app 和首条 demo 业务切片起步；实际使用跨模块接口时建立 :contracts，VPN 与 JNI 实现就绪时建立 :vpn、:core-bridge；Rust 放独立目录 native/。
2. :app 的 application 负责装配与导航，shared/designsystem 负责公共视觉；feature 按实际业务建立，操作、状态、存储、转换和测试聚合到所属切片。
3. 不为每个屏幕建立 Gradle 模块；出现独立交付或构建收益后再拆。
4. :contracts 只包含实际跨模块使用的纯 Kotlin 契约；配置草稿、校验和数据库实现留在配置切片。
5. :vpn 通过窄桥操作核心，通过注入的仓库接口访问保存版本；业务 UI 只消费公开契约，应用装配层连接实际实现。
6. 共享 fake/fixture 先放测试源码，多个模块实用后再建立 :testing。
7. 按目录边界 ADR 建立首条业务切片的 import 与模块依赖检查；仅保存不重连、本地改名不修改核心身份、应用失败不伪报成功均须覆盖。正式会话的结果确认遵循[技术架构](../design/technical-architecture.md#43-应用结果的确认)。

### P01-02 · 锁定并证明工具链

1. 核对已有 Gradle Wrapper、版本目录和 Daemon JVM 配置；正式 native 工程建立时补齐 Rust toolchain / lock 文件。
2. 依据 P00 接入候选固定正式工程的 NDK 与目标 ABI；维护开发环境中的 SDK/JDK 安装要求。
3. 核心版本使用明确 SHA 或受控源码快照，并记录补丁清单。
4. 创建不需要真实 VPN 或秘密的 demo 构建变体。
5. 让 live 构建在核心依赖缺失时明确失败，不能悄悄退回 fake。
6. 在干净 checkout 构建一次 demo APK，记录耗时和完整命令。
7. 网络不可用时打印缺失依赖，不建议关闭校验或换不明镜像。

### P01-03 · 落实 AI 可操作命令

1. 创建 scripts/android/doctor.sh，检查工具版本、SDK/NDK、Rust target 与本机条件。
2. 创建 scripts/android/check-fast.sh，只执行确定性静态检查和相关 JVM 测试。
3. 创建 scripts/android/build-demo.sh，输出 demo 产物路径与明确退出码。
4. 创建 scripts/android/verify-device.sh，先检查设备存在，再调用已实际实现的设备测试。
5. 每个脚本提供帮助、前置条件、输出目录与失败分类，不读取生产秘密。
6. 在已有 docs/development/setup.md 中登记实测后的同一组命令，AGENTS.md 只指向真实入口。
7. CI 与本地调用同一脚本，避免文档、AI 指令与流水线三套命令漂移。

### P01-04 · 定义稳定领域契约

配置私有模型与草稿放配置切片；会话、核心及跨模块仓库实际共用的接口和快照放 :contracts。

1. 定义 LocalProfileId、LocalProfileMetadata、CoreConfig 与 AndroidVpnPolicy。
2. 定义 Draft、SavedRevision、AppliedSessionSnapshot，三者保持独立。
3. 定义 SessionId / generation，异步结果必须能判断归属。
4. 定义连接状态和操作结果，区分未连接、准备、连接、运行、停止、失败。
5. 定义 Capability、UnavailableReason、DataFreshness，不用空字符串表达未知。
6. 对失效输入保留 raw 值及字段错误，不能把解析失败自动改成默认值。
7. 领域类型表示项目契约，不伪装成 EasyTier 官方 SDK 类型。

### P01-05 · 建立确定性 demo 环境

1. 创建实现领域契约的 FakeEngine，不在 Composable 中生成随机网络状态。
2. 注入时钟、调度器和事件源，使重连、超时、迟到回调可控。
3. 提供空网络、已连接、连接失败、应用失败、无采样等具名 fixture。
4. 用同一份 fixture 驱动 Compose Preview 和测试。
5. demo 明显标识模拟数据；生产入口不能自动选择 fake。
6. 固定日志与曲线样本，禁止测试依赖公网、随机 NAT 或真实计时等待。

### P01-06 · 建立 Compose 与主题基础

在已有共享主题上扩展组件契约和预览；模板动态配色不代表完整主题与无障碍要求已验收。

1. 建立语义色、间距、字体层级和圆角 token，避免每页自定义风格。
2. 以 Material 3 稳定组件实现基础布局，Expressive 的实验 API 单独评估。
3. Android 12+ 使用系统动态配色；低版本使用可复现的默认配色。
4. 建立浅色、深色、动态色关闭、高对比和大字体的预览样例。
5. 以 48 dp 触控目标、状态语义与 TalkBack 阅读顺序作为组件契约。
6. 定义图标 IP 复制、配置分组行、设备统计项和条件操作区的共用组件。
7. 对依赖实验 API 的组件写 ADR，不能将“视觉风格”误解为强制追 alpha。

### P01-07 · 建立导航与状态收集骨架

1. 主导航先接四个领域入口：总览、设备、配置、日志；设置由顶栏进入。
2. 路由携带本地配置 ID，查看另一网络不能隐式改变运行网络。
3. ViewModel 输出 UiState；UI 通过显式动作更新，不直接读数据库或 JNI。
4. 使用符合生命周期的状态收集；重组不会重启 VPN 或重复订阅日志。
5. 返回、对话框关闭与标签切换保持草稿，草稿丢弃必须来自明确动作。
6. 屏幕状态用结构化对象表示，不把“成功过”布尔值作为连接状态。

### P01-08 · 建立最小质量与协作规则

1. 根 AGENTS.md 保持短小，列真实命令、模块地图和关键不变量。
2. 仅在 native、vpn 等有特殊规则的目录增加局部 AGENTS.md。
3. 复用已有 ADR、任务 brief 和验证规范；复杂任务与阶段评审按需记录，普通维护不额外生成永久 brief 或证据包。
4. 单测只覆盖真实行为风险：状态转移、配置语义、隔离与错误恢复。
5. Kotlin/KDoc 与相关测试意图遵循中文注释约定，说明条件和可观察结果。
6. PR 模板要求改动目的、验证命令、证据、限制与影响模块。
7. 为 AI 任务写文件所有权和允许变更范围，避免多个代理编辑同一文件。

## 4. 文件与接口交付物

下表是阶段退出时应具备的结果。构建配置、开发环境、ADR 和代理规则在原文件中维护；业务目录与四个 Android 脚本已建立，后续模块按实际消费者建立。

| 位置 | 最小交付 |
|---|---|
| settings.gradle.kts / gradle/libs.versions.toml | 模块图、版本锁定与可复现插件配置 |
| contracts/src/main/ | 实际跨模块使用的会话、核心、仓库与必要快照契约 |
| app 的 configuration 切片及对应测试 | 首条本地改名操作、私有元数据模型、demo 实现与行为验证 |
| app/src/demo/ | 具名场景、预览入口及模拟数据标记 |
| 核心接入边界 | 固定来源与接入条件；模块按实际实现建立，缺失真实引擎时明确失败 |
| scripts/android/ | doctor、快速检查、demo 构建、设备检查入口 |
| docs/development/setup.md | 在现有环境说明中补齐 demo、Android 脚本、产物和已验证的常见阻塞 |
| docs/design/adr/ | 模块边界、工具链、DI 与实验组件决策 |
| .github/workflows/ | 保留现有 Python 校验，补齐与本地脚本一致的 Android 构建和测试 |
| AGENTS.md / 任务模板 | AI 可检索的规则和可闭合任务交付格式 |

## 5. 依赖与并行边界

- 工程、领域契约、主题组件可并行，由单人负责根构建文件。
- 修改领域接口前通知 P02/P03 负责人，先更新契约与 fixture 再改消费者。
- UI、原生桥和持久化依赖注入由 :app 统一装配，禁止相互查找单例。
- 协作边界按模块或文件集合划分，不能仅写“一个人做 UI、一个人做逻辑”。
- P02 可在桥空壳与契约稳定后进入；P03 的模型工作可同步开始。
- 构建未重现前不要批量生成几十个屏幕或大量抽象层。

## 6. 验证与明确通过标准

Android 诊断、快速检查、demo 构建和设备入口均已创建；命令、参数及产物统一见[开发环境](../development/setup.md)。

| 验证 | 通过标准 |
|---|---|
| 干净环境 | 新 checkout 可按文档构建 demo，无维护者机器专用路径 |
| 命令契约 | 所有已记录命令实际存在；失败返回非零且说明前置缺失 |
| 版本固定 | Android 与 Rust 依赖来源可追踪，未使用隐式 main 或浮动版本 |
| 模块边界 | contracts 不含 Android/JNI 引用，切片通过公开契约协作，UI 不直接依赖 DAO 或 JNI |
| demo 隔离 | demo 可离线运行，live 不会静默显示模拟连接 |
| 状态复现 | 同 fixture 得到同结果，可稳定复现迟到事件和连接失败 |
| 最小导航 | 主导航切换、返回、当前网络选择保持领域归属 |
| 主题与无障碍 | 主要入口在浅/深/大字体预览中可操作，触控区域达约定尺寸 |

CI 首次成功须能定位到具体运行及其源码版本，不能以工作流文件或本机脚本存在宣称远端持续集成已经成功。普通检查使用 CI 报告和忽略目录中的诊断输出；阶段验收再按验证规范选择必要记录，不把每次运行永久入库。
屏幕预览通过不代表真机布局、TalkBack 或系统权限流程已通过。

### 历史验证摘要

以下保留阶段门槛、重要负向结果与执行身份；计数和版本属于对应日期，不是当前 head 的统计。完整过程见[历史原文](https://github.com/zzwtsy/Chuanmen/blob/b749abff46b1ef11848f6079ca6d7becd030e00f/docs/plan/p01-foundation.md#本地实施与复验记录2026-10-12)。本地诊断目录被忽略，可能过期，不作为永久证据来源。

### 本地实施与复验记录（2026-10-12）

源码为基线 `a4b6572979a73473379692aac5064a03713d7b4b` 加当时工作区实现，环境为 Linux x86_64、Launcher Java 21.0.12.1、Daemon JDK 25。doctor/check-fast/build-demo 离线执行均为 0；32 项 JVM 测试与 15 项工具回归通过，设备测试 APK 的 13 项用例仅编译。live 与 native 诊断分别返回预期的 1/3，无设备返回 5，无 SDK 返回 3。

全新 clone 应用任务补丁与新增文件，不复制 local.properties 或构建输出，通过 ANDROID_HOME 提供 SDK 并复用 Gradle/uv 缓存；诊断、快速检查、demo 与设备测试编译全部为 0，生成 20 张 JVM 渲染图。记录位于 `build/android/p01-checkout-current/build/android/source-rebuild/`；不声称空缓存、第二主机或 APK 字节一致复建。

### 实施补齐与本地 UI 验收（2026-10-12）

同一基线上的工作区固定了原生候选与来源摘要，生产批准仍为 false。JVM 的 32 项测试含 10 个展示条件与 3 项 Activity 交互，Robolectric API 37 生成 20 张图到 `app/build/p01-ui/`；这些属于宿主验证。两次软件模拟器在 240/600 秒内未启动完成，均已关闭，设备断言未执行。

### 配置恢复与低版本截图修复复验（2026-10-13）

继续在上述基线的工作区修复，保持 minSdk 24。快速检查为 0，41 项 JVM 测试（27 项领域/状态、14 项 UI）与 15 项工具回归通过；demo 构建、14 项设备用例编译成功。demo APK SHA-256 为 `9dacb1a536f13e2730f0ed3a6869f5ed9092b683b94f6634185435fd29d2514e`。恢复用例验证 saved 修订递增、名称与 applied 保留、引擎命令为 0，内容一致后消除待应用；API 24–25 的省略截图逻辑未在设备实测。

API 36 安装曾被 INSTALL_FAILED_USER_RESTRICTED 拒绝，入口返回 0 但没有测试报告，未登记通过；允许安装后的整组运行与直接 instrumentation 均超时（6），没有完整报告或 PNG。临时设备设置已恢复，残留进程已收束；历史诊断位置为 `build/android/verify-device/`。

### API 36 设备复试（2026-10-13）

同一工作区测试 APK 在已亮屏、解锁的 API 36/arm64 设备上仍未进入展示断言。线程栈停在 Instrumentation.startActivitySync，系统返回 START_ABORTED (102)，MIUI 后台弹出权限 10021 为 ignore；后续允许该权限后完成验收。诊断进程及 JDWP 转发已关闭，记录位于 `build/android/verify-device/retry-api36/`。

### API 36 设备验收（2026-10-13）

本轮没有修改源码、断言或依赖。允许后台弹出后，正常设备入口为 0，XML 登记 4 项导航与 10 项展示用例，失败、错误、跳过均为 0。保留 APK 的复跑因安装重置权限而超时（6）；恢复权限后对已安装同一 APK 直接 instrumentation 为 0，用时 165.34 秒，14 项全部通过，20/20 PNG 结构有效，尺寸为 1220 × 2440。

正常入口的成功记录为 `build/android/verify-device/retry-api36/first-passing-run/`，直接运行摘要为同级 `direct-full-ui-summary.json`；中间失败的 result.json 不替代它们。恢复 MTU 的共享 UI 回归在设备通过。结论仅覆盖该 API 36/arm64 demo；API 24–25、真实 VPN、系统栏和 TalkBack 未验收。

### CI SDK 安装入口修复（2026-10-13）

[首次 Android CI](https://github.com/zzwtsy/Chuanmen/actions/runs/38382258126) 对应源码 `7c746609f5f909961313d8ff57602704fbf4ae5c`、Ubuntu 24.04。SDK 安装因 sdkmanager 不存在返回 127，后续诊断、测试与构建均未执行。

同一提交加 CI 修复的本地独立 checkout 使用固定 Android CLI 1.0.16500706 与新临时 SDK，复用依赖缓存：SDK 安装、doctor/check-fast/build-demo 和设备测试编译全部为 0，41 项 JVM 与 15 项工具回归通过；actionlint 1.7.12 与 bash -n 通过。记录在 `build/android/ci-sdk-retry/`；本轮未执行修复后的远端 CI、设备或空依赖缓存复建，远端结果见下方记录。

### 环境诊断与依赖迁移复验（2026-10-13）

当时的工作区修复 SDK/JAVA_HOME 定位与可选 Rust 诊断，迁移 Compose v2 测试；实际解析 KGP/Compose 编译插件为 2.4.21，Compose UI/UI 测试为 1.12.1。快速检查、demo 与设备测试编译成功，41 项 JVM、23 项工具回归和 20 张 JVM PNG 通过；live/native 仍按预期阻断。这些计数保留原执行范围，后续工具回归以各次运行报告为准。

正常设备入口因安装重置 MIUI 权限返回 4，XML 仅有 5 项（1 失败），整组未完成；恢复权限后直接运行已安装同一 APK 为 0，用时 27.798 秒，14 项全部通过，20/20 新 PNG 有效。两次结果分别登记在 `build/android/dependency-repair/`，不合并为 Gradle 设备测试成功。原 AGP 兼容表与 JDK 25 传递依赖告警限制见历史原文；未重复新 checkout、空缓存、第二主机、API 24–25 或真实 VPN 验证。

### 远端 CI 通过记录（2026-10-14）

`main` 源码 `8180bbbd8c20ff74be98bdc004c8e690265b69f1` 的两项 push 运行均成功，满足当时的基础远端 CI 门槛：

| 工作流 | 实际运行 | 结论范围 |
|---|---|---|
| Android foundation | [38409455625](https://github.com/zzwtsy/Chuanmen/actions/runs/38409455625) | SDK 安装、doctor、快速检查、demo 构建与设备测试 APK 编译成功 |
| Verification evidence | [38409456078](https://github.com/zzwtsy/Chuanmen/actions/runs/38409456078) | Python lint/格式、回归、隐私、目录与 P00 既有证据校验成功 |

这是固定 SHA 的历史结果，没有重新执行 P00、真机、空缓存或独立主机验收，也不自动关闭 P01。现行 PR 门禁按[Git 工作流](../development/git-workflow.md)与各 PR 的最新 head 检查。

### P01 退出评审（2026-10-14）

评审源码为 `1a63d9a7615ab37ab368b2c533e7230e9dd8d28d`，执行者为 Codex；宿主为 Linux x86_64，Launcher Java 21.0.12.1、Daemon JDK 25。设备为 Xiaomi、API 36、arm64-v8a，保持亮屏、解锁及既有后台弹出授权。本次没有改动产品 UI、领域契约或设备测试断言。

| 第 6 节标准 | 本次核对与结论 |
|---|---|
| 干净环境 | 新 checkout 未复制 local.properties 或构建输出，通过环境变量提供 SDK，复用依赖缓存；诊断、快速检查、demo 构建、设备测试 APK 编译全部为 0 |
| 命令契约 | 四个入口与帮助存在；设备入口隔离旧报告、强制执行并校验完整用例，48 项工具回归实际通过；缺报告、损坏、计数不符、重复、错误身份、失败和跳过均有负向回归 |
| 版本固定 | Wrapper、版本目录、Daemon JVM 与候选来源摘要核对通过；live 构建为预期的 1（LIVE_ENGINE_MISSING），native 诊断为预期的 3（NATIVE_NOT_READY） |
| 模块边界 | 当前 :app -> :contracts 的模块/import 检查通过；纯 Kotlin 契约和 demo/live source set 边界保持有效 |
| demo 隔离 | 新 checkout 离线构建成功，设备显示模拟标记；live 不回退 fake，生产批准仍为 false |
| 状态复现 | 新 checkout 实际执行 41 项 JVM/UI 测试，失败、错误、跳过均为 0；包含超时、迟到回执、保存/应用分离及本地名称隔离 |
| 最小导航 | 本次设备入口完整执行 4 项交互和 10 项展示用例，失败、错误、跳过均为 0；覆盖返回、草稿保留、复制和恢复运行配置 |
| 主题与无障碍基础 | 浅/深/大字体、高对比和动态色的共享展示断言通过；heading、唯一导航名称和 48 dp 导航目标通过；20/20 PNG 结构有效，大字体配置与深色总览复核可操作 |

新 checkout 的完整命令为 `./scripts/android/doctor.sh --offline`、`./scripts/android/check-fast.sh --offline`、`./scripts/android/build-demo.sh --offline`、`./gradlew --offline --console=plain :app:assembleDemoDebugAndroidTest`；四步耗时分别为 0.565、19.859、4.276、2.021 秒。live/native 负向检查使用开发环境登记的命令。六步总耗时 28.201 秒；不声称空缓存、第二主机或通用 APK 字节一致复建。

设备命令为 `env 'ORG_GRADLE_PROJECT_android.injected.androidTest.leaveApksInstalledAfterRun=true' ./scripts/android/verify-device.sh --offline --timeout 600`，设备 Gradle 步骤为 0，用时 47.972 秒，入口为 0。测试 APK SHA-256 为 `0f0dc79aeb2ecc5aac308f130985f7ae4a0cc516a29332903acbee0ade9cd3fe`；demo APK SHA-256 为 `8e012877260fbd13ffba3f358a1809ca263a0e3a4cace53035b2169afb47b88f`。本次 20 张设备图尺寸均为 1220 × 2440。

上述固定源码的 [CI 38503937604](https://github.com/zzwtsy/Chuanmen/actions/runs/38503937604) 实际执行 Android 与 Python 检查并通过，`quality-gate` 成功；[PR 策略 38503937455](https://github.com/zzwtsy/Chuanmen/actions/runs/38503937455) 通过。后续文档提交仍按 PR 最新 head 门禁交付。

验收范围限定工程基础和上述 demo。API 24–25、完整设备矩阵、系统栏/IME 实机专项、TalkBack、进程恢复、正式 VPN/JNI 与完整配置仍未验收，按 P02–P06 推进；不扩大 P00 运输清单。第 2 节的第一方代码许可证已明确为 AGPL-3.0-only，根 LICENSE 保存 GNU 官方全文，README 说明适用范围和第三方许可边界。该决定不改写 P00 冻结许可记录或批准发行。第 6 节工程标准与前置门槛已闭合，阶段登记为 `verified`（工程基础与限定 demo 范围）。工程验证绑定上述源码 SHA，许可与状态登记为后续文档提交，PR 最新 head 检查另行核对。

## 7. 失败回退与本阶段不做

- DI 框架增加不必要编译复杂度时，可先用清楚的构造注入，记录决策。
- 过多模块导致迭代缓慢时，先保持包边界，不先造通用插件系统。
- Expressive 某组件依赖不稳定时，使用稳定组件与已确认视觉 token。
- 不用忽略 lint、关闭签名校验或扩大秘密权限解决构建失败。
- 本阶段不宣称真正联网、不实现全面高级选项、不建立远程控制服务。
- 不提前创建缺少真实命令和 fixture 支撑的“万能 Android skill”。

## 8. 本阶段证据模板

~~~text
任务：P01-__
源码 SHA / 工具链：
环境：干净 checkout / 本地 / CI
执行命令（实际存在）：
退出码 / 产物路径：
使用 fixture：
预期 / 实际可观察结果：
依赖边界检查结果：
未验证事项与责任人：
~~~
