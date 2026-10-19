# 易查找、可验证的 Android 工程

目标是让开发者或代理能找到负责代码、构造输入、执行相关验证，并说明结论边界。现行目录由 [ADR 0005](../design/adr/0005-single-app.md) 维护，命令见 [开发环境](setup.md)，阶段见 [路线图](../plan/ROADMAP.md)。

## 代码组织

仅保留 `:app`，基础包的六个文件负责宿主与装配，`features/<功能>` 聚合共同变化的 Screen、UiState、ViewModel 及功能组件，主题在 `ui/theme`。不用目录数量表达职责，不预建 data/domain/presentation、逐动作 UseCase、空模块或通用插件。

| 问题 | 先查目录与文件 | 最小验证 |
|---|---|---|
| 名称、保存、草稿与待应用 | lib/profile、features/configuration 的 ConfigurationEditor/UiState/ViewModel | 配置和 ViewModel 状态序列 |
| 导航、复制、表单和主题 | ChuanmenApp、features/<功能>、components、ui/theme | 功能动作、Compose 语义断言与 Activity 重建 |
| 连接、取消、撤销和资源释放 | lib/vpn 下的 NativeVpnService、VpnSessionCoordinator、VpnSessionClient | 正式协调器回执序列，必要设备回归 |
| 核心编码或 JNI | lib/vpn 的编码器与引擎、app/native | codec/native 契约及对应设备场景 |
| 配置范围和显隐 | 配置快照、能力台账、catalog 工具 | 来源映射与实际核心行为分别验证 |
| 流量缺样本或计数重置 | lib/vpn 的 TunTrafficHistory、CoreSnapshot、Runtime；features/overview | 固定计数器与单调时间样本 |

只有独立交付、实际复用或经测量的编译收益出现时再提取模块。共享代码先有消费者，再做抽象。[Android 模块化指南](https://developer.android.com/topic/modularization)

## 状态和替身

Service 唯一拥有真实会话，UI 按生命周期收集状态并发出明确动作。查看配置、本地改名、编辑与仅保存都不能触发连接。draft、saved、applied 及结果未知分别表达，迟到回执按会话代次和操作序号拒绝。

正式应用始终装配真实 Service/JNI。应用无 Preview、演示状态或预置样例；test 的 RecordingSessions 只记录请求，不演进会话。异步状态回归直接驱动正式 VpnSessionCoordinator，使用受控时钟、调度器和引擎回执，避免维护第二套模拟状态机。[协程测试](https://developer.android.com/kotlin/coroutines/test)

五个功能 ViewModel 使用同一 Activity ViewModelStore 和稳定 key，依赖同一个配置仓库与会话客户端；根只持有导航、查看身份和外观偏好。配置编辑器按 profileId 保留草稿；各功能只投影自己需要的数据。共享 lib/components/utils 有实际消费者且不依赖 features，功能不相互注入。

源码与测试只有 main/test/androidTest，测试按功能及共享目录镜像归属。JVM UI 采用固定 Robolectric API 37 与 Compose v2，覆盖导航、草稿、恢复、复制、状态文案、语义、触控尺寸和外观条件。JVM 渲染不证明 TalkBack、系统栏或真实 VPN 已通过。

## 构建与工具

Wrapper、版本目录、Cargo.lock 和 app/native/inputs.json 是版本来源。Gradle native 任务声明实际输入输出，JVM/lint 不依赖 Rust 库；不为单模块引入约定插件或边界扫描器。

scripts 仅维护仍有使用场景的 native、设备/peer、配置目录和隐私工具。新增自动化先说明重复成本、输入输出和退出方式；一次性补救脚本在问题关闭后退役。普通测试输出留 build，常规修改不生成阶段证据包。

## 验证与协作

| 层次 | 支持结论 |
|---|---|
| Python、JVM 与 native 宿主回归 | 输入校验、模型、序列化、并发和资源契约 |
| Compose JVM | 固定状态下的界面语义与交互 |
| APK 构建和对齐 | 编译、双 ABI 库身份和静态 16 KB 对齐 |
| 模拟器 / 真机 / 受控网络 | 本次实际 APK 的平台行为、生命周期或业务回包 |

按风险运行最小充分验证，报告实际命令、结果及未覆盖范围。测试注释用中文说明条件、动作和可观察结果。失败需解释，不能删断言、更新截图基线或关闭检查掩盖问题。

README 与 AGENTS 保留导航和关键约束；设计理由在架构/ADR，命令在 setup，阶段状态在路线图。历史研究与冻结证据保留原身份。Git/PR 流程见 [Git 工作流](git-workflow.md)，实验见 [验证规范](verification.md)。任务复杂时用 [任务模板](task-brief.md)，无需为普通修改建立额外流程。
