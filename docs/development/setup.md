# 开发环境与验证命令

本页说明实际构建和检查入口。阶段状态见 [路线图](../plan/ROADMAP.md)，边界见 [目录 ADR](../design/adr/0003-directory-boundaries.md)和[demo 装配 ADR](../design/adr/0004-demo-assembly.md)。当前提供 `demoDebug`、纯领域契约与 Android 脚本；正式 VPN/JNI 尚未接入，live 构建明确阻断。

## 环境与版本来源

| 项目 | 权威配置 | 使用要求 |
|---|---|---|
| 已有模块 | [settings.gradle.kts](../../settings.gradle.kts) | `:app -> :contracts`；不预建 VPN/JNI 空模块 |
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

诊断启动 Java 时与 Wrapper 一样优先使用 `JAVA_HOME/bin/java`，未设置时才查找 PATH；Daemon JVM 仍按工程配置选择。Rust 已安装但没有可用工具链时，doctor 保留失败日志并提示 demo 不要求 Rust；必需工具失败和命令超时仍返回失败。

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

快速检查核对原生候选输入，再执行固定 Ruff、Android 工具回归、模块/import 边界、配置目录审计，以及 `:contracts:test :app:testDemoDebugUnitTest`，不操作设备。JVM 报告在 `contracts/build/reports/tests/test/` 和 `app/build/reports/tests/testDemoDebugUnitTest/`。其中 27 项领域/状态测试与 14 项 Robolectric UI 测试分别覆盖业务不变量和真实 demo Activity 的界面绑定；20 张 UI 渲染图在 `app/build/p01-ui/`。JVM native Canvas 渲染不表示设备、系统栏或 TalkBack 已验收。

共享 UI 契约使用 [Compose v2 测试入口](https://developer.android.com/develop/ui/compose/testing/migrate-v2)，默认 `StandardTestDispatcher` 将组合协程排队；语义操作、`waitForIdle` 与 `runOnIdle` 负责断言前的同步。测试使用 JUnit 4 与既有 Robolectric / 设备入口。Lifecycle 的 KTX API 已移入 [`lifecycle-runtime`](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.8.0)，工程直接依赖该模块。

Kotlin 版本须同时核对 [Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)和实际构建：当前固定版覆盖工程的 Gradle 版本，AGP 版本高于该表的完全支持上限；Android 模块沿用 AGP 的[内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)，通过根插件声明使用[更高版本的 KGP](https://developer.android.com/build/releases/agp-9-0-0-release-notes)。实际解析、构建与设备结果见 [P01 环境与依赖复验](../plan/p01-foundation.md#环境诊断与依赖迁移复验2026-10-13)，不推导其他 AGP 功能兼容性。

四个入口支持 `--help`、`--offline`、`--timeout SECONDS`，默认每条子命令超时 600 秒。日志和 `result.json` 放 `build/android/<入口名>/`；退出码：0 成功、2 参数错误、3 前置缺失、4 子任务失败、5 设备阻塞、6 超时。每次覆盖该入口的临时报告，历史结论不依赖日志永久保留。

## demo fixture 与设备入口

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

正式入口的负向验证命令：

```bash
./gradlew --offline :app:assembleLiveDebug
./scripts/android/doctor.sh --offline --native
```

P01 中分别预期返回 Gradle 1（`LIVE_ENGINE_MISSING`）和脚本 3（`NATIVE_NOT_READY`）。生产补丁仍未批准，不可用 fake 或 P00 实验制品绕过门槛。

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
| Android 边界 | `python3 scripts/android/check_boundaries.py` | 当前模块、切片和 demo/live 直接引用符合规则 |
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
