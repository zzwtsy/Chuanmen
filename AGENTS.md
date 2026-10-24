# 串门（Chuanmen）— 代理工作约定

## 当前状态与任务入口

串门（Chuanmen）是基于 EasyTier 的 Android 客户端，当前 P02 已完成限定范围实现，P03–P07 已规划、尚未实施。工程仅 `:app`，Kotlin 使用 `features/<功能>` 和根共享目录，主题保留 `ui/theme`，Rust 位于 `app/native`；正式应用始终通过 Service/JNI 使用真实核心。配置仍为受控内存输入。阶段状态以 [路线图](docs/plan/ROADMAP.md) 为准，设备结论绑定对应 APK 和报告范围。

中文产品名使用“串门”，英文拼音写法为 Chuanmen；文档首次介绍产品时使用“串门（Chuanmen）”，现有工程与代码标识沿用 `Chuanmen` / `chuanmen`。名称含义与介绍文案见 [项目名称](README.md#项目名称)。EasyTier 保留用于上游核心、版本、配置语义和来源引用。

先确认本次任务属于哪个阶段、当前文件和验证条件；只读与任务相关的文档：

| 任务 | 入口 |
|---|---|
| 产品行为、UI/UX | [总体设计](docs/design/overall-design.md) |
| Android、JNI、核心边界 | [技术架构](docs/design/technical-architecture.md) |
| 开始/继续实施 | [路线图](docs/plan/ROADMAP.md)及其中对应的阶段文件 |
| 环境、构建与现有检查 | [开发环境](docs/development/setup.md) |
| 测试策略、代理协作 | [AI 友好工程](docs/development/ai-friendly-android.md) |
| 分支、提交、PR 与合并 | [Git 工作流](docs/development/git-workflow.md)；PR 交付使用 [github-pr](.agents/skills/github-pr/SKILL.md)，本地提交复用 [git-commit](.agents/skills/git-commit/SKILL.md) |
| 应用版本、tag 与 Release | [版本治理](docs/development/versioning.md) |
| 配置项核对 | `docs/design/config/assistant-catalog.snapshot.json`、`docs/design/config/android-capability-plan.json`；[配置审计 skill](.agents/skills/easytier-config-audit/SKILL.md) |

查找先用 `rg` / `rg --files`。不要把阶段文档中的拟建模块、命令或 API 当成已经存在；实现后同步对应状态与验证记录。编写实施计划时逐任务列开始条件、现有/拟改路径或接口、编号步骤、失败后的状态、输入/操作/结果断言；不得用阶段缩写和“补齐、核对、完成闭环”代替这些内容。计划中的类型与产物未实施前须标为拟新增。

目录归属按 [ADR 0005](docs/design/adr/0005-single-app.md)：配置、总览、设备、日志、设置在 `features/<功能>` 聚合各自 Screen、UiState、ViewModel；功能组件和辅助逻辑留在功能内，测试镜像归属。根仅保留导航与装配，共享代码进入实际复用的 `lib`、`components`、`utils`。根/功能 ViewModel 使用同一 Activity owner 和稳定键，功能不相互注入；仓库、客户端与 Service 会话保持唯一。共享代码不依赖 features。只保留 main/test/androidTest；debug/release 共用真实配置入口，无应用侧样例、Preview 或演示实现。不得增加 data/domain/presentation、逐动作 UseCase、空目录或通用框架。工具须有实际使用场景，缓存和产物归忽略的 build。

## 现有检查入口

从仓库根运行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
python3 scripts/catalog/audit_config_catalog.py
python3 scripts/check_privacy.py
```

打包使用 `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`，首次须准备固定 Rust/NDK 与依赖。native 源码和输出分别为 app/native、app/build/native，Gradle 追踪实际输入输出，JVM/lint 不构建 Rust。本轮取消全部设备执行与 release 冒烟，androidTest 仅编译。保留的设备回归入口为 `python3 scripts/android/verify_device.py --help`，受控对端为 `scripts/android/peer.py`。完整准备、参数、报告和回归见 [开发环境](docs/development/setup.md)。

Python 修改须运行固定 uv + Ruff lint/格式检查及受影响回归；规则见 [ruff.toml](ruff.toml)。隐私检查支持 `--tree index`，不借用未暂存字节。HTML 原型与专用导出器已退役，历史源码及许可证从 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329` 回溯。

P00/P02 一次性实验、模块边界扫描、证据发布/导出与 handoff 聚合工具已退役，日常构建不再运行。冻结 docs/verification、handoff.json、SHA256SUMS 保持原字节；旧路径按历史时点解释，历史源码从 Git 基线回溯。

## 实验与证据治理

技术可行性调查、真机实验、证据整理和阶段冻结先读 [验证规范](docs/development/verification.md)，使用 [verification-evidence skill](.agents/skills/verification-evidence/SKILL.md)。普通开发使用正常测试，不自动生成证据包。

- 运行前声明判据、预算、停止条件和关闭安排；可复用场景转正式测试，必要的独立实验按问题组织；设备/peer 输出放 app/build/device-tests。
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
- 真实会话由服务统一拥有；正式应用始终使用真实核心，失败如实呈现。

## 修改、验证与报告

按变更风险运行最小充分验证：文档/目录校验、相关状态测试、JVM Compose、宿主 Rust 及必要构建检查；P03–P07 不执行设备验证。未能运行的项目写明原因，不把跳过、mock 通过或 HTML 测试写成真机通过。不要用更新截图基线、删除断言或关闭检查掩盖未解释的失败。

涉及核心升级、TUN/JNI 所有权、配置迁移时，同步契约、来源版本和针对性回归证据。只改文案或可逆低影响内容不新增机械重复的测试。

新增或调整目录、命令、能力时，同步受影响的入口与说明。阶段状态集中维护在路线图，版本以构建配置和锁文件为准，目录职责以 ADR 为准；维护文档不覆盖已冻结记录，也不自动生成新的实验包。

代码注释用中文解释意图、生命周期和约束；测试说明清楚条件、操作和可观察结果。公共类型、命令及协议字段保留其原始名称。

不要覆盖不属于本任务的改动。多代理并行先划分文件/接口所有权，整合后验证共同流程。网络密码、私钥、凭据不进入日志、测试夹具或提交记录；使用合成数据。

日常开发从最新 `main` 建短期任务分支，通过 `gh` 创建或更新 PR。完成实际检查后转为 Ready，报告链接、head SHA 和检查结果，默认停在待审；维护者手动 Squash。只有明确的后续合并请求才核对已审阅 SHA 并执行合并，tag 与 Release 另行授权。完整流程以 Git 工作流为准。

本文件不授予发布、签名密钥访问、上传诊断数据或安装个人技能的额外权限。按当前用户已授权范围工作；普通本地实现与验证无需再增加确认步骤。

## 指令范围

模块确有不同约束后再添加局部 `AGENTS.md`，只写差异；样板在 [agent-guides](docs/development/agent-guides/README.md)。加载规则按所用代理确认，不能假定所有宿主都会自动读取任意后代目录。依据：[AGENTS.md](https://agents.md/)、[Codex 加载规则](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。
