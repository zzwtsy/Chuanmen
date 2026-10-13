# 串门（Chuanmen）

串门（Chuanmen）是基于 EasyTier 的 Android 组网客户端，正在开发中。

仓库已有可构建的 demo、纯领域契约、四页导航、交互原型、配置目录、工程工具和维护中的实验探针。正式 VPN/JNI、持久化配置闭环与真实运行数据尚待实施。P00 技术可行性已在限定设备和网络范围通过；阶段状态及剩余工作以 [路线图](docs/plan/ROADMAP.md) 为准。

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
./scripts/android/doctor.sh
./scripts/android/check-fast.sh
./scripts/android/build-demo.sh
python3 scripts/validate_handoff.py --strict
```

当前提供独立包名的 `demoDebug`，本地领域/导航测试与 APK 构建已通过；首次构建需要获取依赖，缓存齐备后可使用 `--offline`。live 在缺少正式服务/桥接时明确失败；P01 剩余验收见 [阶段记录](docs/plan/p01-foundation.md)。

Python lint、格式检查、回归测试及设备入口见 [开发命令](docs/development/setup.md)。已有 [Python CI](.github/workflows/verification.yml) 和 [Android CI](.github/workflows/android.yml) 配置；Android 远端运行尚未验证，结果以实际 Actions 记录为准。

## 当前工程与目录

Android 工程为 `:app -> :contracts`：`MainActivity` 处理 Activity 与窗口设置，`application` 装配四页导航与 ViewModel，配置切片维护草稿，`shared/designsystem` 保存公共组件和主题。模拟实现、fixture 与 Preview 只在 demo source set。边界见 [目录 ADR](docs/design/adr/0003-directory-boundaries.md)和[装配 ADR](docs/design/adr/0004-demo-assembly.md)。

| 路径 | 职责 |
|---|---|
| `app/` | Android 应用、组合入口、主题与标准测试 source set |
| `contracts/` | 纯 Kotlin 配置、会话、仓库及带操作归属的引擎契约 |
| `scripts/` | catalog、prototype、verification 工具及交付结构校验入口 |
| `experiments/` | 按问题和子场景维护的实验源码、声明、合成夹具及离线回归 |
| `prototype/` | 模拟交互原型、可编辑源与固定的离线资源 |
| `docs/design/` | 产品契约、目标架构、ADR 与配置目录 |
| `docs/plan/` | 阶段状态、剩余工作和验收门槛 |
| `docs/development/` | 环境、工程规范、任务模板及技能维护策略 |
| `docs/research/` | 按核对日期保存的外部来源与研究结论 |
| `docs/verification/` | 支撑明确结论的脱敏记录、必要输入与历史基线 |
| `.agents/skills/` | 项目专用流程和通用技能；外部安装来源见 `skills-lock.json` |

构建、缓存和实验运行输出放在被忽略的目录。根 `handoff.json` 与 `SHA256SUMS` 只描述 P00 阶段材料的运输完整性，范围见文件中的声明。

## 按任务阅读

| 任务 | 入口 |
|---|---|
| 搭建环境、构建、选择检查 | [setup.md](docs/development/setup.md) |
| 开始下一阶段实施 | [P01 工程基础](docs/plan/p01-foundation.md)及[路线图](docs/plan/ROADMAP.md) |
| 产品行为与 UI/UX | [总体设计](docs/design/overall-design.md) |
| Android、会话和 JNI 边界 | [技术架构](docs/design/technical-architecture.md)与[ADR](docs/design/adr/0003-directory-boundaries.md) |
| 配置来源、显隐与实施状态 | [配置目录](docs/design/config/README.md) |
| 查看或修改交互原型 | [原型说明](prototype/README.md)，直接打开 [index.html](prototype/index.html) |
| 设计实验、发布证据、关闭运行 | [验证规范](docs/development/verification.md)与[实验探针](experiments/android-vpn-smoke/README.md) |
| 查看已验证范围及缺口 | [验证记录导航](docs/verification/README.md) |
| 代理工作与技能选择 | [AGENTS.md](AGENTS.md)、[工程约定](docs/development/ai-friendly-android.md)及[技能策略](docs/development/agent-skills-strategy.md) |

较复杂的实现任务使用 [任务模板](docs/development/task-brief.md)；常规修改按风险选择检查，技术不确定性或阶段门槛才进入实验与证据流程。

## 产品契约与证据范围

本地名称与核心身份分离，查看网络与运行网络分离，draft / saved / applied 分开建模；仅保存不改变运行配置，应用失败不伪报成功。首页使用固定 60 秒趋势，缺测值保持未知。完整约束见 [需求追踪](docs/design/requirements-traceability.md) 和总体设计。

[P00 报告](docs/verification/p00/report.md) 支持固定核心、限定设备与受控网络下的技术结论。生产 bridge、其他设备/API/ABI、范围外网络能力和发行安排仍需各自验收。当前维护探针的静态检查与源码更新不继承历史 APK 的真机通过状态。

原型使用模拟数据。配置快照中的历史 `availability`、`apply` 与核心语义版本描述当时的研究；正式产品实施状态由 [能力台账](docs/design/config/android-capability-plan.json) 记录。外部资料的核对日期及限制见 [研究导航](docs/research/README.md)。
