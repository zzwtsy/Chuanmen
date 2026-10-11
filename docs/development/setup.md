# 开发环境与验证命令

本页说明当前仓库实际具备的构建和检查入口。阶段状态见 [路线图](../plan/ROADMAP.md)，目标工程边界见 [目录 ADR](../design/adr/0003-directory-boundaries.md)。正式 demo 变体、VPN/JNI 产品模块和 `scripts/android/` 通用工具仍按阶段实施。

## 环境与版本来源

| 项目 | 权威配置 | 使用要求 |
|---|---|---|
| 已有模块 | [settings.gradle.kts](../../settings.gradle.kts) | 当前只有 `:app` |
| Gradle | [Wrapper 配置](../../gradle/wrapper/gradle-wrapper.properties) | 使用仓库的 `./gradlew` |
| Gradle Daemon JDK | [Daemon JVM 配置](../../gradle/gradle-daemon-jvm.properties) | 按 `toolchainVersion` 准备 JDK；它与 Java 编译兼容级别分别配置 |
| AGP、Compose 编译插件及 Android 依赖 | [版本目录](../../gradle/libs.versions.toml)和[根构建](../../build.gradle.kts) | 随配置获取固定版本，升级单独评审 |
| Android SDK 与 Java 编译级别 | [app/build.gradle.kts](../../app/build.gradle.kts) | 安装 `compileSdk` 指定的平台；`minSdk` 不代表已经完成最低系统的产品验收 |
| Python | 工具源码与[实验说明](../../experiments/android-vpn-smoke/README.md) | scripts 使用 Python 3.10+ 标准库；实验离线回归使用 Python 3.11+ |
| uv 与 CI Python | [CI 配置](../../.github/workflows/verification.yml) | 安装 uv；CI 固定工具版本与 Python 环境 |
| Ruff | [ruff.toml](../../ruff.toml) | 固定版本与格式；scripts / experiments 分别按对应 Python 最低版本检查 |
| 实验工具链 | [实验声明](../../experiments/android-vpn-smoke/experiment.json)与[复建入口](../../experiments/android-vpn-smoke/rebuild.py) | NDK、Rust、核心及补丁属于实验或后续正式 native 工程的要求 |

Android 模板构建使用 Android/Kotlin 工具链；完整 native 复建另有输入和环境要求。P00 的实验工具链与主应用配置分别定位，更新其中一方不能替代另一方的验证。

## 首次搭建与构建

1. 在 Android Studio 打开仓库根目录，按上述配置准备 Daemon JDK 和 Android SDK。
2. 通过 SDK Manager 安装 `compileSdk` 对应平台及构建工具。SDK 位置由本机 `local.properties` 或环境配置提供；本机目录不进入版本库。
3. 首次 Gradle 同步与构建允许获取 Wrapper、插件和依赖。自动工具链解析可能访问配置中的分发来源；受限网络环境需预先准备对应缓存。
4. 安装 Python 和 uv 后检查版本，再从根目录运行模板构建与 JVM 测试。

```bash
python3 --version
uv --version
./gradlew --version
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

模板此前通过的离线复验命令为：

```bash
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
```

离线模式要求 Wrapper、插件、依赖、SDK 与 JDK 已齐备。空缓存或缺少平台时先处理具体缺项。APK 输出在 `app/build/outputs/apk/debug/`，JVM 报告在 `app/build/reports/tests/testDebugUnitTest/`。当前 JVM 测试为模板示例；业务验收测试随功能建立。

## Python lint 与格式

uv 使用隔离工具环境运行 Ruff。首次获取工具需要 PyPI 网络访问，工具缓存齐备后可给 `uv tool run` 增加 `--offline`。

```bash
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check --no-cache scripts experiments
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --no-cache --check scripts experiments
```

格式检查只读；需要修改格式时去掉第二条命令的 `--check`，随后运行受影响的回归。源码脚本继续使用标准库。

规则例外限定到具体 CLI 文件或有中文理由的语句。`RUF100` 检查失效的 `noqa`。子进程需要保留输出再判定退出码时显式使用 `check=False`；需要立即抛出失败时使用 `check=True`。既有 Python 入口通过 `python3` 按路径调用。

## 回归与结构检查

按变更选择相关入口；全套 Python 回归由三个现有测试入口组成：

```bash
python3 scripts/catalog/test_audit_config_catalog.py
python3 scripts/verification/test-evidence.py
python3 experiments/android-vpn-smoke/test_workflow.py
```

| 检查 | 命令 | 可支持的结论 |
|---|---|---|
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

实验的离线测试、复建预览、源码导出及实际设备运行条件见 [实验 README](../../experiments/android-vpn-smoke/README.md)。设备命令需要对应授权与环境；本页的模板构建和 Python 检查不操作设备。

## CI 与输出

现有 [GitHub 工作流](../../.github/workflows/verification.yml) 配置了固定版本的 uv + Ruff、Python 回归、隐私、目录和 P00 判据检查。正式 Android 构建/JVM CI 属于 P01 剩余工作。远端是否成功以实际 Actions 运行记录为准，本地通过只登记本地结果。

本地构建、缓存、私密配置和实验输出按 [.gitignore](../../.gitignore) 放入忽略范围。普通检查的结果保留在执行报告或临时输出中；只有支撑技术结论或阶段门槛的筛选材料才按 [验证规范](verification.md) 发布。

## 文档同步

工程事实按来源维护：

- 阶段进度与验收归路线图及对应阶段文件；模板、工具或目录维护不自动完成阶段。
- 版本归构建配置、工具配置与锁文件；本页提供定位和使用方法。
- 目录与依赖决策归 ADR；源码和命令变动同步实际调用点、文档及 CI。
- 技能归各 `SKILL.md`，安装来源归锁文件，维护策略归 [技能策略](agent-skills-strategy.md)。
- 历史验证记录保留执行时的身份与范围；当前导航解释它们与现有工程的关系。

更新代码或能力时，核对 README、AGENTS、环境入口及受影响计划中的相关说明。常规维护不另建一份重复状态快照。
