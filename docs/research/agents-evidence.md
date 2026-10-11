# AGENTS、Skills 与 AI 友好工程：研究证据

外部资料检索日期：2026-10-11（Asia/Shanghai）。本文保留该次研究的来源与推导，后续仓库演进不代表重新联网检索。机器可读来源索引见 [sources-agents.json](sources-agents.json)；现行技能策略见 [创建与维护策略](../development/agent-skills-strategy.md)，目录决策见 [目录边界 ADR](../design/adr/0003-directory-boundaries.md)，工程入口见 [开发环境](../development/setup.md)。

## 1. 核心结论及证据等级

| 结论 | 类型 | 支撑与边界 |
|---|---|---|
| 项目入口采用 `AGENTS.md` 复数名称 | 规范事实与项目选择 | 开放格式使用该名称；本项目选择它作为唯一根代理说明 |
| 不把局部指令加载规则写成跨工具统一标准 | 已核实实现差异 | 开放说明与 Codex 启动路径发现规则必须区分 |
| 研究时建议先创建配置审计技能 | 历史项目建议 | 基于当时稳定的配置输入；现有技能与触发范围以维护策略为准 |
| 产品升级和诊断技能延后 | 项目建议 | P00 已提供限定实验构建与真机证据，正式产品工作流仍不足以提炼稳定流程 |
| AI 友好优先解决可发现、可构建、可复现、可验证 | 项目判断 | 将已核实的工程机制组合为本项目验收目标，不是引用某个统一认证标准 |
| demo/fake 与真实引擎分开 | 设计决策 | 便于确定性开发；真实模式不能默默回退为假成功 |
| 研究时建议五个 Android 模块加 native 目录 | 历史架构建议 | 已由目录边界 ADR 更新为从已有 :app 按实际消费者建立模块，不预建空目录 |

## 2. AGENTS.md 资料核对

### 已确认事实

- [AGENTS.md 官方站点](https://agents.md/)把它描述为面向编码代理的项目说明文件，采用普通 Markdown，无统一必填字段。网站有嵌套文件的通用说明。
- [OpenAI 当前 AGENTS.md 文档](https://learn.chatgpt.com/docs/agent-configuration/agents-md)具体描述 Codex 在启动时构建指导链，从项目根到当前工作目录逐级寻找，按同目录 override/常规/fallback 优先级选取。它并没有给所有代理定义统一发现算法。
- [Now in Android 的实际 AGENTS.md](https://github.com/android/nowinandroid/blob/main/AGENTS.md)包含架构、模块、构建测试和 CI 的入口信息。本次通过 GitHub 文件工具读取，blob SHA 为 `edcfd396ec116bfe8e516148d1a30e2d00076fb5`。

### 对本项目的推导

根文件应该短、准确、可执行，重要程度高于篇幅；局部文件仅补模块独有约束。命令必须按仓库事实登记：已有模板 Gradle 与 Python 入口见开发环境，拟建 demo、设备脚本和产品 JNI 接口仍不能当作现存能力。对新工具做一次加载验证比维护很多看似兼容的指令副本更便宜。

本项目提供两个局部样板，文件名故意保留 `.example.md`，以免尚未建立模块时被误认为活动指令。样板不能改变用户当前授权范围。

## 3. Agent Skills 资料核对

### 已确认事实

| 来源 | 本次使用的事实 |
|---|---|
| [格式规范](https://agentskills.io/specification) | SKILL.md、name/description 与可选脚本/资料结构；渐进加载；实验性工具字段 |
| [OpenAI Build skills](https://learn.chatgpt.com/docs/build-skills) | 描述参与匹配，正文按需加载；当前 Codex 的项目技能发现位置 |
| [创建最佳实践](https://agentskills.io/skill-creation/best-practices) | 从实际任务、项目工件和执行反馈提炼有用流程 |
| [效果评估](https://agentskills.io/skill-creation/evaluating-skills) | 用真实任务与输入评估，并做有/无技能或前后版本对照 |
| [脚本指南](https://agentskills.io/skill-creation/using-scripts) | 脚本要清楚表达依赖、路径与接口，复杂重复命令才值得打包 |

### 对本项目的推导

“配置目录审计”同时具备项目专属知识、反复出现的易错边界和现有真实输入，适合立即封装。配置助手线上部署与参考仓库的差异、Android 宿主管理的选项、默认值与隐藏数据保留规则，都不是泛用“遵循最佳实践”能替代的内容。

核心升级和 VPN 排障涉及实际二进制、JNI、设备与网络。产品流程未稳定时写技能会固化假设，因此两个候选保留设计规格，在 P02 和后续产品使用记录到位后再决定创建；P00 探针的实证不能代替这些条件。

技能包格式检查和审计脚本正/负例，分别验证格式和脚本行为；对代理整体效率的提升仍须通过独立有/无技能评估证明。

## 4. Android 工程资料核对

| 一手资料 | 已确认事实 | 本项目建议 |
|---|---|---|
| [模块化指南](https://developer.android.com/topic/modularization) | 隔离模块有封装/测试收益，也有粒度过细的成本 | 先隔离平台与核心边界，UI 不按每页拆模块 |
| [测试基础](https://developer.android.com/training/testing/fundamentals) | 本地/设备是运行位置；业务解耦与依赖替换有利于测试 | 领域、UI、设备和网络验证分别报告 |
| [Compose Preview](https://developer.android.com/develop/ui/compose/tooling/previews) | 预览不是完整 Android 环境 | 屏幕接受显式状态，不在预览里启动 VPN/native |
| [Compose Semantics](https://developer.android.com/develop/ui/compose/testing/semantics) | 无障碍与 UI 测试共享语义信息 | 优先真实语义，必要时补稳定测试标签 |
| [协程测试](https://developer.android.com/kotlin/coroutines/test) | 测试调度器支持可控时序 | 重连、采样和提示使用可控时钟/调度器 |
| [构建管理设备](https://developer.android.com/studio/test/managed-devices) | 配置与管理测试设备生命周期 | 逐步建立设备矩阵，真实蜂窝/OEM 问题另测 |
| [JNI tips](https://developer.android.com/ndk/guides/jni-tips) | 线程、引用和资源有跨边界约束 | 写清所有权、取消、回调和错误契约 |
| [Android VPN](https://developer.android.com/develop/connectivity/vpn) | 授权、TUN、隧道路径由 VpnService 模型连接 | 分层验证数据路径，不用单个 UI 状态当成功证据 |

上表的建议是适用于本项目的选择，不意味着每种模块划分、测试库、构建变体都是 Android 官方强制要求。

## 5. Now in Android：借鉴机制，避免照抄规模

[官方样例 README](https://github.com/android/nowinandroid)展示本地 demo 数据源和测试替身；[模块化说明](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)提供模块导航并说明设计与其自身目标有关；[构建约定说明](https://github.com/android/nowinandroid/blob/main/build-logic/README.md)给出公共配置复用方式。

本次直接读取的两个源码 blob 为：

- 模块化说明：`b50242776e797bdbf9069c2003be29c6ed18dbba`。
- 构建约定说明：`0458b4fb18d38940b72ae193586d7015e4b327d4`。

本项目借鉴可独立启动的演示数据、领域接口替身和可定位的构建说明。其 `demoDebug` task、页面模块拆分或截图工具不是本项目已经存在的能力。复制示例命令而不建立相应 Gradle 模型，会制造代理无法执行的说明。

## 6. 构建可复现的范围

[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)、[版本目录](https://docs.gradle.org/current/userguide/version_catalogs.html)、[依赖校验](https://docs.gradle.org/current/userguide/dependency_verification.html)分别处理工具版本入口、依赖声明和制品检查。项目应把这些机制与 Rust/NDK/SDK 版本及核心 commit 一起记录。

“版本固定”并不自动等于“最终签名 APK 完全字节一致”。本计划先验收干净环境可以按同样步骤构建、测试和标识产物；二进制复现程度由后续实际比对确定。demo 在运行时不依赖真实组网，也不能推导出初次构建在空缓存完全离线可行。

## 7. 研究时工件与后续演进

2026-10-11 的研究直接检查了配置 JSON，其结构含 `meta`、`groups`、`fields` 和 `runtimeDefaults`。元数据记录实际助手部署的来源、哈希、可见项、八个隐藏项及原因；引用的参考仓库并未冒充线上已部署版本。冻结快照保存在 `docs/design/config/assistant-catalog.snapshot.json`。

后续 P00 已形成 [限定范围的实验报告](../verification/p00/report.md)，仓库也已有 Android 模板与工程检查，入口见开发环境。正式产品的领域状态、VPN/JNI、统计与页面仍须按阶段实施，进度以 [路线图](../plan/ROADMAP.md) 为准。

快照中旧 `availability`、`apply` 和核心语义 revision 属于原型研究，不能作为产品能力证明；产品实施状态按 `docs/design/config/android-capability-plan.json` 与阶段实测证据维护。目录审计、模板构建、实验验收和产品验收各自声明适用范围。

## 8. 研究边界

- 没有对所有编码代理逐一实测加载规则；仅对已打开的规范和 Codex 文档作相应范围内结论。
- 没有将官方样例当前任务名、版本号或模块数量直接认定为本项目实现。
- 没有量化证明本项目技能已提高代理成功率；评估方案与脚本验证不能替代未来对照测试。
- 所有时间点敏感资料均按本次检索日期理解；正式实施时对所选工具链及平台行为再次固定版本并验证。
