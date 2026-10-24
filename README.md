# 串门（Chuanmen）

串门（Chuanmen）是基于 EasyTier 的 Android 组网客户端，正在开发中。

应用采用单 app 工程结构，通过真实 Service/JNI 接入 EasyTier 核心，提供加密持久化、多配置、首批 10 项配置编辑，以及总览、设备详情、流量趋势和双来源日志。当前 APK 的设备运行未验证；阶段状态和验收范围以 [路线图](docs/plan/ROADMAP.md) 为准。

## 项目名称

“串门”取自到邻居或朋友家走动的日常用语。这个名字表达了项目的目标：让分散在不同地方的手机、电脑和 NAS，通过同一个虚拟网络互相访问，就像去邻居家串个门。

两个字简洁、容易记，也符合客户端轻巧、易用的设计目标。“打开串门，连一下家里的 NAS”这样的日常表达，体现了名字的亲近感和一点趣味。

| 用途 | 写法 |
|---|---|
| 中文产品名 | 串门 |
| 英文拼音写法 | Chuanmen |
| 项目介绍 | 串门，一个基于 EasyTier 的 Android 组网客户端。 |
| 一句话介绍 | 让远处的设备，也能串个门。 |

文档首次介绍产品时使用“串门（Chuanmen）”，中文叙述可简称“串门”。工程名称、包名、类名和命令中的现有 `Chuanmen` / `chuanmen` 标识沿用英文拼音；EasyTier 用于说明上游核心及其配置语义、版本和来源。

## 开始开发

先按 [开发环境与验证命令](docs/development/setup.md) 配置 JDK、Android SDK、Python 和 uv。从仓库根运行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
python3 scripts/check_privacy.py
python3 scripts/catalog/audit_config_catalog.py
```

首次打包需准备固定 native 工具链与依赖，再执行 `./gradlew :app:assembleDebug`；详情见开发环境。正式应用使用真实 Service/JNI，JVM 测试不需要实际组网。

[CI](.github/workflows/ci.yml) 对所有 PR 执行相同检查，[PR policy](.github/workflows/pr-policy.yml) 保留标题及目标分支规则。参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 当前工程与目录

Android 工程仅 `:app`。Kotlin 在 `com.github.zzwtsy.chuanmen` 下按 `features/<功能>` 聚合，已有共享能力位于根 `lib`、`components`、`utils`，主题保留 `ui/theme`；Service 统一拥有真实会话。仅使用 main/test/androidTest；debug/release 共用正式配置入口，配置通过 AtomicFile 与 Android Keystore/AES-GCM 加密保存在本机，测试替身仅用于测试。现行决策见 [ADR 0005](docs/design/adr/0005-single-app.md)。

| 路径 | 职责 |
|---|---|
| `app/src/` | Android 应用、主题与标准测试 |
| `app/native/` | 固定 EasyTier 来源、Rust 桥、补丁、锁文件与原生回归 |
| `scripts/` | native、设备/peer、配置目录与隐私工具 |
| `docs/design/`、`docs/plan/` | 产品契约、ADR、配置目录与阶段状态 |
| `docs/development/`、`docs/research/` | 当前工程入口与按日期保存的研究 |
| `docs/verification/` | 原字节保留的历史验证材料 |
| `.agents/skills/` | 项目及已安装技能，来源见 skills-lock.json |

native 缓存和产物在 `app/build/native`，设备输出在 `app/build/device-tests`，均忽略入库。旧实验和证据发布框架已退役；冻结材料中的旧路径按记录时点解释。

## 按任务阅读

| 任务 | 入口 |
|---|---|
| 搭建环境、构建、选择检查 | [setup.md](docs/development/setup.md) |
| 分支、PR 与手动合并 | [Git 工作流](docs/development/git-workflow.md) |
| 应用版本、tag 与 Release | [版本治理](docs/development/versioning.md) |
| 开始下一阶段实施 | [路线图](docs/plan/ROADMAP.md) |
| 产品行为与 UI/UX | [总体设计](docs/design/overall-design.md) |
| Android、会话和 JNI 边界 | [技术架构](docs/design/technical-architecture.md)与[ADR](docs/design/adr/0005-single-app.md) |
| 配置来源、显隐与实施状态 | [配置目录](docs/design/config/README.md) |
| 设计实验、发布证据、关闭运行 | [验证规范](docs/development/verification.md) |
| 查看已验证范围及缺口 | [验证记录导航](docs/verification/README.md) |
| 代理工作与技能选择 | [AGENTS.md](AGENTS.md)、[工程约定](docs/development/ai-friendly-android.md)及[技能策略](docs/development/agent-skills-strategy.md) |

较复杂的实现任务使用 [任务模板](docs/development/task-brief.md)；常规修改按风险选择检查，技术不确定性或阶段门槛才进入实验与证据流程。

## 产品契约与证据范围

本地名称与核心身份分离，查看网络与运行网络分离，draft / saved / applied 分开建模；仅保存不改变运行配置，应用失败不伪报成功。首页使用固定 60 秒趋势，缺测值保持未知。完整约束见 [需求追踪](docs/design/requirements-traceability.md) 和总体设计。

[P00 报告](docs/verification/p00/report.md) 支持固定核心、限定设备与受控网络下的技术结论。正式接入范围见 [P02 报告](docs/verification/p02/report.md)；P03–P07 采用工程验收，设备矩阵及 release 冒烟等设备执行已取消；当前 APK 不继承旧产物的真机通过状态。P07 仅准备 GitHub APK 发布材料。

HTML 交互原型及专用导出器已退役，历史源码和专属资源许可证可从 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329` 取回。配置快照中的历史 `availability`、`apply` 与核心语义版本描述当时的研究；正式产品实施状态由 [能力台账](docs/design/config/android-capability-plan.json) 记录。外部资料的核对日期及限制见 [研究导航](docs/research/README.md)。

## 许可证

除另有许可声明的第三方代码和资源外，串门（Chuanmen）第一方代码采用 **GNU Affero General Public License v3.0 only**（SPDX：`AGPL-3.0-only`），全文见 [LICENSE](LICENSE)。许可版本固定为第 3 版。

EasyTier 和 Android 依赖保留各自的原始许可证和版权声明，使用与分发时须同时满足对应义务；固定 EasyTier 候选及依赖的来源与许可复核见 [P00 许可记录](docs/verification/p00/report.md)。正式发行的对应源码、通知及原生库许可安排仍按发布阶段验收。
