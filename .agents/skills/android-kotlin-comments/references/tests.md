# 测试代码的中文意图说明

## 目录

- [逐例说明](#逐例说明)
- [先核对验证范围](#先核对验证范围)
- [命名与参数化用例](#命名与参数化用例)
- [协程与 Compose 的特殊说明](#协程与-compose-的特殊说明)
- [普通单测示例](#普通单测示例)
- [Compose 测试示例](#compose-测试示例)
- [边界时间示例](#边界时间示例)

## 逐例说明

为任务范围内每个新增、修改或要求补齐说明的测试，在测试声明附近添加简体中文行注释或 KDoc。覆盖本地单测、Robolectric、设备测试、Compose UI 测试及项目自定义 source set；不要只搜索固定目录或只识别 JUnit 的 `@Test`。

一句话必须能回答：**什么条件下，执行什么操作，验证什么可观察结果？** 简单用例一句足够；不要强制所有测试写成长模板。

```kotlin
/** 验证输入为负整数时，数量解析返回 null。 */
@Test
fun negativeQuantity_returnsNull() {
    assertNull(parseQuantity("-1"))
}
```

复杂流程按需增加具体的 `// 前置：…`、`// 操作：…`、`// 预期：…`。仅写“准备数据”“执行测试”“验证结果”，或只保留 Given/When/Then 标题，不满足要求。已有准确的中文意图说明直接保留，不再叠加同义注释。

类级说明只交代共有场景，不能替代每个测试的具体目标。异常断言、属性测试、交互验证和框架提供的隐式断言也可能构成验证，要检查真实机制。

## 先核对验证范围

按“fixture/fake → 操作 → 断言”阅读；不要只根据方法名或旧注释猜测测试在证明什么。

| 实际证据 | 可以写出的中文说明 | 不能扩大成 |
| --- | --- | --- |
| `assertExists()` | 目标节点存在于查询的语义树 | 用户可见 |
| `assertIsDisplayed()` | 目标节点满足显示断言 | 像素、颜色或布局完全正确 |
| `uiState.value` 的断言 | 当前状态等于期望值 | 所有中间状态依序发出 |
| 某 fake 的调用计数为 0 | 未调用该替身对应接口 | 整个应用没有任何网络请求 |
| `StateRestorationTester` | 模拟保存实例状态恢复后保留目标值 | 真实进程死亡后的端到端恢复已验证 |
| 只检查异常类型 | 抛出该类型异常 | 异常消息、重试次数和副作用也正确 |

如果旧说明承诺了实现未断言的行为，记录具体覆盖缺口；只在能确认应缩小说明时改写。若测试没有有效断言，不能补一句“验证成功”充数，也不要为了配合注释擅自添加断言、修改预期或调整 fake。将未能满足的测试意图要求如实报告。

回归说明必须有真实缺陷或需求依据；不要捏造“历史线上事故”、issue 编号或产品要求。

## 命名与参数化用例

保留项目现有测试方法名和标识符。默认通过中文注释满足要求，不为此批量重命名或新增 `@DisplayName`。中文测试名已经符合项目惯例时也不擅自改回英文。

Android 风格推荐 ASCII 标识符，测试名可含下划线。Kotlin 允许测试名称使用反引号和空格，但含空格的方法名在 Android runtime 上从 API 30 才受支持；区分 JVM 本地运行与设备矩阵。这不意味着 Kotlin 语言禁止中文标识符。

参数化、动态或数据驱动测试需说明共同规则和不同用例的含义；靠近各组数据标出关键边界与期望。只改注释时保留输入值、数据结构及显示名；更广泛的测试编写任务可沿用已有中文用例描述机制。

## 协程与 Compose 的特殊说明

- 解释测试替身提供了什么条件，以及为何不使用真实依赖；不要只写“创建 mock”。
- 将 `runCurrent()` 写成执行当前虚拟时刻任务，按实际用途说明是启动收集、消费事件还是执行边界任务。
- `advanceTimeBy` 推进的是对应测试调度器的虚拟时间；当前 API 不执行恰好在目标时刻的任务，验证该边界通常还需 `runCurrent()`。先核对项目版本与代码。
- `advanceUntilIdle()` 不代表所有真实线程、外部 I/O 或其他 scheduler 全部完成。
- 说明订阅是否为启动 `stateIn(WhileSubscribed/Lazily)` 的上游所必需，以及测试结束时如何取消。不要把 `StateFlow` 的当前值断言写成全量事件序列验证。
- Compose 的主测试时钟、协程调度器、外部数据工作不自动共享控制。说明等待的具体条件；不要把固定 sleep 描述成可靠同步。
- 仅在有额外含义时解释 `autoAdvance = false`、手动推进动画、idling resource 或 `waitUntil`；明确验证哪个时刻或状态。
- 默认查询合并语义树；若用 `useUnmergedTree = true`，说明需要哪个被合并的子节点。不要为解释测试而修改生产语义。
- 区分组合保存恢复、Activity 重建和真实进程恢复；断言哪一层，就描述哪一层。

## 普通单测示例

下面的示例包含被测实现，用于展示说明与断言的对应关系；实际项目使用真实被测对象。

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun parseQuantity(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it >= 0 }

class QuantityParserTest {
    /** 验证输入两端带空白时，解析得到去除空白后的非负数量。 */
    @Test
    fun surroundingWhitespace_returnsQuantity() {
        assertEquals(12, parseQuantity(" 12 "))
    }

    /** 验证输入为负整数时，解析返回 null。 */
    @Test
    fun negativeQuantity_returnsNull() {
        assertNull(parseQuantity("-1"))
    }
}
```

## Compose 测试示例

片段使用 JUnit4 和项目已有的 Compose test rule、被测组件；省略 imports。中文说明中的两个结果均有断言。组件和界面文本在接入时从真实代码核对，不能据此修改产品文案。

```kotlin
/** 验证加载状态下渲染提交按钮时，按钮显示且不可用。 */
@Test
fun loading_submitButtonIsVisibleAndDisabled() {
    composeTestRule.setContent {
        SubmitButton(isLoading = true, onSubmit = {})
    }

    composeTestRule.onNodeWithText("提交")
        .assertIsDisplayed()
        .assertIsNotEnabled()
}
```

这里没有断言回调次数，不能把说明扩写成“点击后不发起任何提交请求”。

## 边界时间示例

假设已有 `SearchController` 的产品契约是输入后防抖 300 毫秒，并使用注入的 scope；这些前提必须在真实项目核对。示例中的业务类型和调度行为不构成对任意搜索实现的断言。

```kotlin
/** 验证输入后的 299 毫秒内未搜索，达到 300 毫秒时仅提交一次当前查询。 */
@Test
fun query_debounceDeadline_searchesOnce() = runTest {
    val repository = RecordingSearchRepository()
    val controller = SearchController(repository, scope = this)

    controller.updateQuery("Compose")
    // 启动控制器在当前虚拟时刻排队的任务，使防抖等待从此刻开始。
    runCurrent()

    advanceTimeBy(299)
    assertTrue(repository.queries.isEmpty())

    advanceTimeBy(1)
    // 执行恰好在 300 毫秒到期的任务，检查防抖阈值的边界。
    runCurrent()
    assertEquals(listOf("Compose"), repository.queries)
}
```
