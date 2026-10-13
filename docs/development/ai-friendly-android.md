# Chuanmen · AI 友好的 Android 工程方案

本文规定工程边界、测试分层与代理协作方式。现有环境和命令见 [开发环境](setup.md)，阶段状态统一维护在 [路线图](../plan/ROADMAP.md)。目标接口、demo 和 Android 通用脚本须落实后才成为工程能力；研究引用按原检索日期理解。

## 1. 判断目标：让修改可以被找到、运行和验证

对本项目而言，AI 友好意味着：另一位开发者或编码代理接到一个具体问题后，能找到负责代码、构造输入、复现现象、执行相关验证，并解释证据与限制。它不要求在客户端中加入 AI API，也不依赖某个特定代理、IDE、MCP 或付费服务。

成功标准采用下面四个可观察结果。时间数字是项目初始目标，P01 首次测量后可在记录理由的前提下调整。

| 能力 | 初始验收目标 | 对 Chuanmen 的价值 |
|---|---|---|
| 可发现 | 从 README 和根 AGENTS.md 出发，5 分钟内找到一类问题的模块、契约和验证入口 | 减少把 UI 问题误修到核心参数或把运行状态写回草稿 |
| 可构建 | 干净环境可按锁定版本清单构建 demo；失败指出缺失依赖 | UI 开发不受测试组网和账号可用性影响 |
| 可复现 | 同一夹具、时钟与动作序列得到相同领域状态；失败记录种子和环境 | 重现断线、待应用、恢复和统计计数器重置 |
| 可验证 | 变更能映射到最小验证集合，命令有退出码和报告路径 | 避免“能编译”被误写成“真实 VPN 已正常工作” |

## 2. 已具备、应建立与暂不建立

| 时间点 | 应具备的东西 | 明确边界 |
|---|---|---|
| 已有工程基础 | `:app -> :contracts`、demo 切片与共享主题、构建配置、设计/原型/配置目录、Python 与 Android 检查、CI 配置、代理规则 | 本地及远端通过的范围见 P01；首次 SDK 安装失败与后续成功分别保留 |
| P00 历史验收 | 固定核心来源、许可技术复核、限定场景的实验源码与冻结证据 | 结论以 [P00 报告](../verification/p00/report.md) 的制品和范围为准，维护后的探针不继承旧验收 |
| P01 退出目标 | 干净环境构建、首条业务切片、demo 数据源、领域契约、导航、Android 快速检查和 CI | 复用已有基础，补齐剩余门槛；通用脚本须真正创建、运行后才进入可用命令列表 |
| P02 | 真正的 VpnService/native 生命周期、数据路径、结构化诊断与故障夹具 | 获得这批证据后再提炼核心升级和 VPN 排障技能 |
| P03—P05 | 配置、监控和高级能力的契约测试、场景复现、能力门控 | 每实现一项同步其目录、迁移规则和测试，不先写一套空壳生成框架 |
| P06—P07 | 兼容性、性能、签名与发布证据 | 没有真机或发布环境时记录缺口，不将缺口转换成通过 |

暂不建立“全能 Android 开发 skill”、每页一个 Gradle 模块、自动把任意 Markdown 变成代理指令的加载器、自动批准发布的机器人。它们尚不能解决本项目已经证实的重复成本。

## 3. 保持工程入口短，按任务发现细节

根 `README.md` 服务人，说明项目状态、启动入口和文档导航；根 `AGENTS.md` 只保留代理执行任务时不可遗漏的事实、命令状态和不变量。设计理由放总体设计，阶段工作放 `docs/plan/`，可重复的专门流程放项目 skill。不要把一篇长设计文档复制到三处。

`AGENTS.md` 本身是开放的 Markdown 约定，没有统一必填章节；具体加载和覆盖行为需要按使用的代理验证。[AGENTS.md 官方说明](https://agents.md/)；[Codex 的加载规则](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。

P01 应维护一张很小的任务导航表，更新时与实际目录一致：

| 问题类型 | 预期首先阅读 | 最小证据 |
|---|---|---|
| 配置名称、保存、待应用状态错误 | 配置切片的状态、保存和改名操作；其 storage/codec 实现及必要跨模块契约 | 一个状态序列或迁移夹具 |
| Tab、复制反馈、表单布局 | `:app` 对应功能包与设计令牌 | 状态驱动的 Compose 预览及交互断言 |
| 开始连接、停止、撤销 VPN 权限 | `:vpn` 生命周期；`:core-bridge` 调用边界 | 会话事件序列及相关设备测试 |
| 核心参数含义、字段缺失 | 固定核心版本；配置目录；能力映射 | 官网来源、序列化输入输出、核心校验结果 |
| 流量归零、出现负值、跨网络串数据 | 会话 ID、计数器口径、单调时钟契约 | 带重置/缺样本的确定性时间序列 |

模块 README 只写职责、公开入口、依赖和相关验证；出现第三次重复查找再补导航。Now in Android 使用模块说明和依赖图帮助定位结构，可借鉴这个做法；它展示的模块数量和构建任务不应直接复制到本项目。[官方样例的模块化说明](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)。

## 4. 模块边界以实际责任划分

当前为 `:app -> :contracts`，已有 demo 配置编辑、四页导航与共享主题；`:vpn`、`:core-bridge` 按实际消费者和实现建立，目录与依赖见 [目录边界 ADR](../design/adr/0003-directory-boundaries.md)和[demo 装配](../design/adr/0004-demo-assembly.md)。

配置编辑、改名、保存、应用，以及日志、设备、首页和偏好相关变更，先查所属能力和操作。状态、校验、持久化和测试聚合在切片；跨模块接口才进入 `:contracts`。根导航和 DI 归 application，公共视觉能力归 shared/designsystem。测试保留标准 source set，镜像被测包。

正式服务应持有唯一真实会话，JNI 适配通过窄桥实现。当前模拟仓库由配置切片提供，经装配注入；业务 UI 不访问存储或引擎。Kotlin 包不是编译隔离边界；check-fast 已落实当前依赖/import 检查，有实际隔离或编译收益后再提取功能模块。

工程工具按任务聚合在 scripts；固定问题的实验、fixture 和探针归 experiments；输出归忽略的 build 目录。Android 通用工具已在 scripts/android 提供。实际复用后才提取共享支持，不提前生成空目录、页面模块或通用插件框架。[Android 模块化指南](https://developer.android.com/topic/modularization)

## 5. 构建环境必须能明确复现

已有 JDK、Gradle Wrapper、AGP、Compose 编译插件、Compose BOM 和 SDK 声明以构建配置为准，位置与安装要求见 [开发环境](setup.md)。正式 native 工程接入时，再按验证结果锁定 NDK、Rust toolchain、目标 ABI 和核心来源；实验工具链只说明相应探针的构建条件。不要在 README、AGENTS.md 和阶段文件维护多套版本号。

Gradle Wrapper 能使用声明的 Gradle 版本；分发包校验和与 Wrapper JAR 完整性校验处理的是不同对象。版本目录集中管理依赖声明，依赖校验用于检查所取制品，二者也不可互相替代。[Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)；[Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)；[Dependency Verification](https://docs.gradle.org/current/userguide/dependency_verification.html)。

本项目建议：

- 仓库提交 Wrapper、版本目录、Rust 锁文件和 native 来源记录；升级依赖和业务修改尽量分开审阅。
- 文档列出首次联网获取依赖的要求。“demo 无需真实组网”不等于“空缓存能离线构建”。
- 保留一条不需要签名密钥、生产凭据或私人服务器的 demo 构建路径。
- 将相同 Android 编译设置抽成少量约定插件；单个模块的特例留在模块内，不提前造插件框架。Now in Android 的 `build-logic` 采用可组合约定插件来统一共同设置。[样例构建约定](https://github.com/android/nowinandroid/blob/main/build-logic/README.md)。
- 需要比较或验收的构建记录源码 revision、环境版本、variant、ABI 与制品哈希；普通检查使用运行报告，不全部永久入库。在实际比较之前，只称“环境与步骤可复现”，不承诺 APK 字节完全一致。

## 6. 用可执行入口代替散落的命令提示

现有 Python 工具与实验源码的 lint、格式检查使用根 [ruff.toml](../../ruff.toml)，运行命令见 [开发环境](setup.md)。CI 通过隔离的 uv 工具环境执行固定版本的 Ruff；校验脚本自身仍使用标准库。规则例外限定到具体 CLI 文件或有中文理由的语句，子进程显式声明 `check` 并保留原有退出码处理与日志记录。

以下四个 Android 通用脚本已创建；实际参数、运行结果和设备执行范围见 [开发环境](setup.md)和[P01](../plan/p01-foundation.md)。demo 检查与实验探针各自验证对应范围。

| 实际入口 | 输入和职责 | 命令契约 |
|---|---|---|
| `scripts/android/doctor.sh` | 检查工具链、SDK/NDK、ABI、核心来源 | 默认只读；报告缺什么及安装说明，不悄悄改全局环境 |
| `scripts/android/check-fast.sh` | 执行格式检查、领域测试、配置目录与契约检查 | 缺依赖或有失败返回非零；打印各子项和报告路径 |
| `scripts/android/build-demo.sh` | 构建固定 demo variant | 不加载真实 VPN/native 引擎，不要求生产秘密 |
| `scripts/android/verify-device.sh` | 对指定测试设备运行已实现的设备验证配置 | 无设备时明确 blocked；不把跳过写成通过；P02/P06 逐步扩充 |

每个脚本都要有 `--help`、明确工作目录、稳定退出码、无交互模式、超时和报告目录约定。长输出保存在工件里，终端给摘要。不要把四个入口包装成“万能脚本”后隐藏所有错误；报告仍须能定位失败的 Gradle task、测试类或 native 命令。

单独修正文案无需重跑全部设备组合；改会话状态机则至少跑相关领域序列和服务验证；改 JNI 所有权则补 native 边界与真机生命周期证据。检查集合由变更风险决定，不能靠泛用 `./gradlew test` 猜测所有 variant 都适用。Now in Android 的文档本身也列出特定 demo variant 的测试命令，可见命令必须与仓库实际构建模型对齐。[Now in Android README](https://github.com/android/nowinandroid)。

## 7. 确定性数据源是优先投入

`CoreEngine` 的 fake/real 实现通过构建配置和依赖装配选择。demo 只消费夹具；real 初始化失败应进入明确错误状态，不能自动回退到 fake 并显示“已连接”。

领域层注入时钟、调度器与外部操作接口。重连超时、流量采样、延迟提示用可控时间推进；测试不靠睡眠等“应该已经完成”。Android 的协程测试文档提供 `runTest`、共享测试调度器与调度器注入来控制协程时序。[Android 协程测试](https://developer.android.com/kotlin/coroutines/test)。

P01—P04 应逐步积累以下项目夹具：

| 场景夹具 | 要验证的观察结果 |
|---|---|
| 已连接 A，正在查看/编辑 B | B 的编辑不改变 A 的会话，切换查看不自动切换连接 |
| 修改配置，仅保存，再应用失败 | saved 可变化，applied 仍是实际运行版本，错误可恢复 |
| 本地改名后恢复上次应用配置 | 本地名称保留，网络身份按配置恢复 |
| 核心启动中撤销授权或收到停止 | 旧回调不会把已停止会话重新标为连接 |
| 计数器重置、缺样本和真实零流量 | 重置产生新基线；缺样本不同于零，不绘制伪造尖峰 |
| 静态 IPv4 切回 DHCP | 表单摘要、核心序列化、TUN 计划一致 |
| Android 包名单中应用卸载 | 显示失效项及处理结果，不静默扩大代理范围 |
| 不同能力版本读入同一配置 | 不支持项给出可解释处理，不悄悄删除原值 |

这些夹具包含合成地址和密码，不收录用户真实网络秘密。边界错误需要真实解析、迁移和规则实现来暴露；只验证“某 mock 方法被调用一次”的测试不能代替行为断言。Android 测试指南强调可替换依赖与隔离业务逻辑；Now in Android 的接口替身也可作为具体参考。[测试基础](https://developer.android.com/training/testing/fundamentals)；[官方样例测试方式](https://github.com/android/nowinandroid#testing)。

## 8. UI 应同时适合预览、无障碍与自动测试

把负责获取 ViewModel 的路由组合函数与只接收状态/动作的界面组合函数分开。预览直接传入状态，不临时启动真实服务。Android Studio 的 Compose 预览不提供完整网络、文件与框架能力，直接注入 ViewModel 也有约束。[Compose Preview 官方说明](https://developer.android.com/develop/ui/compose/tooling/previews)。

每个关键界面优先有少量典型状态预览：正常、空、错误、长文本；配置页再覆盖未保存与待应用。主题测试固定种子色/色板，避免测试机器壁纸变化造成伪差异；另做少量动态色集成验收。

图标按钮具有有意义的动作描述，开关暴露实际状态，表单错误关联输入。测试先使用语义定位，只有文本或重复列表确实不适合时才增加稳定 test tag。不要为自动化塞入对用户无意义的 `contentDescription`。Compose 测试使用语义树，无障碍也是这份信息的主要使用者。[Compose Semantics](https://developer.android.com/develop/ui/compose/testing/semantics)。

截图差异必须人工看过再接受新基线。图表截图冻结时钟和曲线数据；动画、每秒变化数值和真实网络日志不应成为偶发失败来源。截图验证布局，语义断言验证操作，真机验证 Android 行为，三者分别记录。

## 9. Native 与 VPN 边界要有可读契约

P02 必须把以下内容写成接口说明和测试，不能只靠函数名猜测：

- TUN 文件描述符由谁创建、交接、持有、关闭；失败和取消路径是否重复关闭。
- native 回调在哪个线程发生、如何切换到 Kotlin 协程、何时不再允许回调。
- JNI 句柄、内存与字符串的生命周期，异常/错误如何跨边界返回。
- 每个 `sessionId` 的开始/停止顺序，晚到事件的处理，重复命令的结果。
- 配置版本、核心版本、能力集的映射，不把“当前主干有此字段”当成所嵌入版本已有能力。
- 本机 TUN 统计和核心 peer/隧道统计分别来自哪里，计数器何时清零，单位是什么。

Android 的 JNI 指南强调边界复杂度和线程/引用约束；VPN 文档说明应用通过 VpnService 建立 TUN，隧道 socket 需要避免再次进入 VPN 路径。这些是测试边界的依据；P00 实验验证限定问题，正式产品工程仍须建立接口并验收。[JNI tips](https://developer.android.com/ndk/guides/jni-tips)；[Android VPN](https://developer.android.com/develop/connectivity/vpn)。

## 10. 诊断要形成可复现的最小证据

建议 App 与核心日志统一采用可辨认事件结构，但保留 `source`。基础字段包括：事件时间、单调相对时间、`sessionId`、本地 `profileId`、事件码、严重程度、核心/应用版本、操作结果。网络密码、私钥、凭据不进入日志；IP/设备名是否保留由导出脱敏策略决定。

错误报告应能回答“在哪个阶段失败、哪份配置版本、什么证据、下一步验证什么”。例如 `TUN_ESTABLISH_FAILED` 与 `PEER_NOT_REACHABLE` 属于不同阶段，不能都压成“连接失败”。日志页保留两来源筛选，设备页按已确认 UX 不重新增加“相关日志”入口。

开发诊断包只读导出相关片段、环境和脱敏配置摘要；默认不上传。故障夹具记录原始证据与推断的区别，避免一个 NAT 类型就被推断为所有连接必然失败。

## 11. CI 分层，并如实标记验证范围

| 验证层 | 适合任务 | 环境和失败证据 |
|---|---|---|
| 快速静态/领域 | 配置目录、序列化、状态转移、迁移、格式 | 开发机/CI；退出码、断言与差异 |
| Android 主机/UI | ViewModel、组件语义、固定场景截图 | 锁定运行环境；测试报告与差异图 |
| 设备集成 | VpnService、权限、通知、路由、JNI | 指定设备/API/ABI；系统日志和会话记录 |
| 网络/长稳 | Wi-Fi/蜂窝切换、中继、IPv6、Doze、长连接 | 实验拓扑、时长、流量口径、失败条件 |

Android 区分主机上的本地测试和设备上的 instrumented 测试；测试大小与运行位置不是同一维度。[测试基础](https://developer.android.com/training/testing/fundamentals)。构建管理设备可以统一部分设备测试环境，但不能替代 OEM 电源管理、真实蜂窝和多 NAT 拓扑验证。[构建管理设备](https://developer.android.com/studio/test/managed-devices)。

每个 PR 报告“运行了什么、结果、未覆盖什么”；没有设备应写“未运行/环境阻塞”，不能写“测试通过（跳过设备）”。仅文档变更不强制原生全套测试。P06 再依据风险扩大矩阵，避免每个小变更消耗完整长稳测试。

分支、PR 标题、变更分类与合并门禁由 [Git 工作流](git-workflow.md) 维护，版本与发布由 [版本治理](versioning.md) 维护。代理交付检查完成的待审 PR，维护者手动 Squash；自动合并关闭。

## 12. 给代理的任务应包含契约与验收

建议新任务采用以下结构，而不是仅说“按最佳实践实现”：

```text
目标：修复已保存未应用的网络参数被 UI 误显示为当前运行值。
相关阶段：P03；范围：配置状态与对应界面，不调整 native 协议。
入口：领域状态契约、当前失败场景夹具、负责模块路径。
保持：本地显示名称独立；查看网络与运行网络独立。
验收：仅保存不改变 applied；应用失败保留原运行版本；重试成功才更新。
证据：相关测试命令、结果和必要界面截图；指出未做的设备验证。
```

这只是任务模板，不能代替先阅读实际实现。多代理并行时按目录或接口边界分工，接口变更先对齐，整合方执行端到端验证；不要让多个代理同时修改一份导航/状态文件。

## 13. 最小落地清单

- **P01 必须完成**：工程导航、固定环境、四个脚本的初始可运行版本、fake/demo、纯领域状态测试、关键 Compose 预览、CI 快速门槛。
- **P02 必须完成**：TUN/JNI 所有权契约、真实生命周期设备证据、结构化诊断、核心来源清单；据此决定是否把重复升级/排障工作提炼为 skill。
- **每次功能变更同步**：配置目录/能力约束、必要测试、模块入口与受影响文档。脚本和代码能验证的规则交给检查，不靠根 AGENTS.md 反复提醒。
- **P06 评估后再扩展**：更多模块、截图覆盖、性能基线、较大设备矩阵和自动升级流程；以观察到的维护成本为依据。

维护事实各归一处：阶段状态在路线图，现有命令在开发环境，目录职责在 ADR，依赖和技能来源在配置与锁文件。变更能力、目录或命令时更新受影响说明；历史研究、原始来源与冻结证据保留原日期和身份。维护文档本身不触发实验冻结。

技能范围及创建时机见 [Agent Skills 策略](agent-skills-strategy.md)；模块说明样板见 [代理指南](agent-guides/README.md)。本文的资料与建议分界另见 [研究证据](../research/agents-evidence.md)。
