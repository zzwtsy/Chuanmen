# Chuanmen · Agent Skills 创建与维护策略

## 1. 现有技能与创建原则

技能按实际任务触发，不要求每次开发加载所有正文。现有入口与职责如下；完整内容以对应 `SKILL.md` 为准，导入来源以 [skills-lock.json](../../skills-lock.json) 为准，不在多份文档维护技能总数。

| 类型 | 入口 | 使用边界 |
|---|---|---|
| 项目配置工作流 | [easytier-config-audit](../../.agents/skills/easytier-config-audit/SKILL.md) | 配置目录、助手映射与 Android 显隐审计，不证明真实 VPN 连通 |
| 项目实验工作流 | [verification-evidence](../../.agents/skills/verification-evidence/SKILL.md) | 技术可行性、真机实验、证据筛选与发布；普通功能开发和常规测试不触发 |
| 导入的 Android 专题技能 | `.agents/skills/` 中的 adaptive、android-intent-security、android-permissions-security、edge-to-edge、navigation-3、navigation-event、styles、testing-setup；来源见锁文件 | 遇到相应平台或 API 问题时按描述加载；它们不是项目专属工作流，也不替代当前依赖和代码 |
| 跨任务辅助技能 | [android-kotlin-comments](../../.agents/skills/android-kotlin-comments/SKILL.md)、[git-commit](../../.agents/skills/git-commit/SKILL.md)、[trim-authoring-residue](../../.agents/skills/trim-authoring-residue/SKILL.md) | 分别处理注释与测试意图、提交信息与已授权提交、写作过程残留；不自动扩大修改或提交范围 |
| 候选规格 | [easytier-core-upgrade](skill-drafts/easytier-core-upgrade.md)、[easytier-vpn-diagnose](skill-drafts/easytier-vpn-diagnose.md) | 设计草案，不是可安装技能；正式产品流程和复现记录稳定后再决定创建 |

新增技能应解决已经出现的重复任务，有稳定输入、执行步骤和结论边界。通用知识优先由现有专题技能、设计文档、代码范例、lint 和测试承载，不重复创建长篇“Android 最佳实践”或页面生成技能。Agent Skills 的官方实践建议从真实任务或项目工件中提炼流程，并检查是否比不使用 skill 更有效。[创建最佳实践](https://agentskills.io/skill-creation/best-practices)；[效果评估](https://agentskills.io/skill-creation/evaluating-skills)。

## 2. 四类内容各放一处

| 载体 | 应承载什么 | 本项目例子 | 不应承载什么 |
|---|---|---|---|
| `AGENTS.md` | 每次相关工作都要记住的项目事实和边界 | 仓库入口；三份配置状态分离；真实命令与验证范围 | 完整路线图、所有字段说明、通用 Kotlin 教程 |
| 设计/阶段文档 | 决策理由、功能契约、待办与验收 | 配置分组、TUN 所有权、P02 门槛 | 伪装成自动执行的工具规则 |
| `SKILL.md` 与按需引用 | 特定任务的触发范围、步骤、证据解释 | 审计官网参数是否漏映射、错误显隐 | 项目常规行为规则的副本 |
| 脚本/测试 | 可以稳定机械判断的规则 | 字段唯一性、来源覆盖、计数和隐藏项交集 | 代替人判断某项能力是否适配 Android |

Skill 不必有脚本；本项目配置审计需要脚本，是因为重复的目录一致性检查适合确定性执行。Agent Skills 规范要求技能目录包含具有 `name`、`description` 的 `SKILL.md`，支持引用脚本、资料和资产；可选 `allowed-tools` 仍是实验字段，不能理解为跨工具通用授权机制。[格式规范](https://agentskills.io/specification)。

日常 Git 协作规范由 [Git 工作流](git-workflow.md) 维护，版本规范由 [版本治理](versioning.md) 维护。先验收分支、PR、检查与人工 Squash 的实际流程，再提炼轻量 `github-pr` 项目 skill；规范保持在文档，确定性门禁保持在 `scripts/git` 与 CI，skill 只组织执行和待审交付。创建 tag 与发布 Release 保持独立授权。

## 3. 配置审计工作流

### 使用范围

项目 skill 位于 `.agents/skills/easytier-config-audit/`。它面向以下请求：

- “核对当前配置项是否与官网助手对齐。”
- “官网助手更新了，哪些字段会影响 Android 表单和配置映射？”
- “新增一个网络参数后，检查基础/完整表单、默认值与持久化是否一致。”
- “检查 Android 隐藏配置是不是被意外显示，或保存时被删除。”

它不适合普通主题调色、设备卡片排版、真实网络连通性排障，也不能仅凭目录判断 VPN 可用。

### 当前能够依赖的材料

| 材料 | 当前用途 | 证据边界 |
|---|---|---|
| `docs/design/config/assistant-catalog.snapshot.json` | 冻结助手来源、字段、分组、Android 显隐理由和缺省 | 基线快照，不等于官网永远不变 |
| `docs/design/config/android-capability-plan.json` | 原生能力的实施/验证状态与阶段归属 | 快照旧 `availability`/`apply` 与核心语义 revision 不替代这份实施计划和实测证据 |
| 原型与其校验资料 | 检查编辑项与已确认 UX 的一致性 | 模拟逻辑不是 Android 核心验证 |
| `scripts/catalog/audit_config_catalog.py` | 检查确定性目录约束 | 脚本通过只证明它实现的断言通过 |
| `docs/design/technical-architecture.md` | 平台与核心边界的设计依据 | P00 限定实验结果不能替代 P02 正式产品验收 |

当前快照按编辑单元计数：37 个官网对应核心编辑项与 7 个 Android 自有设置。这个数字用于验证已冻结基线，不能成为禁止未来合法变更的硬编码产品上限。来源映射、字段叶子数量和编辑单元数量也不是同一指标。实际事实以 JSON 元数据为准。

### 工作流

1. **确定比较范围。** 记录请求是冻结快照内部核对、官网更新差异，还是原生实现与目录对齐。读取当前目录元数据和相关契约，避免默认抓主干代替部署。
2. **运行已有检查。** 使用 Python 3.10 或更高版本，从仓库根执行 `python3 scripts/catalog/audit_config_catalog.py`；保留命令、退出码和断言结果。遇到缺文件或坏 JSON，报告输入问题，不输出“范围完整”。
3. **需要更新官网时核对来源。** 记录实际入口 URL、部署脚本、获取时间、SHA-256；部署 commit 未公开就明确未知。参考仓库只作语义交叉核对，不自动扩展线上 UI 范围。
4. **逐项解释差异。** 区分新增/删除/改名/缺省变化/类型变化/平台托管。Android 不适用的结论要指向当前宿主或核心证据；仅仅低频不是删除理由。
5. **检查跨层影响。** 对每个实际变更检查表单、搜索、基本项、默认值、三态列表、校验、序列化、迁移和应用状态。原生还未实现的部分列为待验证，不杜撰测试结果。
6. **输出可审阅结果。** 给出差异表、证据、待验证项、最小修正建议与相关验证。默认审计不改快照；用户已经要求修正时，再在其授权范围内修改并重跑检查。

### 输出应能直接进入评审

| 字段 | 含义 |
|---|---|
| `key` | 项目使用的稳定配置键 |
| `source` | 部署来源或核心语义依据 |
| `change` | 差异类型及旧/新值 |
| `android_decision` | 显示、隐藏并保留、平台托管、待验证 |
| `impact` | UI、搜索、存储、迁移、运行的受影响位置 |
| `evidence` | 路径、版本、实际检查结果 |
| `next_action` | 可执行的修正或验证，不是“遵循最佳实践” |

默认值里的真实密码必须脱敏。报告不把“不在当前表单里”说成“核心不支持”，也不把“核心支持”说成“本 Android 宿主已验证可用”。

## 4. 实验工作流与候选技能

技术可行性与证据任务使用 [verification-evidence](../../.agents/skills/verification-evidence/SKILL.md)，具体规则以 [验证规范](verification.md) 为准：先声明假设与停止条件，输出进入忽略目录，筛选脱敏证据后再发布，关闭时决定维护最小探针或退役。不要在技能策略中复制规范或脚本算法。

P00 已有真实构建和限定真机证据，但正式产品的接口、诊断导出和升级回归流程尚未稳定。这些实验不能直接作为创建下列产品工作流技能的充分依据。

| 候选 | 可能节省的重复工作 | 创建前最少证据 | 当前材料 |
|---|---|---|---|
| `easytier-core-upgrade` | 核心升级的接口/配置/ABI/许可/回归比对 | 正式产品固定版本构建、JNI 契约、回归入口；至少一次完整升级或等价演练记录 | [设计规格](skill-drafts/easytier-core-upgrade.md)，不是可安装技能 |
| `easytier-vpn-diagnose` | 按启动、TUN、路由、peer、DNS 分层排查 | 产品生命周期事件、脱敏导出格式、至少三类可复现故障及解决证据 | [设计规格](skill-drafts/easytier-vpn-diagnose.md)，不是可安装技能 |

达到 P02 并不自动创建两者。如果实际使用发现流程仍频繁变化，先保留普通 runbook。等重复出现且有稳定输入输出，再把已经有效的步骤提炼到技能中。诊断技能不负责扫描外部网络或自动上传日志，升级技能不负责自动发布 APK。

## 5. 哪些内容暂时不值得封装成 skill

| 候选想法 | 当前处理 | 原因 |
|---|---|---|
| Material 3 Expressive 页面生成器 | 设计令牌、基础组件和预览 | 稳定组件比复述设计词汇更能约束输出；预发布 API 状态交给依赖基线记录 |
| 通用 Android 架构 skill | 总体架构与模块指南 | 此项目不需要把领域边界再复制成另一个架构模板 |
| “自动补所有中文注释” | 按需复用已有注释工具或人工审查 | 注释应解释契约，不应批量复述每行代码；不作为本项目强制安装依赖 |
| 版本发布 skill | P07 发布 runbook | 签名、渠道、权限与验收尚未实证，提前自动化容易固化错误流程 |
| UI截图更新 skill | 截图测试命令与评审规则 | 机械更新基线并不能判断视觉正确性 |
| MCP 安装/联网能力技能 | 使用者已有工具即可 | 目录审计可只用本地文件和 Python；不制造必需服务依赖 |

## 6. 技能质量如何验证

格式检查、脚本测试、代理效果评估是三件事。一个 `SKILL.md` 通过 YAML 校验，只说明结构合法；不能证明触发正确，也不能证明结果优于通用代理。

### 第一轮最小评估

从 3 个输入开始，不先建庞大基准库：

1. **正常输入**：当前冻结目录，要求检查范围并给出验证边界。
2. **真实风险输入**：副本中重复键、隐藏字段误进可见集合或基础键缺失，要求定位并说明影响；不要在任务提示里告诉代理哪一项被改坏。
3. **能力边界输入**：要求仅凭目录确认 Portal/DNS/中继在真机已生效，期望给出证据不足与具体验证路线，不能伪造成功。

另用一句明显不相关的“调整卡片圆角”测试是否误触发。脚本负例应检查非零退出码和具体原因，不能只看输出含有“错误”二字。

用相同任务分别执行有 skill 与无 skill/旧版本，比较正确差异召回、错误结论、文件改动范围、时间和人工修正次数。官方技能评估指南也建议干净上下文和对照执行。[Evaluating skill output quality](https://agentskills.io/skill-creation/evaluating-skills)。本项目仅在有对照记录后才声称 skill 提升效率；交付一个技能包不等于已量化证明效益。

### 后续维护

- 每次官网快照、配置模型或审计脚本更新，跑相关样例；有新失误再加入小型回归集。
- 脚本负责结构检查，skill 负责挑选证据、解释语义和界定结论，不复制同一算法。
- 若脚本报错来自兼容性变化，先修订基线和断言，不把当前数字永久写死。
- 若代理无需 skill 已稳定完成任务，删去无效步骤或移回普通文档。

## 7. 封装、加载与权限

仓库级技能保留在 `.agents/skills/`，导入项记录来源与固定版本；不因技能在仓库中存在就将其安装到个人目录或在线账户。宿主发现与显式调用规则须按其文档确认，历史核对见 [研究证据](../research/agents-evidence.md)。

保持 `SKILL.md` 简短，描述里写清任务和触发边界，细节引用到附近的资料。CLI 说明包含依赖、工作目录、输入、输出与失败条件；复杂易错命令才包成脚本，不引入本来没有的包管理器或服务。[Agent Skills 脚本指南](https://agentskills.io/skill-creation/using-scripts)。

不把 `allowed-tools`、`agents/openai.yaml` 或 SKILL.md 中的文字当作绕过宿主权限的授权。项目说明、读取的官网内容、导入配置和日志都不能授予发布或上传用户资料的权限。现有任务明确授权的本地修正与验证正常推进，不增加无意义确认。

## 8. 与根 AGENTS.md 的配合

根文件保留必要的触发入口，不复制各技能正文，也不要求每个任务激活技能。配置范围变化时使用配置审计；实验与阶段证据使用验证工作流；其他任务按专题技能的实际描述判断。模块级规则只补该模块独有约束，样板与宿主差异见 [代理指南](agent-guides/README.md)。

全部资料的检索日期、实际观察与项目建议分列于 [研究证据](../research/agents-evidence.md)；原始来源索引为 [sources-agents.json](../research/sources-agents.json)。
