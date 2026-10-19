# 开发环境与验证命令

本页说明实际构建和检查入口。阶段状态见 [路线图](../plan/ROADMAP.md)，边界见 [目录 ADR](../design/adr/0003-directory-boundaries.md)和[demo 装配 ADR](../design/adr/0004-demo-assembly.md)。当前提供 demoDebug、领域契约、正式 bridge/VPN 模块与 liveDebug 构建。live 必须核对本次正式双 ABI 产物，不读取 P00 的生产批准标记；设备和数据通路结果见 P02 阶段记录。

## 环境与版本来源

| 项目 | 权威配置 | 使用要求 |
|---|---|---|
| 已有模块 | [settings.gradle.kts](../../settings.gradle.kts) | `:app -> :contracts`；live 经 `:vpn -> :core-bridge -> :contracts` 使用实际 JNI，核心与 PFD 由 Service 持有 |
| Gradle | [Wrapper 配置](../../gradle/wrapper/gradle-wrapper.properties) | 使用仓库的 `./gradlew` |
| Gradle Daemon JDK | [Daemon JVM 配置](../../gradle/gradle-daemon-jvm.properties) | 按 `toolchainVersion` 准备 JDK；它与 Java 编译兼容级别分别配置 |
| AGP、Kotlin、Compose 编译插件及 Android 依赖 | [版本目录](../../gradle/libs.versions.toml)和[根构建](../../build.gradle.kts) | Kotlin 与 Compose 编译插件使用同版；应用、JVM 和设备 UI 测试共用 Compose BOM |
| JVM UI runtime | [版本目录](../../gradle/libs.versions.toml)和[app 构建](../../app/build.gradle.kts) | Robolectric 4.17 与固定 API 37 instrumented jar；仅测试依赖，runtime 由 Gradle 解析，Robolectric 禁止自行下载 |
| Android SDK、构建工具与 Java 编译级别 | [app/build.gradle.kts](../../app/build.gradle.kts) | 安装 `compileSdk` 与 `buildToolsVersion` 指定版本；`minSdk` 不代表最低系统已验收 |
| CI SDK 安装工具 | [Android 工作流](../../.github/workflows/android.yml) | Android CLI 启动器与组件固定同一版本；SDK 与 CLI 缓存位于 runner 临时目录 |
| Python | 工具源码与[实验说明](../../experiments/android-vpn-smoke/README.md) | scripts 使用 Python 3.10+ 标准库；实验离线回归使用 Python 3.11+ |
| 原生接入候选 | [候选输入清单](../../gradle/native-candidate.json) | 固定核心、NDK、Rust、ABI 和冻结来源摘要；doctor/check-fast 自动核对，生产批准仍为 false |
| uv 与 CI Python | [CI 配置](../../.github/workflows/verification.yml) | 安装 uv；CI 固定工具版本与 Python 环境 |
| Ruff | [ruff.toml](../../ruff.toml) | 固定版本与格式；scripts / experiments 分别按对应 Python 最低版本检查 |
| 实验工具链 | [实验声明](../../experiments/android-vpn-smoke/experiment.json)与[复建入口](../../experiments/android-vpn-smoke/rebuild.py) | NDK、Rust、核心及补丁属于实验或后续正式 native 工程的要求 |

demo 使用 Android/Kotlin 工具链，无真实 VPN、NDK 或网络秘密要求。完整 native 复建另有输入和环境要求；P00 实验与正式产品分别定位，更新其中一方不能替代另一方的验证。

## 首次搭建与构建

1. 在 Android Studio 打开仓库根目录，按上述配置准备 Daemon JDK 和 Android SDK。
2. 通过 SDK Manager 安装 `compileSdk` 对应平台、固定构建工具和 `platform-tools`。SDK 优先使用 `local.properties` 中有效的 `sdk.dir`，再使用环境配置；相对路径按仓库根目录解析，本机目录不进入版本库。
3. 首次 Gradle 同步与构建允许获取 Wrapper、插件和依赖。自动工具链解析可能访问配置中的分发来源；受限网络环境需预先准备对应缓存。
4. 安装 Python 和 uv 后，从根目录运行诊断、快速检查与 demo 构建。

诊断启动 Java 时与 Wrapper 一样优先使用 `JAVA_HOME/bin/java`，未设置时才查找 PATH；Daemon JVM 仍按工程配置选择。Rust 已安装但没有可用工具链、命令普通非零退出时，doctor 保留失败步骤和日志并标记 `failure_handled=true`，提示 demo 不要求 Rust；必需工具失败、启动失败、超时、中断、日志或清理错误仍阻断。

需要直接调用下文的 ADB 命令时，将同一 SDK 的位置设为 `ANDROID_HOME`，并把 `platform-tools` 加入 PATH。若保留旧的 `ANDROID_SDK_ROOT`，它须与 `ANDROID_HOME` 一致；仅设置 `sdk.dir` 不会自动配置 shell 的 PATH。

```bash
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

无界面 CI 使用官方 [Android CLI](https://developer.android.com/tools/agents/android-cli/download) 的 [`android sdk install`](https://developer.android.com/tools/agents/android-cli/commands/sdk_install) 安装工程声明的包；[`sdkmanager` 已弃用](https://developer.android.com/tools/sdkmanager)。Android 工作流显式下载固定版本的启动器与组件，不依赖 runner 预装命令或 SDK；`ANDROID_HOME` 与 `ANDROID_SDK_ROOT` 指向同一个临时 SDK 目录，后续诊断、测试和构建复用该目录。SDK 安装结果与完整 CI 验收分别记录，见 [CI SDK 安装入口修复](../plan/p01-foundation.md#ci-sdk-安装入口修复2026-10-13)。

```bash
./scripts/android/doctor.sh
./scripts/android/check-fast.sh
./scripts/android/build-demo.sh
```

缓存齐备后的离线复验命令为：

```bash
./scripts/android/doctor.sh --offline
./scripts/android/check-fast.sh --offline
./scripts/android/build-demo.sh --offline
```

离线模式要求 Wrapper、插件、依赖、SDK 与 JDK 已齐备。新增 Kotlin/JVM 插件还需其插件标记；缺缓存时依据 Gradle 错误从已配置的官方来源补齐。默认 Gradle/uv 缓存目录在只读沙箱外时，需要宿主允许正常缓存写入，或预备可写缓存；权限失败不算测试通过。

APK 为 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`，包名后缀 `.demo`、名称“串门 · 演示”，常驻“模拟数据”标记。脚本核对产物元数据并输出路径和 SHA-256。签名沿用标准 debug 配置，正式 release 设置和签名配置未改动。

快速检查核对原生候选输入，再执行固定 Ruff、Android/native 工具回归、模块边界的临时 Gradle 工程回归、源码 import 边界、配置目录审计，以及 `:contracts:test :core-bridge:test :vpn:testDebugUnitTest :app:testDemoDebugUnitTest`，不操作设备或构建 Rust。正式 Gradle 调用加载 `scripts/android/check_module_boundaries.init.gradle.kts`，同时执行 `checkModuleBoundaries`；配置阶段先通过 `incoming.dependencies` 执行全部延迟声明，再读取各配置的直接依赖，任务执行阶段只消费普通输入值，不解析依赖制品。自依赖豁免仅限 AGP 公共测试组件模型中的 compile/runtime 配置，手工可解析配置及仿测试名称均不获得豁免。

JVM 报告在各模块 `build/reports/tests/` 下；领域/状态与 Robolectric UI 测试分别覆盖业务不变量和真实 demo Activity 的界面绑定，测试计数以对应运行报告为准。UI 渲染图在 `app/build/p01-ui/`。JVM native Canvas 渲染不表示设备、系统栏或 TalkBack 已验收。受控配置输入测试位于 `testLiveDebug`，由下文的正式 live JVM 检查执行，不属于 demo 快速检查。

单独检查 Gradle 模型时运行：

```bash
./gradlew --offline --init-script scripts/android/check_module_boundaries.init.gradle.kts checkModuleBoundaries
python3 scripts/android/test_module_boundaries.py
```

临时工程回归使用 Wrapper、内置 Java 插件和离线模式，验证合法设备测试依赖、延迟声明、跨配置回调、错误配置、反向/自依赖、缺失边、Android 平台依赖/插件及配置缓存复用；Android 插件身份使用合成 fixture，不需要 Android 编译或 native 产物。真实 AGP 测试组件的正常接线由正式工程检查验证。

共享 UI 契约使用 [Compose v2 测试入口](https://developer.android.com/develop/ui/compose/testing/migrate-v2)，默认 `StandardTestDispatcher` 将组合协程排队；语义操作、`waitForIdle` 与 `runOnIdle` 负责断言前的同步。测试使用 JUnit 4 与既有 Robolectric / 设备入口。Lifecycle 的 KTX API 已移入 [`lifecycle-runtime`](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.8.0)，工程直接依赖该模块。

Kotlin 版本须同时核对 [Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)和实际构建：当前固定版覆盖工程的 Gradle 版本，AGP 版本高于该表的完全支持上限；Android 模块沿用 AGP 的[内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)，通过根插件声明使用[更高版本的 KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes)。实际解析、构建与设备结果见 [P01 环境与依赖复验](../plan/p01-foundation.md#环境诊断与依赖迁移复验2026-10-13)，不推导其他 AGP 功能兼容性。

四个入口支持 `--help`、`--offline`、`--timeout SECONDS`，默认每条子命令超时 600 秒。日志和 `result.json` 放 `build/android/<入口名>/`；退出码：0 成功、2 参数错误、3 前置缺失、4 子任务失败或报告写入失败、5 设备阻塞、6 超时、130 SIGINT 中断、143 SIGTERM 中断。每次原子替换该入口的临时报告，历史结论不依赖日志永久保留；报告写入失败经 stderr 输出 `REPORT_WRITE_FAILED`，保留的旧报告不代表本次结果。

有效命令调用的前置失败也生成顶层报告。`schema_version=1` 保留原有 `command`、`exit_code`、`steps`，新增顶层 `status` 和 `failure`（成功时为 `null`，失败时含 `message`、`step`）。状态为 `passed / failed / blocked / timed_out / interrupted`。每个已尝试步骤保留 `label`、`exit_code`、`elapsed_seconds` 和 `log`，新增字段如下：

| 步骤字段 | 含义 |
|---|---|
| `status`、`timed_out`、`interrupted` | 步骤结果；超时或中断后，即使子进程返回 0 也不成功 |
| `interruption_signal`、`termination_signal` | CLI 接收的中断信号与直接子进程实际终止信号分别记录，未发生时为 `null` |
| `cleanup_scope` | POSIX 为 `process_group`，其他平台为直接子进程 `process` |
| `cleanup_complete`、`cleanup_signals`、`cleanup_error` | 是否完成清理、发出的清理信号和清理错误；未请求清理时为 `null`、空列表、`null` |
| `log_error`、`failure_handled` | 日志写入错误和 doctor 已处理的可选诊断失败 |

缺工具、启动失败、非零退出、超时与中断均有步骤记录；未启动进程时，退出码和终止信号为 `null`。每步日志保留 stdout 和 stderr，沿用先 stdout 后 stderr 的合并方式，不承诺两路输出的时间顺序。前置失败可能没有步骤，参数错误由 argparse 返回 2。

每次 POSIX 执行建立独立进程组，超时或中断先发送 SIGTERM 并等待最多 5 秒，仍有运行成员时发送 SIGKILL 并再等待最多 5 秒；直接子进程退出后仍检查衍生成员，Linux 排除已结束的僵尸进程。超过预算明确记录清理未完成，不无限等待。清理只操作本次创建的组，不能终止其他任务或组外的共享 Gradle daemon。非 POSIX 使用直接子进程清理，本轮进程组验收针对 Linux。SIGINT / SIGTERM 处理只在 CLI 调用期间安装，退出时恢复宿主处理器。

## demo fixture 与设备入口

契约与 demo 行为回归使用 `:contracts:test :app:testDemoDebugUnitTest`，包括阶段进度、取消、停止未确认、应用回执丢失及整数计数。`app/src/liveDebug/.../configuration/controlled/ControlledProfileInput.kt` 是只接受显式参数的内存配置入口，对应测试位于标准源集 `testLiveDebug`，由 `:app:testLiveDebugUnitTest` 执行；demo 不挂载 liveDebug 源码，liveRelease 单测也不编译该调试测试。当前 AGP 默认仅启用 debug 主机测试，构建通过 `HostTestBuilder` 显式启用 liveRelease 单测以验证 release 隔离。真实密码和对端不写入源码、Gradle 参数或测试报告。liveDebug 的受控表单初始化内存仓库，正式 Service 只消费仓库中的已保存修订。`MemoryProfileRepository` 同时供 demo 与受控输入使用，不提供持久化；完整表单、加密保存及进程恢复仍属 P03。

具名场景见 `app/src/demo/java/com/github/zzwtsy/chuanmen/application/demo/DemoFixtures.kt`：空网络、未连接、已连接、连接失败、应用失败、无采样。运行入口使用已连接 fixture；Preview 与 JVM 测试复用同一份数据。仓库和外观偏好只在内存保留；Apply 模拟回执，不承诺核心热应用或 VPN 重建。

恢复运行配置到已保存会创建新修订，保留本地名称和 applied，不调用引擎。待应用由当前运行网络的已保存核心配置与 VPN 策略是否不同决定；内容恢复一致后，修订号不同也不显示“更改待应用”或 Apply 按钮。

只编译已实现的 Compose 设备测试，不安装 APK：

```bash
./gradlew --offline :app:assembleDemoDebugAndroidTest
```

连接且仅连接一个已授权的 API 24+ 测试设备后运行：

```bash
./scripts/android/verify-device.sh --offline
```

可用 `--serial` 核对唯一设备；多设备、未授权、离线或不存在时返回 5，不执行安装。入口检查 ADB 和系统 API，再安装 demo 执行 application 包中的 `DemoNavigationTest` 与 `DemoPresentationTest`，共 14 项：验证无效草稿跨页/设置返回保留、保存新 MTU 后恢复运行配置并消除待应用、模拟标记/剪贴板写入，以及六个具名场景和浅/深/大字/高对比/动态色的渲染、heading、48 dp 导航触控尺寸及唯一朗读名称。设备报告在 `app/build/reports/androidTests/connected/debug/flavors/demo/`。

入口在运行前将旧 XML 和旧 `device-tests.json` 移至 `build/android/verify-device/previous-results-*/`，保留历史失败材料；使用 `--rerun-tasks` 强制本次执行。Gradle 返回 0 后，仍须核对 `app/build/outputs/androidTest-results/connected/debug/flavors/demo/` 中的 JUnit XML：当前 14 项身份完整、无重复、失败/错误/跳过均为 0，计数与节点一致。缺报告、损坏或不完整均返回 4，不能以旧报告或成功退出码登记通过。校验成功才生成 `build/android/verify-device/device-tests.json` 的用例摘要。新增共享用例时同步 `android_tools.py` 的 `DEVICE_TEST_CASES` 与本节。

测试期间设备须保持亮屏、解锁并允许 USB 安装；MIUI 设备还需在“串门 · 演示 → 其他权限”允许“后台弹出界面”。安装或卸载可能重置该授权；系统取消 Activity 启动时，测试会停在 `ActivityScenarioRule`，尚未进入 UI 断言或截图。

API 26+ 通过 PixelCopy 在测试设备 demo 应用的 `cache/p01-ui/` 生成 20 张渲染图；截图失败仍导致测试失败。API 24–25 仅省略截图，在创建目录、文件之前用 `P01_UI_CAPTURE` 日志记录用例、页面、API 和原因，全部 14 项 UI 用例及断言继续执行，不使用 JUnit 跳过机制。低版本验收须核对失败、错误、跳过均为 0，没有新增截图文件且有对应省略日志；高版本须核对有效 PNG。这些图是临时复核图，不是截图基线。同一组断言由 JVM 和设备入口复用，两类结果分别登记；实际执行结果见 [P01 修复复验](../plan/p01-foundation.md#配置恢复与低版本截图修复复验2026-10-13)与 [API 36 设备验收](../plan/p01-foundation.md#api-36-设备验收2026-10-13)，历史结果保留在同页。

AGP 默认在测试结束后卸载 APK，截图缓存随之删除。需要导出截图时，可用以下单次运行选项保留 APK 与缓存：

```bash
env 'ORG_GRADLE_PROJECT_android.injected.androidTest.leaveApksInstalledAfterRun=true' \
    ./scripts/android/verify-device.sh --offline
```

若安装阶段重置了后台弹出授权，恢复授权并核对亮屏、解锁后，可直接运行已安装的同一组用例，避免再次安装：

```bash
adb shell am instrument -w -r \
    -e package com.github.zzwtsy.chuanmen.application \
    com.github.zzwtsy.chuanmen.demo.test/androidx.test.runner.AndroidJUnitRunner
```

此入口的结果在 instrumentation 输出中，需核对 14 项开始、14 项通过以及失败、错误、跳过均为 0；不将其与 Gradle XML 报告混为一次运行。截图可通过 `adb exec-out run-as com.github.zzwtsy.chuanmen.demo cat cache/p01-ui/<文件名>.png` 导出到忽略目录。

正式原生输入与构建入口：

```bash
python3 scripts/native/build_native.py check-inputs
python3 scripts/native/build_native.py prepare --allow-download
python3 scripts/native/build_native.py build --sdk "$ANDROID_HOME"
python3 scripts/native/build_native.py verify-artifacts
./scripts/android/doctor.sh --offline --native
./gradlew --offline :app:assembleLiveDebug :app:assembleLiveDebugAndroidTest :app:testLiveDebugUnitTest :app:testLiveReleaseUnitTest
python3 scripts/native/check_apk.py --sdk "$ANDROID_HOME" app/build/outputs/apk/live/debug/app-live-debug.apk
```

首次构建需要固定 Rust 1.99.0、cargo-ndk 4.1.2、NDK 30.0.16248370、Android Rust 双目标、libclang、protoc 及锁定依赖缓存；`--allow-download` 显式允许获取固定来源和依赖，后续默认离线。SDK 参数使用本机有效 SDK_ROOT，不能照抄不存在的目录。正式输入在 [native/inputs.json](../../native/inputs.json)，来源 checkout 位于忽略的 `build/native/upstream`，双 ABI 库及构建报告位于 `build/native/`。源码、补丁、锁文件或构建脚本变化使旧报告失效，须重新构建；不能用 P00 实验库解锁 live。

liveDebug 仅交付 arm64-v8a、x86_64。APK 门槛同时核对实际打包库字节、ELF LOAD 对齐及 zipalign 16 KB 检查；这些静态检查不代表 16 KB runtime 通过。普通 live 构建只核对正式 native 输入与双 ABI 产物，服务和桥接接线由正式变体编译验证。正式桥的宿主测试入口为 `cargo test --manifest-path native/Cargo.toml --locked --offline`，原生工具回归为 `python3 -m unittest discover -s scripts/native -p 'test_*.py'`。

P02 阶段补丁审查在实验目录独立执行，缺失、过期或范围不符均失败；普通构建和 live 编译输入快照不依赖阶段审查文件：

```bash
python3 experiments/p02-vpn-core/check_review.py
python3 -m unittest discover -s experiments/p02-vpn-core -p 'test_*.py'
```

Android CI 显式执行这些阶段检查，并在同一必需工作流中执行 demo、桥/服务回归、固定 native 构建、liveDebug/liveRelease 单测、live APK 与 APK 对齐检查；任一失败使 quality-gate 失败。工程自审不代表独立审阅或公开发行批准，本地成功不代表远端已执行。固定历史运行的记录复核入口保留至 P02 退出，退出后连同其测试及 CI 调用退役，见 [P02 收尾安排](../plan/p02-vpn-core.md#53-结构收尾与退出准备)。

变更分类将 `experiments/p02-vpn-core/` 及 `docs/verification/p02/native-review.json`、`native-review.md` 视为实际 CI 输入；这些路径单独变化也必须执行 Android/阶段检查，不随一般实验和文档省略。

正式设备判据、预算与关闭安排见 [P02 设备验收](../../experiments/p02-vpn-core/README.md)。MIUI 上正式“串门”的后台弹出权限与“串门 · 演示”分别授予；未允许时 ActivityScenario 无法进入授权流程。JNI pipe 测试仅支持借用协议结论，不能当作系统 TUN 或业务连通通过。

正式设备入口显式选择 live，默认 demo 行为不变：

```bash
./scripts/android/verify-device.sh --offline --mode live --live-case bridge
./scripts/android/verify-device.sh --offline --mode live --live-case lifecycle --serial emulator-5580 --timeout 180
./scripts/android/verify-device.sh --offline --mode live --live-case revoke --serial emulator-5580 --timeout 180
./scripts/android/verify-device.sh --offline --mode live --live-case datapath \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report build/experiments/p02-vpn-core/UNIQUE_RUN/peer/ready.json
./scripts/android/verify-device.sh --offline --mode live --live-case resources \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report build/experiments/p02-vpn-core/UNIQUE_RUN/peer/ready.json --timeout 960
./scripts/android/verify-device.sh --offline --mode live --live-case dns \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report build/experiments/p02-vpn-core/UNIQUE_RUN/peer/ready.json
./scripts/android/verify-device.sh --offline --mode live --live-case policy \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report build/experiments/p02-vpn-core/UNIQUE_RUN/peer/ready.json
./scripts/android/verify-device.sh --offline --mode live --live-case network \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report build/experiments/p02-vpn-core/UNIQUE_RUN/peer/ready.json
```

对端由 [host_peer.py](../../experiments/p02-vpn-core/host_peer.py)启动，须提供匹配构建报告、已核验的私有 LAN 地址与独立运行目录。既有 datapath 可使用固定无补丁核心；resources、dns 使用正式来源的用户态 DNS/状态对端。network 另需 `--ipv6-address` 指定已核验的宿主全局 IPv6 高端口回显地址。占位符替换为本轮实际参数，不传真实网络密码；fixture 只使用源码中声明的公开合成网络身份。

正式受控对端构建与身份检查：

```bash
python3 scripts/native/build_peer.py build
python3 scripts/native/build_peer.py check
python3 experiments/p02-vpn-core/host_peer.py \
    --binary experiments/p02-vpn-core/native-peer/target/debug/chuanmen-controlled-peer \
    --build-report build/native/controlled-peer/build-report.json \
    --bind-ip PEER_LAN_IPV4 --out build/experiments/p02-vpn-core/UNIQUE_RUN/peer \
    --run-id p02-UNIQUE_RUN --duration 1200 --dns-peer
```

对端默认不创建宿主 TUN 或修改系统路由；用户态虚拟 DNS 为 53，私有 LAN 状态入口为 31274。运行目录必须新建，待 ready 收据生成后执行设备入口；设备停止确认并归档结果后，在该 peer 目录创建 `stop.request`，核对 `report.json` 的核心、线程及 DNS socket 停止结果。

`bridge` 验证三项 JNI/pipe 契约；`revoke` 只在本任务 API37 英文模拟器通过正常设置 Forget VPN 验证实际系统撤销，不操作个人真机；`lifecycle` 用新建、尚未授权的模拟器验证正常系统拒绝、取消、迟到授权、TUN 安装与停止，不要求业务对端。`datapath` 要求双外层双业务协议的本轮回包；`resources` 要求 4 轮预热、完整 50 轮和 108 次对端关联，并逐轮核对对端旧连接与路由收束。`dns` 核对正常系统 resolver 的双外层实际回答；`policy` 核对 DHCP、默认路由和配置失败后的显式恢复；`network` 短暂关闭并恢复原已启用的 Wi-Fi，以 IPv6 前后正向回包验证 VPN 内阻断。缺参数、报告或前置时登记未执行，失败不回退 demo。

live 使用 adb 固定目标；多设备时可用 `--serial` 显式选择，未指定时仍要求唯一在线设备。Activity 场景先核对亮屏、解锁并启动正式界面；测试窗口保持亮屏，不绕过锁屏或系统授权。相同 APK 摘要跳过重复安装；变更 APK 使用 `install -r`。API 24/37 的自动点击只针对测试模拟器的系统授权按钮，不修改 AppOps。

报告在 `build/android/verify-device/live/<case>/`；本轮开始先归档该目录中的旧文件，成功须同时核对实际用例和应用报告。`source-inputs.json` 在构建前后及设备执行后核对输入不变，并绑定 APK 摘要。CLI 超时只结束自己创建的 adb 进程，不等于设备上的 Service 已停止；未确认资源仍需保留并核对，不直接 force-stop。模拟器基本生命周期与真机数据通路分别登记。

## Python lint 与格式

uv 使用隔离工具环境运行 Ruff。首次获取工具需要 PyPI 网络访问，工具缓存齐备后可给 `uv tool run` 增加 `--offline`。

```bash
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check --no-cache scripts experiments
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --no-cache --check scripts experiments
```

格式检查只读；需要修改格式时去掉第二条命令的 `--check`，随后运行受影响的回归。源码脚本继续使用标准库。

规则例外限定到具体 CLI 文件或有中文理由的语句。`RUF100` 检查失效的 `noqa`。子进程需要保留输出再判定退出码时显式使用 `check=False`；需要立即抛出失败时使用 `check=True`。既有 Python 入口通过 `python3` 按路径调用。

## 回归与结构检查

按变更选择相关入口；Python 回归包括：

```bash
python3 scripts/catalog/test_audit_config_catalog.py
python3 scripts/verification/test-evidence.py
python3 experiments/android-vpn-smoke/test_workflow.py
python3 scripts/android/test_android_tools.py
python3 scripts/git/test_workflow_policy.py
```

| 检查 | 命令 | 可支持的结论 |
|---|---|---|
| Android 源码边界 | `python3 scripts/android/check_boundaries.py` | 仅检查源码 import、切片、页面和 demo/live 引用；完整模块依赖检查由 check-fast 加载 Gradle 任务执行 |
| 配置映射与台账 | `python3 scripts/catalog/audit_config_catalog.py` | 冻结目录、来源覆盖与实施台账结构一致 |
| 文档与交付结构 | `python3 scripts/validate_handoff.py --strict` | 所需文件、本地链接、配置和阶段运输摘要可检查 |
| 隐私 | `python3 scripts/verification/check-privacy.py` | 选定文件集合未命中已实现的隐私规则 |
| P00 既有证据 | `python3 scripts/verification/check-evidence.py --stage p00 --require-stage` | 既有观察与判据、身份和阶段声明一致 |

这些检查分别报告静态、结构、回归或既有证据结果。文档结构通过仍需人工核对叙述是否符合当前代码；普通 Python 回归与模板构建不产生新的 native 或真机验收。

准备暂存交付时，对选定的 Git 索引再检查：

```bash
python3 scripts/verification/check-privacy.py --tree index
python3 scripts/verification/check-evidence.py --tree index --stage p00 --require-stage
python3 scripts/validate_handoff.py --tree index --strict
```

`--tree index` 读取暂存内容，工作区修改须按准备交付的范围暂存后才会被这些检查采用。不要通过刷新摘要掩盖冻结记录变化。

## 原型与实验入口

原型重建使用：

```bash
python3 scripts/prototype/export_prototype.py
```

它会更新原型片段和页面，维护顺序见 [原型说明](../../prototype/README.md)。根 `SHA256SUMS` 属于 P00 运输范围，原型修改不刷新这份阶段清单。

实验的离线测试、复建预览、源码导出及实际设备运行条件见 [实验 README](../../experiments/android-vpn-smoke/README.md)。设备命令需要对应授权与环境；demo 构建、快速检查和 Python 检查不操作设备。

## CI 与输出

现有 [Python 工作流](../../.github/workflows/verification.yml) 保留 uv + Ruff、回归、隐私、目录和 P00 判据检查，并运行 PR/门禁回归。[Android 工作流](../../.github/workflows/android.yml) 调用相同 doctor/check-fast/build-demo 入口（含 JVM UI 渲染）并编译设备测试，安装声明的 SDK 平台、构建工具和 `platform-tools`。两者由 [统一 CI](../../.github/workflows/ci.yml) 复用，保留手动入口；验证每次执行，Android 按变更分类执行。PR 另有 `pr-policy`，最终 `quality-gate` 拒绝失败、取消与意外跳过，规则见 [Git 工作流](git-workflow.md)。

已有 `main` 源码的 Python 与 Android 远端运行通过，SHA 与链接见 [P01 CI 记录](../plan/p01-foundation.md#远端-ci-通过记录2026-10-14)。首次 SDK 安装失败保留在历史记录中；新工作流须以本次实际 Actions 结果验收。工作流修改使用固定 `actionlint 1.7.12` 检查 `.github/workflows/*.yml`。本地与远端结果分别报告，CI 不执行真机或产品发布验收。

本地构建、缓存、私密配置和实验输出按 [.gitignore](../../.gitignore) 放入忽略范围。普通检查的结果保留在执行报告或临时输出中；只有支撑技术结论或阶段门槛的筛选材料才按 [验证规范](verification.md) 发布。

## 文档同步

工程事实按来源维护：

- 阶段进度与验收归路线图及对应阶段文件；模板、工具或目录维护不自动完成阶段。
- 版本归构建配置、工具配置与锁文件；本页提供定位和使用方法。
- 目录与依赖决策归 ADR；源码和命令变动同步实际调用点、文档及 CI。
- 技能归各 `SKILL.md`，安装来源归锁文件，维护策略归 [技能策略](agent-skills-strategy.md)。
- 历史验证记录保留执行时的身份与范围；当前导航解释它们与现有工程的关系。

更新代码或能力时，核对 README、AGENTS、环境入口及受影响计划中的相关说明。常规维护不另建一份重复状态快照。
