# 依据与适用范围

核验日期：2026-10-08（Asia/Shanghai）。

中文注释和逐个测试的中文意图说明来自用户要求；“条件—操作—可观察结果”、最小注释改动与真实性核对是本 skill 的工程约定。官方资料提供语法、框架行为和适用范围，不应将项目约定包装成官方强制标准。

需要重新确认某条框架事实或处理版本差异时，读取对应的一手资料；常规注释编辑先依据项目代码、已有规范和锁定版本，不要求每次重新研究全部来源。

## Kotlin 与 API 文档

| 来源 | 支持的规则与适用边界 |
| --- | --- |
| [Kotlin Coding conventions](https://kotlinlang.org/docs/coding-conventions.html#documentation-comments) | 简短参数/返回说明融入正文，长说明再用标签；同页测试命名节说明 Android 上含空格方法名的 API 30 边界。 |
| [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) | 摘要、Markdown、符号链接及支持的标签；异常文档按调用方需要提供，不能使用 Javadoc 的弃用标签。 |
| [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide#documentation) | 公开 API 文档基线、自明声明和部分 override 的例外；标签不得为空；命名节给出 ASCII 风格。 |
| [AndroidX KDoc guidelines](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/docs/kdoc_guidelines.md) | 面向 Jetpack Kotlin API；显式参数/属性标签用于完整 API 表格，真实样例可用 @sample 引用。 |
| [Android API Guidelines — Docs](https://android.googlesource.com/platform/developers/docs/+/refs/heads/main/api-guidelines/docs.md) | 调用契约需自包含；同步抛出与异步回调失败必须区分。文中的 Javadoc 写法需转换为 KDoc。 |

## Compose

| 来源 | 支持的规则与适用边界 |
| --- | --- |
| [Compose API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md) | 区分框架、库与应用的要求级别；作为状态、组件与 Modifier 契约的设计背景，运行时细节仍查对应版本 API。 |
| [Where to hoist state](https://developer.android.com/develop/ui/compose/state-hoisting) | 状态所有权、最低共同祖先、UI 与业务逻辑的作用域；不要求所有状态进入 ViewModel。 |
| [Lifecycle of composables](https://developer.android.com/develop/ui/compose/lifecycle) | 进入/重组/离开组合与实例身份；不等同 Android Lifecycle。跳过规则需结合 strong skipping。 |
| [Side-effects in Compose](https://developer.android.com/develop/ui/compose/side-effects) | effect 的 key、重启、取消、最新回调、清理，以及 derivedStateOf 的适用条件。 |
| [State and Jetpack Compose](https://developer.android.com/develop/ui/compose/state) | remember、rememberSaveable 及 Flow 的状态读取；状态恢复有生命周期与保存机制的边界。 |
| [Fix stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix) | 稳定性注解是契约，不自动改变类型；错误声明可能破坏重组。 |
| [Strong skipping mode](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping) | 带不稳定参数的可重启函数也能跳过；需检查项目 Kotlin/Compose Compiler 模式。 |
| [Merging and clearing semantics](https://developer.android.com/develop/ui/compose/accessibility/merging-clearing) | 语义操作影响无障碍、测试等消费者，不是源码注释。 |

## 测试

| 来源 | 支持的规则与适用边界 |
| --- | --- |
| [Fundamentals of testing Android apps](https://developer.android.com/training/testing/fundamentals) | 官方示例用条件、操作和期望组织测试；不要求每个测试机械添加三个标题。 |
| [Testing Kotlin coroutines](https://developer.android.com/kotlin/coroutines/test) | 共享 TestCoroutineScheduler、不同 dispatcher 行为及虚拟时间调度。 |
| [TestScope.advanceTimeBy](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/advance-time-by.html) | 目标时刻的任务与时间推进分开，必要时运行 runCurrent；按项目依赖版本核对。 |
| [Testing Kotlin flows](https://developer.android.com/kotlin/flow/test) | StateFlow 合并、当前值断言、启动上游所需订阅、backgroundScope。 |
| [Compose testing APIs](https://developer.android.com/develop/ui/compose/testing/apis) | 基于语义树定位、操作和断言；区分合并与未合并树。 |
| [Synchronize Compose tests](https://developer.android.com/develop/ui/compose/testing/synchronization) | 默认同步、主时钟与外部工作边界；新增 API 的适用版本需核对。 |
| [Compose testing common patterns](https://developer.android.com/develop/ui/compose/testing/common-patterns) | 模拟保存恢复测试的能力边界；不应为测试滥加生产语义属性。 |

## Agent Skills

- [Agent Skills specification](https://agentskills.io/specification)：明确 name/description；主文档精简，参考资料按需读取，文件引用从主文档可直接发现。
- [Best practices for skill creators](https://agentskills.io/skill-creation/best-practices)：用具体领域约束和可执行流程代替泛泛建议；提供默认做法，按风险约束自由度，并通过真实任务式演练修正。
