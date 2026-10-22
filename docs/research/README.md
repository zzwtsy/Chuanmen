# 研究资料导航

本目录保留 2026-10-11（Asia/Shanghai）的技术与 Agent 协作研究，来源优先采用官方文档、官方仓库和固定版本源码。机器可读索引保留获取时间、URL、commit 与限制；它们是历史来源记录，不是当前工程或发布政策的实时快照。

实际构建与检查见 [开发环境](../development/setup.md)，阶段状态见 [路线图](../plan/ROADMAP.md)，目录决策见 [ADR](../design/adr/0005-single-app.md)，实验结论见 [验证导航](../verification/README.md)。研究建议被后续决策调整时，在维护文档说明关系，保留原来源日期和身份。

| 资料 | 内容 |
|---|---|
| [android-core-evidence.md](android-core-evidence.md) | EasyTier 稳定版/主干、JNI、fd、protect、运行指标、日志、Material3、Android 平台与发布 |
| [sources-android.json](sources-android.json) | 官方 API / 网页 / 固定源码的读取索引、已知限制及开放门槛 |
| [agents-evidence.md](agents-evidence.md) | AGENTS.md、技能创建/评估、Android 模块化/测试、Now in Android 的可借鉴部分 |
| [sources-agents.json](sources-agents.json) | Agent 和 AI 工程资料的来源与证据边界 |
| [配置来源快照](../design/config/assistant-catalog.snapshot.json) | 官网实际部署 UI 的来源字段和资源哈希；部署 commit 未知 |

## 如何使用证据

1. 先看结论对应的是“已读取源码事实”“本项目设计建议”还是“待真机验证”。
2. 用索引中的固定 commit 或版本打开源码；不要用 main 的新内容替换原先证据。
3. 工具链接入或升级时重新核对 SDK 与依赖兼容性；P07 按实际发布时点重查商店政策。官网助手变化时生成新快照与差异，不改旧来源记录。
4. 论文或文档描述能力、代码存在某个入口、APK 编译成功和真实业务连通，是四种不同证据，不能互相替代。

研究发现的关键实现风险已映射到技术架构与阶段验收；风险是否关闭以对应实现和实测记录为准。

HTML 原型与专用资源已退役，历史源码及许可证在 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329` 的 `prototype/` 中。配置来源快照与 Android 能力计划继续维护，不能因退役原型删除配置映射。
