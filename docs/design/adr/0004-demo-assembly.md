# ADR 0004：P01 领域边界与 demo 装配

状态：已被 [ADR 0005](0005-single-app.md) 取代。历史源码见 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329`。以下保留当时的决策与路径，现行目录及命令以新 ADR 和开发环境为准。

## 模块与依赖

当前实际模块为 `:app -> :contracts`。`:contracts` 是 Kotlin/JVM 库，仅依赖 Kotlin 标准库、协程与测试 JUnit；保存配置摘要、仓库接口、会话状态和带操作归属的引擎消息。不引用 Android、Compose、JNI 或业务切片。它们是项目自有的最小契约，不是 EasyTier SDK，也不表示完整配置或正式 TUN 生命周期已经实现。

配置草稿、字段校验和编辑状态在 `feature/configuration/edit`，demo 内存仓库与私有 `LocalProfileMetadata` 在同一能力的 demo `storage`。仓库只保存与改名，不能调用会话；UI 接收切片状态与动作，不能访问存储或引擎。`application` 聚合各切片并装配依赖，切片不反向依赖装配层。依赖检查允许测试导入 demo fixture，不把这项例外扩展到主源码。

正式服务与桥接尚无实现，因此不创建 `:vpn`、`:core-bridge`、`:testing` 或空的 `native/`。服务就绪后再接入 `SessionController`，P02 仍需建立 fd、取消、资源排空和真实重连契约。

## 构建与装配

`demoDebug` 使用独立应用 ID 后缀 `.demo`、演示名称和常驻“模拟数据”标记。内存仓库、`FakeEngine`、协调器、fixture 与 Preview 都在 `app/src/demo`；JVM 行为测试在 `src/testDemo`，设备测试在 `src/androidTestDemo`。demo 不产生 release 变体。

`live` 保留正式应用 ID 和原有 release 设置。其 pre-build 依赖 `verifyLiveEngine`，在正式服务/桥接缺失时抛出 `LIVE_ENGINE_MISSING`。live 的 `AppAssembly` 也明确失败，无自动 fake 回退。解除门槛必须随 P02 的真实装配及相关验证实施，不能只增加绕过参数。

P00 候选固定来源见 [原生边界 ADR](0001-native-host-boundary.md)。P01 的 [候选输入清单](../../../gradle/native-candidate.json) 固定核心 commit、NDK、Rust commit、ABI 及锁文件/补丁/参考构建摘要；doctor 和 check-fast 核对清单与冻结来源，改写批准字段、扩大 ABI 或修改来源均失败。清单是接入候选，实验补丁的 `production_approved=false` 继续有效；本次不复制实验 `.so`、批准补丁或改动签名配置。P02 必须实际接入并验证生产实现；本机安装版本只作诊断，不覆盖候选约束。

变体按 Android 的 [source set 与 flavor 规则](https://developer.android.com/build/build-variants) 隔离。Wrapper、Daemon JVM、SDK、构建工具及库版本继续以构建配置为准，不另造依赖管理层。

## 状态、调度与注入

使用构造注入与 flavor 工厂。ViewModel 只创建一次依赖，UI 用 [collectAsStateWithLifecycle](https://developer.android.com/develop/ui/compose/state#other-supported-types-of-state) 收集公开状态；重组不发出连接或订阅引擎事件。草稿与导航在 ViewModel 生命周期内保留，不承诺磁盘持久化或进程死亡恢复。

模拟引擎接收固定事件脚本、单调时钟和注入作用域。JVM 测试共用虚拟调度器，明确执行边界时刻任务；不依赖真实 sleep、网络或随机值。迟到回执先比较 `SessionId/generation` 和操作 sequence，只有当前 pending 能提交。应用失败或超时保留旧 applied，新 saved 仍可重试；停止成功后清除 applied。

待应用只在查看当前运行网络时比较已保存与 applied 的 `core`、`vpnPolicy`，修订号差异不代表需要应用。恢复运行配置仍保存为新修订，保留本地名称和原 applied，不发出引擎命令；恢复后的内容与运行配置一致时不显示待应用。

demo 的 Apply 只是领域回执模拟，不声称已经实现核心热更新或生产重连。真实运行状态必须由 P02 的服务拥有，不能沿用 ViewModel 作用域中的模拟协调器。

## UI 与检查

复用 Material 3 稳定组件与现有动态配色；增加公共尺寸、形状和字体层级，不引入实验性 Expressive API 或 DI 框架。四个入口由轻量查看状态切换，返回先关闭设置，再回总览；配置草稿由明确丢弃动作清除。待 P02/P04 的真实页面和返回动画需求明确后再评估导航框架。

`check-fast.sh` 检查 Kotlin import、当前模块依赖、demo/live 引用和关键 JVM 行为。静态规则针对当前 Kotlin/Gradle 写法，不能替代编译器或证明任意间接依赖安全。主题预览与设备测试源码可编译，不等于预览渲染、TalkBack 或真实设备已验收。

基础渲染另使用仅测试依赖的 Robolectric 4.17、固定 API 37 runtime 与 native graphics；与设备测试复用 `demoUiTest` 中的 fixture/断言。runtime 由 Gradle 的固定配置预先解析，Robolectric 以 offline 模式读取，JVM 测试不自行下载。JVM 与设备测试都显式使用版本目录中的 Espresso，避免 Compose 的旧传递版本调用 API 37 已移除的 `InputManager.getInstance()`。JVM 渲染用于主题、语义及基本交互验收，不能继承真实设备、系统栏或 TalkBack 的结果。配置依据 [Robolectric](https://robolectric.org/getting-started/)、[固定 SDK 映射](https://github.com/robolectric/robolectric/blob/robolectric-4.17/robolectric/src/main/java/org/robolectric/plugins/DefaultSdkProvider.java)及 [AndroidX Test 修复记录](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0-alpha04)。

设备截图使用 [PixelCopy 的 Window 接口](https://developer.android.com/reference/android/view/PixelCopy)，仅在 API 26+ 捕获。API 24–25 执行全部 UI 断言，截图操作在创建目录或文件前记录用例、页面、API 和省略原因，不将用例标记为跳过；高版本截图失败仍使测试失败。JVM 保留 native Canvas 捕获，不代替低版本设备兼容性验证。

自定义顶栏使用 `safeDrawing` 的顶部/横向 inset；内容消费 Scaffold 的 innerPadding 后再处理 IME，避免重复添加系统栏空间。采用 [Compose inset 处理规则](https://developer.android.com/develop/ui/compose/system/insets-ui)，具体键盘和刘海布局仍需设备验证。
