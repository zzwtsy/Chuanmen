# Jetpack Compose 注释检查

## 检查范围

只在相关代码中检查下列维度。为非显然的选择补充原因，不给每个 `remember` 或 `@Composable` 自动生成教学注释。涉及 API 或编译器行为时，以项目依赖版本和配置为准。

| 对象 | 值得说明的信息 | 防止写出的错误承诺 |
| --- | --- | --- |
| 状态提升 | 状态所有者、更新事件、为何放在当前层级 | 所有状态都必须放进 ViewModel |
| 组合身份与列表 key | 逻辑身份、重排时保留哪一项状态 | 有 key 就不会重组；key 必须全局唯一 |
| remember | 当前组合位置的保留范围、key 变化后的重算理由 | 能跨 Activity 重建或永久缓存 |
| rememberSaveable / Saver | 保存哪些最小状态、恢复前提、无法恢复的内容 | 等同持久化存储；用户彻底关闭后必然恢复 |
| LaunchedEffect | key 选择、重启条件、取消范围 | Unit 表示整个页面或应用只执行一次 |
| rememberUpdatedState | 哪些值需要更新且不应导致 effect 重启 | 捕获任何值都无需考虑 key |
| DisposableEffect | 注册资源、key 变化或离开组合后的释放 | onDispose 等同于 Activity.onDestroy |
| Flow 收集 | 收集者生命周期、上游共享策略、作用域 | 停止收集就必然停止所有上游任务 |
| derivedStateOf | 输入与有意义结果的更新频率为何不同 | 阻止整个组件的全部重组；总能提高性能 |
| Stable / Immutable | 为什么真实类型满足所声明的契约 | 注解会自动使对象不可变或线程安全 |
| Modifier 与 slot | 非显然的顺序、作用目标、布局或调用约束 | 顺序无影响；slot 永远只执行一次 |
| 无障碍 semantics | 谁承载语义、为何合并或清除 | contentDescription 是源码注释；清除只影响 TalkBack |

## 核验副作用与生命周期

- 明确区分当前调用的 Composition、Activity/Fragment 的 `Lifecycle`、导航目标和 `ViewModel` 作用域。不要笼统写“页面生命周期”。
- 核对 effect 读取的值和 key。相同 key 的普通重组不导致 `LaunchedEffect` 重启；离开后重新进入组合会重新启动。
- 区分“请求/传播协程取消”与“远端副作用已回滚”。取消保证要核对下游是否支持协作取消。
- 查看清理是否对应当前注册对象；不要在未正确释放时写“确保无泄漏”。
- 调用者必须知道的行为放 KDoc；局部实现为什么采用某个 key，放在 key 或 effect 附近。
- 不用注释掩盖业务副作用直接发生在组合体、漏 key、错误捕获或缺少清理；将代码问题单独报告。

## 核验稳定性与性能

`@Stable`、`@Immutable` 是对编译器的契约声明，不是验证器。检查公开可观察属性、可变内容、别名修改和变更通知，不从 `val` 或只读集合接口直接推断深层不可变。

重组跳过规则受 Compose Compiler 模式影响。strong skipping 启用时，带不稳定参数的可重启 composable 也可以被跳过。写“必然重组”“lambda 每次新建”前先检查 Kotlin、Compose Compiler 及相关配置；不要把某版默认行为写成永远成立的规则。

说明优化对象及适用条件即可；只有真实测量支持时才写重组次数、帧率或收益数字。注释任务不新增稳定性注解、不调整 key、不切换编译器配置。

## 示例：状态所有权

```kotlin
/**
 * 编辑由调用方持有的 [query]，通过 [onQueryChange] 发出文本变更请求。
 *
 * 调用方负责更新 [query]；[modifier] 应用到输入框。
 */
@Composable
fun QueryField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
    )
}
```

这是说明契约粒度的片段，使用项目现有的 Compose imports。不要据此给真实组件添加原本不存在的状态控制承诺。

## 示例：计时不随回调替换而重启

```kotlin
@Composable
private fun TimeoutEffect(
    timeoutMillis: Long,
    onTimeout: () -> Unit,
) {
    // 回调替换只影响到期后执行的行为，避免因此重新开始等待。
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    // 等待时长变化时重新计时；该调用离开组合时取消尚未完成的等待。
    LaunchedEffect(timeoutMillis) {
        delay(timeoutMillis)
        currentOnTimeout()
    }
}
```

不要把这段说明改成“计时器只执行一次”。它描述的是当前组合实例与给定 key 下的行为，重新进入组合会重新计时。

## 无障碍与运行时文本

中文注释要求适用于解释文字。`contentDescription`、`stateDescription`、字符串资源、测试输入、`testTag` 都可能参与运行行为，不能顺带翻译或修改。若清理特殊语义或解释装饰图标的空描述，先确认信息确实由父组件或邻近语义承载。
