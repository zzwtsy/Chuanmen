# 串门（Chuanmen）— 代理工作约定

## 当前状态与任务入口

串门（Chuanmen）是基于 EasyTier 的 Android 客户端开发仓库，已有 `:app -> :contracts`、demo 配置切片、四页导航、原型、配置目录和工程校验工具。正式 VPN/JNI 接入仍待实施；阶段状态以 [路线图](docs/plan/ROADMAP.md) 为准，实验结论以对应报告的验证范围为准。

中文产品名使用“串门”，英文拼音写法为 Chuanmen；文档首次介绍产品时使用“串门（Chuanmen）”，现有工程与代码标识沿用 `Chuanmen` / `chuanmen`。名称含义与介绍文案见 [项目名称](README.md#项目名称)。EasyTier 保留用于上游核心、版本、配置语义和来源引用。

先确认本次任务属于哪个阶段、当前文件和验证条件；只读与任务相关的文档：

| 任务 | 入口 |
|---|---|
| 产品行为、UI/UX | [总体设计](docs/design/overall-design.md) |
| Android、JNI、核心边界 | [技术架构](docs/design/technical-architecture.md) |
| 开始/继续实施 | [路线图](docs/plan/ROADMAP.md)及其中对应的阶段文件 |
| 环境、构建与现有检查 | [开发环境](docs/development/setup.md) |
| 测试策略、代理协作 | [AI 友好工程](docs/development/ai-friendly-android.md) |
| 分支、提交、PR 与合并 | [Git 工作流](docs/development/git-workflow.md)；提交复用 [git-commit](.agents/skills/git-commit/SKILL.md) |
| 应用版本、tag 与 Release | [版本治理](docs/development/versioning.md) |
| 配置项核对 | `docs/design/config/assistant-catalog.snapshot.json`、`docs/design/config/android-capability-plan.json`；[配置审计 skill](.agents/skills/easytier-config-audit/SKILL.md) |

查找先用 `rg` / `rg --files`。不要把阶段文档中的拟建模块、命令或 API 当成已经存在；实现后同步对应状态与验证记录。

目录归属按 [目录边界 ADR](docs/design/adr/0003-directory-boundaries.md)：业务代码与测试聚合到所属切片，通用工具归 scripts，固定问题的探针归 experiments，运行输出归忽略目录。新建目录须有实际内容；跨切片通过公开契约协作，共享代码须有实际复用方。

## 现有检查入口

从仓库根目录运行，依赖 Python 3.10 或更高版本的标准库：

```bash
python3 scripts/catalog/audit_config_catalog.py
python3 scripts/validate_handoff.py
python3 scripts/verification/check-privacy.py
python3 scripts/verification/check-evidence.py --stage p00 --require-stage
python3 scripts/validate_handoff.py --strict
```

这些校验检查工作区结构、隐私、配置目录和既有门槛记录，不重复验证真实 VPN 行为。交付前需要检查暂存内容时，为支持该参数的检查添加 `--tree index`。Android 入口为 `scripts/android/doctor.sh`、`check-fast.sh`、`build-demo.sh` 和 `verify-device.sh`；参数、报告及当前设备验证范围见 [开发环境](docs/development/setup.md)。原型维护见 [原型说明](prototype/README.md)，实验流程见 [验证规范](docs/development/verification.md)。正式 native 命令须建立并实测后再登记。

Python 源码修改须运行 [开发环境](docs/development/setup.md) 中固定版本的 uv + Ruff lint 与格式检查，再运行受影响的回归。规则在 [ruff.toml](ruff.toml)；入口通过 `python3` 调用，局部规则例外须说明契约或测试意图，不全局忽略告警。

## 实验与证据治理

技术可行性调查、真机实验、证据整理和阶段冻结先读 [验证规范](docs/development/verification.md)，使用 [verification-evidence skill](.agents/skills/verification-evidence/SKILL.md)。普通开发使用正常测试，不自动生成证据包。

- 运行前声明判据、预算、停止条件和关闭安排；源码/fixture 留在 experiments，临时输出放 build/experiments。
- 以结论筛选证据，保留必要样本和重要负向结果；先脱敏和检查，再冻结。个人主目录、设备序列号及稳定摘要不入库。
- 静态、构建、模拟器、真机、独立复建分别声明；新产物不继承旧真机验收，实验补丁不获得生产批准。
- 发布默认预览，已发布记录不覆盖；运输清单只覆盖所选阶段，不冻结当前工具或整个仓库。
- 关闭时决定转正式测试、维护最小探针或退役，不永久保存一次性补救脚本和旧规范副本。

## 不得破坏的行为契约

- `profileId`、本地 `localDisplayName` 与核心实例/网络身份分离；改本地名称不触发重连，也不被恢复核心配置覆盖。
- 草稿、已保存、实际已应用分别建模；“仅保存”不改变运行配置，应用失败不伪报成功。
- 查看网络与实际运行网络分离；“基础配置 / 完整配置”编辑同一份草稿，切换不丢输入或错误。
- 配置范围以冻结官网助手目录为准，原生实施能力以能力计划和实测证据为准；快照旧 `availability`/`apply` 不作原生承诺。经证实不适用/平台托管项不进表单和搜索，暂未实现要另列缺口，隐藏不等于删除已保存数据。新增能力先补映射和证据。
- 首页流量是固定 60 秒趋势与上下行速率，不增加拖动取值、详情或时间范围切换；流量不替代连接成功判据。缺测数据不写成 0。
- 流量范围在当前网络设置里；在线设备整合在虚拟网络卡片；设备页不增加“相关日志”；复制 IP 使用一致动作与短暂反馈。
- 真实会话由服务统一拥有；demo 与 real 明确选择，real 失败不回退为模拟成功。

## 修改、验证与报告

按变更风险运行最小充分验证：文档/目录校验、相关状态测试、必要 UI 或设备验证。未能运行的项目写明原因，不把跳过、mock 通过或 HTML 测试写成真机通过。不要用更新截图基线、删除断言或关闭检查掩盖未解释的失败。

涉及核心升级、TUN/JNI 所有权、配置迁移时，同步契约、来源版本和针对性回归证据。只改文案或可逆低影响内容不新增机械重复的测试。

新增或调整目录、命令、能力时，同步受影响的入口与说明。阶段状态集中维护在路线图，版本以构建配置和锁文件为准，目录职责以 ADR 为准；维护文档不覆盖已冻结记录，也不自动生成新的实验包。

代码注释用中文解释意图、生命周期和约束；测试说明清楚条件、操作和可观察结果。公共类型、命令及协议字段保留其原始名称。

不要覆盖不属于本任务的改动。多代理并行先划分文件/接口所有权，整合后验证共同流程。网络密码、私钥、凭据不进入日志、测试夹具或提交记录；使用合成数据。

日常开发从最新 `main` 建短期任务分支，通过 `gh` 创建或更新 PR。完成实际检查后转为 Ready，报告链接、head SHA 和检查结果，默认停在待审；维护者手动 Squash。只有明确的后续合并请求才核对已审阅 SHA 并执行合并，tag 与 Release 另行授权。完整流程以 Git 工作流为准。

本文件不授予发布、签名密钥访问、上传诊断数据或安装个人技能的额外权限。按当前用户已授权范围工作；普通本地实现与验证无需再增加确认步骤。

## 指令范围

模块确有不同约束后再添加局部 `AGENTS.md`，只写差异；样板在 [agent-guides](docs/development/agent-guides/README.md)。加载规则按所用代理确认，不能假定所有宿主都会自动读取任意后代目录。依据：[AGENTS.md](https://agents.md/)、[Codex 加载规则](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。
