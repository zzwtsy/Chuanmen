# P01 · 工程基础与可重复开发环境

> 阶段状态以 [路线图](ROADMAP.md) 为准。预计 1 周。已有模板和工具可复用，本文列出补齐 P01 的工作与验收；拟建接口和脚本须创建并验证后才可用。现有命令见 [开发环境](../development/setup.md)。

## 1. 目标

建立可以被人和 AI 重复构建、定位与验证的原生项目。
先划清配置、会话、桥接、持久化和 UI 边界，再以最小演示页面验证状态流。
避免从网页 DOM 移植业务代码；原型只作为交互与状态参考。

### 已有基础与剩余门槛

| 范围 | 已有基础 | P01 仍需完成 |
|---|---|---|
| Android 工程 | `:app -> :contracts`、demo 配置切片、四页导航、生命周期收集、主题组件及具名预览；41 项 JVM 测试（27 项领域/状态、14 项 UI）通过，20 张主题/场景图已渲染；API 36 设备 14 项 UI 用例与 20 张有效 PNG 验收通过 | API 24–25 尚未实测，系统栏、完整真机矩阵与完整无障碍验证不继承 JVM 或 API 36 结果 |
| 工具链 | Wrapper、版本目录、Daemon JVM；2026-10-12 的新 checkout 加任务补丁复建 demoDebug 通过，live 明确阻断；候选核心/NDK/Rust/ABI 与来源摘要已固定并自动核对 | P02 实际接入与生产批准分别验收，不阻塞 P01 demo 工程基础 |
| 质量检查 | Python 检查及 CI；四个 Android 入口、23 项工具回归、模块/import 检查、Android CI 配置；环境诊断与依赖迁移已[复验](#环境诊断与依赖迁移复验2026-10-13) | Android CI 的完整运行与源码定位；首次运行在 SDK 安装步骤失败，见 [CI SDK 安装入口修复](#ci-sdk-安装入口修复2026-10-13) |
| 协作与文档 | 根 AGENTS.md、目录边界 ADR、任务模板、开发环境文档 | 随实际切片补充入口、依赖检查和任务验收，不重复建立文档副本 |

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
7. 按目录边界 ADR 建立首条业务切片的 import 与模块依赖检查；仅保存不重连、本地改名不修改核心身份、应用失败保持旧 applied 状态均须覆盖。

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

以下 scripts/android 入口已创建并在本地运行，参数与产物见开发环境文档：

~~~bash
./scripts/android/doctor.sh
./scripts/android/check-fast.sh
./scripts/android/build-demo.sh
~~~

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

### 本地实施与复验记录（2026-10-12）

源码基线为 `a4b6572979a73473379692aac5064a03713d7b4b`，本次实现为其上的工作区改动。使用 Linux x86_64；Wrapper、AGP、Compose、SDK 与编译级别以构建配置为准。实测 Launcher Java 21.0.12.1、Daemon JDK 25、Python 3.14.8、uv 0.13.0、SDK Platform 37、build-tools 36.0.0；Doctor 另报告 NDK 30.0.16248370 和 Rust 1.99.0，它们不构成正式 native 工具链批准。

| 命令 / 环境 | 退出码与观察结果 |
|---|---|
| `./scripts/android/doctor.sh --offline`，本地 | 0；读取实际工具与 SDK；不修改全局配置 |
| `./scripts/android/check-fast.sh --offline`，本地 | 0；32 项 JVM 测试、15 项工具回归、Ruff、模块/import 和配置目录通过，JVM 无跳过 |
| `./scripts/android/build-demo.sh --offline`，本地 | 0；输出 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`，核对独立包名并打印 SHA-256 |
| `./gradlew --offline --console=plain :app:assembleDemoDebugAndroidTest` | 0；编译两个 Compose 测试类中的 13 项设备测试，未执行设备断言 |
| `./gradlew --offline :app:assembleLiveDebug` | 1；`verifyLiveEngine` 报 `LIVE_ENGINE_MISSING`，没有生成 live APK |
| `./scripts/android/doctor.sh --offline --native` | 3；明确报告 `NATIVE_NOT_READY`，不把本机 NDK/Rust 或 P00 产物当成正式接入 |
| `./scripts/android/verify-device.sh --offline --timeout 30` | 5；无在线设备，未安装或执行设备测试 |
| 新 checkout 未提供 SDK 路径，运行 doctor | 3；明确报告 `SDK_MISSING` |
| 当前源码的新 checkout 提供 `ANDROID_HOME` 后依次运行 doctor/check-fast/build-demo/设备测试编译 | 全部 0；分别约 0.52、13.45、3.67、1.62 秒，总计 19.25 秒；32 项 JVM 测试无失败、错误或跳过，生成 20 张渲染图 |

源码副本在忽略目录 `build/android/p01-checkout-current`：从本地仓库全新 clone，应用 `git diff --binary HEAD` 并复制 `git ls-files --others --exclude-standard` 列出的本任务文件。不复制 `local.properties` 或模块构建输出，SDK 通过标准 `ANDROID_HOME` 提供；Gradle/uv 依赖缓存复用。在副本根目录依次执行上表前三条命令和设备测试编译命令，命令、耗时和测试计数在该副本的 `build/android/source-rebuild/result.json` 与 `summary.json`，完整日志同目录；本地入口报告见 `build/android/<入口名>/`。本次不声称空缓存、第二主机或 APK 字节一致复建。

JVM 测试覆盖：改名不修改身份或重连、仅保存不改变 applied、失败及超时保留旧 applied、恢复配置保留本地名称、无效 raw/错误与静态地址保留、迟到会话/操作回执隔离、显式断开幂等、查看 B 与运行 A 分离，以及设置/导航保持草稿。所有测试逐例说明中文条件、操作和可观察结果，使用同一份具名 fixture 与虚拟调度器，不依赖公网或真实 sleep。

P01 剩余验收：Android Actions 实际运行。新 checkout 构建已满足本阶段的复建要求，独立主机/空缓存属于额外验证；完整真机矩阵和 TalkBack 实测在 P06 验收。P02 的服务/JNI/fd 所有权、生产补丁批准和 P03 的磁盘持久化/完整表单不属于本次 demo 验证。P00 冻结证据、运输摘要及生产签名配置保持原身份。

### 实施补齐与本地 UI 验收（2026-10-12）

- `gradle/native-candidate.json` 固定候选核心 SHA、NDK 30.0.16248370、Rust 1.99.0/commit、arm64-v8a 与 x86_64，以及冻结锁文件、补丁和参考构建摘要。doctor/check-fast 校验来源、版本和 `production_approved=false`；未改变正式构建的批准或签名配置。
- `check-fast.sh --offline` 完整执行 32 项 JVM 测试与 15 项工具回归，均通过、无跳过。UI 部分包含 10 个 fixture/外观条件和 3 项实际 demo Activity 交互，复用设备断言；剪贴板写入是在 JVM Android 模拟环境观察到的结果。
- Robolectric 4.17/API 37 native graphics 已生成 20 张图到 `app/build/p01-ui/`。浅/深/大字/高对比/动态色、六个具名场景的布局可渲染，字段可滚动到达；测试验证 heading、48 dp 主导航、唯一朗读名称、无效草稿跨页/设置返回保留和复制。
- 两次隔离软件模拟器启动分别在 240/600 秒内未完成，均已关闭。本机无 `/dev/kvm`；第二次已排除沙箱本地 ADB 权限并观察到 Android 初始化，但设备保持 offline。日志在 `build/android/emulator/`；未执行设备断言，不把 JVM 渲染写成设备或 TalkBack 通过。
- UI 首次运行发现传递 Espresso 3.5.0 调用 API 37 已移除的 `InputManager.getInstance()`；显式复用已有 3.7.0 后修复。JVM 没有真实显示 Surface，捕获采用 native Canvas；设备保持 PixelCopy。未删除行为断言、关闭检查或创建截图基线。
- 当前 checkout 没有 Git 远端；需要明确目标仓库后取得 Actions 运行 URL 与具体源码版本，才能关闭 CI 门槛。基础 UI、候选输入及新 checkout 验证不再作为未完成事项重复列入。

### 配置恢复与低版本截图修复复验（2026-10-13）

在同一源码基线上的工作区继续修复，保留公共契约、保存模型、依赖版本与 `minSdk=24`。待应用仅比较当前运行网络的已保存 `core`、`vpnPolicy` 与 applied；恢复仍创建新修订，内容一致后清除待应用，本地名称与 applied 不变且不提交引擎命令。设备截图仅在 API 26+ 执行；API 24–25 在创建目录或文件前记录用例、页面、API 和原因，保留全部 UI 断言，不将用例标记为跳过。

| 命令 / 环境 | 退出码与观察结果 |
|---|---|
| `./scripts/android/check-fast.sh --offline`，本地 | 0；41 项 JVM 测试（27 项领域/状态、14 项 UI），失败、错误、跳过均为 0；15 项工具回归、Ruff、模块/import 与配置目录通过 |
| `./scripts/android/build-demo.sh --offline`，本地 | 0；生成并核对 demoDebug APK 元数据；SHA-256 为 `9dacb1a536f13e2730f0ed3a6869f5ed9092b683b94f6634185435fd29d2514e` |
| `./gradlew --offline --console=plain :app:assembleDemoDebugAndroidTest` | 0；编译 14 项共享 UI 用例对应的设备测试 APK |
| `./scripts/android/verify-device.sh --offline --timeout 300` 与 `--timeout 180`，API 36、arm64 设备 | 均为 6；已安装 demo 与测试 APK，但用例执行超时，未产生完整设备测试报告或 PNG |
| 已安装 APK 的 `adb shell am instrument -w -r -e package com.github.zzwtsy.chuanmen.application com.github.zzwtsy.chuanmen.demo.test/androidx.test.runner.AndroidJUnitRunner`，120 秒预算 | 调用器返回 6；仅收到 1 项用例开始状态，0 项完成；未报告失败、错误或跳过，但整组结果不完整，不能登记设备通过 |
| `python3 scripts/validate_handoff.py --strict` | 0；文档、链接、配置与阶段运输摘要校验通过 |
| `python3 scripts/verification/check-privacy.py` | 0；工作区文件未命中已实现的隐私规则 |
| `python3 scripts/verification/check-evidence.py --stage p00 --require-stage` | 0；仅复核 P00 既有证据完整性与判据，没有重新执行 P00 真机实验 |

新增 7 项状态测试覆盖修订号、核心内容、VPN 策略、离线、查看另一网络、本地改名和未保存草稿；ViewModel 与共享 UI 各新增 1 项恢复回归。ViewModel 验证保存新 MTU 后待应用为真、恢复后为假，saved 修订递增、本地名称与 applied 保留、引擎命令数为 0；共享 UI 验证 MTU 从 1400 恢复为 1380，待应用提示及 Apply 按钮消失。新增和调整的测试逐例保留中文条件、操作与可观察结果。

JVM 报告在 `contracts/build/test-results/test/`、`app/build/test-results/testDemoDebugUnitTest/`；固定 API 37 native Canvas 生成 20 张 PNG 到 `app/build/p01-ui/`，已核对 PNG 签名、非零尺寸和结束块。快速检查与构建的运行记录在 `build/android/<入口名>/`，属于可覆盖的本地诊断输出。

首次设备安装被 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，Gradle/入口虽然返回 0，但没有测试报告，不登记为通过。允许安装后的某次运行记录了 4 项交互用例的 TestRunner 结束日志，随后停在首个展示场景；当时观察到熄屏与锁屏。解锁后再次运行及直接 instrumentation 仍超时，原因尚未定位。临时常亮已关闭、屏幕超时设置已恢复，残留 demo 测试进程已关闭。设备日志与部分状态摘要在 `build/android/verify-device/`；没有完整用例报告，不据结束日志宣称 14 项设备断言通过。

API 24–25 暂无测试设备或已安装的系统镜像，低版本的全部断言执行、无新增截图和原因日志尚未实测；API 36 的整组测试与有效 PNG 验收也未完成，不能推广为 API 26+ 通过。JVM API 37 结果不代替设备兼容性验收。本次未重复历史全新 checkout 复建、空缓存或独立主机验证，也不构成真实 VPN、系统栏或 TalkBack 验收；2026-10-12 的历史记录保持原范围。

### API 36 设备复试（2026-10-13）

复试时设备为 API 36、arm64，已亮屏、解锁，屏幕超时为 10 分钟。独立展示测试仍停在第一个用例，未进入测试正文，也没有创建截图文件。

只对本次 demo 进程读取 Java 线程栈，确认 instrumentation 线程等待在 `Instrumentation.startActivitySync()`，调用链来自 `ActivityScenarioRule.before`。系统记录同一 Activity 启动请求返回 `START_ABORTED (102)`；demo 包的 MIUI 后台启动权限 `10021` 为 `ignore`。这与启动被设备侧后台弹出限制取消一致，等待 Activity 创建的线程未进入 UI 断言或截图。

临时测试进度日志已移除，设备测试 APK 已重新编译通过；诊断用 JDWP 转发已移除，诊断测试进程已关闭。诊断日志放在忽略目录 `build/android/verify-device/retry-api36/`，不纳入阶段冻结记录。允许 demo 的后台弹出后，才能继续核对整组用例和有效 PNG。

### API 36 设备验收（2026-10-13）

用户允许后台弹出后，核对 demo 的 MIUI `10021=allow`，设备亮屏且已解锁。正常设备入口完成全部 14 项测试；其后默认卸载 APK 删除了截图缓存并重置授权。为导出截图，使用保留 APK 的单次运行选项复跑，但安装阶段再次重置授权，该次运行未完成。恢复用户已授予的后台弹出权限后，直接对已安装 APK 执行同一组用例，完成全部断言并取得截图。

| 命令 / 环境 | 退出码与观察结果 |
|---|---|
| `./scripts/android/verify-device.sh --offline --timeout 180`，API 36、arm64 设备 | 0；Gradle XML 包含 4 项 `DemoNavigationTest`、10 项 `DemoPresentationTest`，失败、错误、跳过均为 0 |
| 同一入口加 `ORG_GRADLE_PROJECT_android.injected.androidTest.leaveApksInstalledAfterRun=true` | 6；安装后后台弹出权限恢复为 `ignore`，运行未完成；APK 得以保留，不登记为设备通过 |
| `adb shell am instrument -w -r -e package com.github.zzwtsy.chuanmen.application com.github.zzwtsy.chuanmen.demo.test/androidx.test.runner.AndroidJUnitRunner`，恢复授权后的已安装 APK | 0；165.34 秒，14 项开始、14 项通过，失败、错误、忽略及假设跳过均为 0，runner 报告 `OK (14 tests)` |
| 导出 demo 的 `cache/p01-ui/` 并校验 PNG | 20/20 有效；每个展示用例各有总览与配置页截图，尺寸均为 1220 × 2440；逐张核对签名、块 CRC、IDAT 解压后的像素长度与行过滤、结束块 |

第一轮成功运行的 XML、入口结果与日志保存在忽略目录 `build/android/verify-device/retry-api36/first-passing-run/`；直接运行的输出和状态摘要为同级 `direct-full-ui.log`、`direct-full-ui-summary.json`，截图与结构校验摘要为 `p01-ui/`、`png-summary.json`。这两次通过结果分别登记；`build/android/verify-device/result.json` 对应中间未完成的复跑，不代替直接运行结果。

共享恢复回归在设备执行通过：保存 MTU 1400 后出现待应用，恢复运行配置后 MTU 回到 1380，待应用提示及 Apply 按钮消失。20 张截图由设备 PixelCopy 生成；已抽查浅色总览图可见实际 demo 页面内容。本次未修改源码、断言或依赖；用户已允许的后台弹出授权保留为 `allow`，屏幕超时仍为 600000 毫秒，USB 常亮设置仍为 0。

本次设备验收仅覆盖 API 36、arm64 的 demo UI 与截图。API 24–25 暂无设备或已安装系统镜像，低版本全部断言执行、无新增截图与原因日志尚未实测；不扩大为全部 API 26+、真实 VPN、系统栏或 TalkBack 通过。此前超时、安装受限和 2026-10-12 的历史记录保留原貌。

### CI SDK 安装入口修复（2026-10-13）

[Android foundation 首次运行](https://github.com/zzwtsy/Chuanmen/actions/runs/38382258126) 由 `workflow_dispatch` 触发，源码为 `7c746609f5f909961313d8ff57602704fbf4ae5c`，使用 Ubuntu 24.04、镜像 `20261004.327.1`。`Install declared SDK packages` 报 `sdkmanager: command not found`，退出码为 127；诊断、JVM 检查、demo 构建和设备测试编译四个后续步骤均未执行，不能登记为 CI 构建或测试通过。

官方已将 [`sdkmanager` 标为弃用](https://developer.android.com/tools/sdkmanager)，替代入口为 [`android sdk install`](https://developer.android.com/tools/agents/android-cli/commands/sdk_install)。工作流改为显式下载 Android CLI `1.0.16500706` 的启动器与组件，SDK 和 CLI 缓存使用 runner 临时目录，安装 `platforms/android-37.0` 与 `build-tools/36.0.0`。两种 SDK 环境变量指向同一目录，现有诊断、测试与构建入口继续复用；Gradle、项目依赖及最低支持版本未调整。

本地 Linux x86_64 已在空的临时 SDK 目录实测同版官方 CLI，`android --no-metrics --sdk=<临时目录> sdk install platforms/android-37.0 build-tools/36.0.0` 退出码为 0，stdin 关闭时仍完成安装。CLI 报告版本 `1.0.16500706`，SDK 安装结果不等于远端工作流通过。

随后原样提取并执行修复后的工作流安装步骤，核对 `GITHUB_ENV` 与 `GITHUB_PATH` 中的 SDK、缓存和 CLI 路径。以该步骤新安装的 SDK 在独立 checkout 复验，源码为上述提交加本次 CI 修复；未复制 `local.properties` 或构建输出，复用已有 Gradle/uv 依赖缓存。本地 Launcher Java 21、Daemon JDK 25，与远端 Launcher Java 25 分别报告。

| 命令 / 环境 | 退出码与观察结果 |
|---|---|
| 修复后的 SDK 安装步骤，本地临时目录 | 0；固定版本启动器与组件可下载，Platform 37.0 revision 2、build-tools 36.0.0 和许可记录已生成，SDK 环境变量一致 |
| `actionlint .github/workflows/android.yml .github/workflows/verification.yml`，1.7.12 | 0；工作流语法、上下文和步骤检查通过，安装脚本另通过 `bash -n` |
| `./scripts/android/doctor.sh --offline`，独立 checkout 与新 SDK | 0；约 0.66 秒，诊断新 SDK 与实际工具链 |
| `./scripts/android/check-fast.sh --offline`，同一 checkout | 0；约 22.70 秒，41 项 JVM 测试（contracts 3、app 38），失败、错误、跳过均为 0；15 项工具回归、Ruff、边界与配置目录通过 |
| `./scripts/android/build-demo.sh --offline`，同一 checkout | 0；约 6.34 秒，生成并核对 demo APK |
| `./gradlew --offline --console=plain :app:assembleDemoDebugAndroidTest`，同一 checkout | 0；约 2.53 秒，生成设备测试 APK，仅编译、未运行设备用例 |

本地诊断日志与计数摘要在忽略目录 `build/android/ci-sdk-retry/`；独立 checkout 的 JUnit XML 已逐项汇总，不以命令退出码单独推断测试通过。本次没有重新执行设备测试、真实 VPN 或空依赖缓存复建。

修复后的 GitHub Actions 尚未执行，Android CI 的完整运行门槛仍待远端复验；此前本地、设备及复建记录保留各自范围。

### 环境诊断与依赖迁移复验（2026-10-13）

SDK 诊断优先读取有效的本地 `sdk.dir`，支持相对目录及常见路径转义；无效本地目录回退到有效环境目录，环境目录冲突明确失败。Java 诊断优先使用 `JAVA_HOME`，不因 PATH 缺少 java 拒绝可用 JDK，也不回退绕过无效 `JAVA_HOME`。可选 Rust 诊断的命令失败保留日志并继续 demo，必需命令失败与超时仍阻断。新增 8 项中文说明的工具回归，合计 23 项。

Lifecycle 直接依赖 `lifecycle-runtime`，Kotlin 与 Compose 编译插件同版升级，应用、JVM 与设备测试统一 Compose BOM；两个共享 UI 契约使用 v2 测试入口，保留全部断言和 Canvas / PixelCopy 截图实现。实际解析得到 KGP 与 Compose 编译插件均为 2.4.21，Compose UI 与 UI 测试均为 1.12.1；权威版本仍以版本目录为准。新编译器指出的多余非空断言已移除，领域模型、生产公共接口和最低 API 24 保持原约定。

| 命令 / 环境 | 退出码与观察结果 |
|---|---|
| 固定 Ruff lint 与格式检查，`scripts experiments` | 均为 0；35 个 Python 文件格式通过 |
| `python3 scripts/android/test_android_tools.py` | 0；23 项工具回归，失败、错误、跳过均为 0，覆盖 SDK 优先级/冲突、JAVA_HOME 及可选 Rust 失败边界 |
| `./scripts/android/doctor.sh --offline`、`check-fast.sh --offline`、`build-demo.sh --offline`，本地 | 均为 0；41 项 JVM 测试（contracts 3、app 38），失败、错误、跳过均为 0；边界、配置目录和 demo APK 元数据通过；20 张 JVM PNG 结构有效 |
| `./gradlew --console=plain --warning-mode all :contracts:test :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest` | 0；获取新依赖后完成 JVM 回归、demo 与设备测试 APK 编译；编译告警修正后由快速检查再次通过受影响的 JVM 测试 |
| 当前 Android 工作流 SDK 安装步骤，本地临时 SDK | 0；声明的 Platform 37.0、build-tools 36.0.0 和新增 `platform-tools` 可安装；复用此前临时 SDK，不是空 SDK 复建 |
| `actionlint .github/workflows/android.yml .github/workflows/verification.yml`，1.7.12；安装脚本 `bash -n` | 均为 0；工作流与脚本语法通过 |
| `verify-device.sh --offline --timeout 300`，保留 APK，API 36 | 4；安装将 MIUI 后台弹出权限重置为 `ignore`，4 项交互通过后展示用例启动受阻；主动停止后 XML 登记 5 项、失败 1、错误 0、跳过 0，整组未完成 |
| 恢复权限后，直接执行已安装同一 APK 的 `adb shell am instrument -w -r -e package com.github.zzwtsy.chuanmen.application com.github.zzwtsy.chuanmen.demo.test/androidx.test.runner.AndroidJUnitRunner` | 0；runner 用时 27.798 秒，14 项开始、14 项通过，失败、错误、忽略及假设跳过均为 0，报告 `OK (14 tests)` |
| 导出本次新生成的设备 PNG | 20/20 有效，尺寸均为 1220 × 2440；核对签名、块 CRC、像素解压长度与行过滤、结束块 |
| live 构建与 `doctor.sh --offline --native` | 分别为预期的 1、3，明确报告 `LIVE_ENGINE_MISSING` 与 `NATIVE_NOT_READY` |

验证日志、解析版本、JUnit 汇总、设备直接运行输出与截图在忽略目录 `build/android/dependency-repair/`。正常设备入口的失败 XML 与直接 instrumentation 的成功结果分别登记，不将二者合并为 Gradle 设备测试通过。用户恢复的后台弹出权限保留；临时 USB 常亮恢复为 0，屏幕超时仍为 600000 毫秒。

Kotlin 当前版本在官方完全支持表中覆盖工程 Gradle；AGP 高于该表的完全支持上限，沿用内置 Kotlin 并核对实际解析与编译结果，不声称全部 AGP 功能已经验证。设备任务仍出现 AGP 传递依赖 `protobuf-java` 在 JDK 25 上调用 `sun.misc.Unsafe` 的上游弃用告警；未通过强制覆盖插件内部依赖或关闭告警处理。本次未推送或执行修复版远端 CI，API 24–25 未实测，未重复全新 checkout、空缓存或第二主机复建，不构成真实 VPN、系统栏或 TalkBack 验收；历史记录保留原范围。

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
