# ADR 0005：单应用模块与功能切片

状态：已采纳，2026-10-19。取代 ADR 0003 的目录/模块规则与 ADR 0004 的 demo 装配规则。P02 历史范围保留；采纳时 P03–P07 暂停，2026-10-23 已重新规划。当前阶段与验收范围见路线图。

## 问题与取舍

当前只有一个 Android 应用和一个活动 VPN。四个 Gradle 模块、按操作分包、两套运行变体和阶段发布工具增加了修改入口、构建组合与维护成本。现阶段没有独立交付或可测量的编译收益支撑这些结构。

单模块仍需要功能边界。用真实的功能目录聚合界面、展示状态、ViewModel 和功能逻辑，根目录只保留装配与已有共享能力。目录、状态所有权和调用方向共同形成切片；不以移动 Screen 或新增空 ViewModel 代替职责调整。

## 决策

- Gradle 仅 `:app`。基础包为 `com.github.zzwtsy.chuanmen`；根直属代码限于 Activity、Application、装配、导航外壳及根状态和 ViewModel。
- `features/configuration`、`overview`、`devices`、`logs`、`settings` 各有 Screen、UiState、ViewModel。配置编辑器与输入构造在 `features/configuration/lib`；改名框、曲线、设备卡片在各自功能的 `components`。没有实际声明时不创建空目录。
- 根 `components` 仅收纳多个功能实际使用的 UI；根 `lib/profile` 保留配置身份和仓库，`lib/vpn` 保留会话、Service 与原生适配；`lib/AppearancePreferences` 供根主题和设置共享；纯 IPv4 字面量校验在 `utils/Ipv4`。主题保留 `ui/theme`。
- 功能不互相引用内部实现。共享代码不依赖功能。Service 通过 `ProfileRepositoryOwner` 读取唯一仓库，不依赖根 Application 具体类型。单模块 `internal` 不提供包隔离；以明确导入、职责和行为测试核对边界，不新增扫描框架。
- 根 ViewModel 仅拥有导航、查看身份、共享外观对象与顶栏摘要；五个功能分别投影真实数据并处理自己的动作。配置独占编辑器。根与五个功能 ViewModel 使用同一 Activity 的稳定 ViewModelStore，不互相注入，不按 profileId 创建实例。
- Application 持有唯一配置仓库（P03 使用持久仓库及其内存快照）和一个 `VpnSessionClient`；Service 继续唯一拥有真实会话。页面离开、Activity 配置重建或功能 ViewModel 清理均不停止 VPN，也不新增真实采样器。
- 实体源码目录仅 `main`、`test`、`androidTest`。保留 Android 标准 debug/release 构建类型，两种构建共用正式配置入口和 Service/JNI；无应用侧样例、Preview、演示状态机或变体业务入口。测试合成输入与替身仅在测试中。
- 首次入口不预填网络、节点或地址；本地名称可选，空白回退网络名称；自动分配 IPv4 默认开启，互联网流量默认关闭。保存不连接。P03 将已保存配置认证加密落盘；未保存输入由 Activity 配置 ViewModel 持有，配置重建保留草稿，进程重新创建只恢复 saved。
- Rust 自有源码、补丁、来源与锁文件在 `app/native`，固定上游 checkout、Cargo target、双 ABI 库及报告在 `app/build/native`。Gradle 声明实际输入输出，只在消费 JNI 时构建；普通 JVM/lint 不要求 native 产物；独立 `testHostNativeCodec` 任务显式构建宿主库验证 JNI 契约。
- scripts 仅保留 native 构建/APK 检查、真实设备回归与受控对端、配置目录审计、隐私扫描及其有效回归，共 14 个 Python 文件。HTML 原型与专用导出器退役，配置目录及能力映射继续维护。
- CI 仅 `ci.yml` 与 `pr-policy.yml`；所有 PR 执行同一套静态与 Android/native 检查。`quality-gate` 和 `pr-policy` 名称保持，远端规则不随源码自动修改。

不建立 data/domain/presentation、逐动作 UseCase、通用 DI/导航/MVI 框架或 BaseViewModel。未来只有独立交付、明确复用或实测编译收益出现时，才评审提取模块。

## 保持的行为

TUN/PFD/JNI 借用、排空和释放顺序不变；核心、补丁语义及依赖版本不升级。draft/saved/applied、本地名称与核心身份、查看网络与运行网络继续分离；保存与改名不连接、不应用。缺测不替换为零，真实失败如实呈现。

## 历史材料与验证

P02 签名基线为 `872bea7e3898881e4787b0adb012726933cd8329`，提交树与原 504 文件快照一致。退役源码和原型从该基线取回至独立工作区，不恢复为当前构建输入。

`docs/verification/**`、`handoff.json`、`SHA256SUMS` 原路径、原字节保留。旧路径与命令按写入时点解释，历史结果不证明当前源码或新 APK 已通过。

结构变化后的 APK 使用 JVM、双 lint、宿主 Rust、Python、构建和双 ABI/16 KB 静态检查。2026-10-23 已取消原定模拟器、真机、release 冒烟及失败对照补验，见 [结构精简验收](../../development/setup.md#结构精简验收)。PR #7 已合并且工程 CI 通过；当前产物的设备效果未验证，原失败和历史结论保持原范围。

## 依据

- [Android 模块化指南](https://developer.android.com/topic/modularization)：按规模和实际收益决定模块化。
- [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)：单向数据流、明确数据来源和屏幕状态持有者。
- [界面层](https://developer.android.com/topic/architecture/ui-layer)：Screen 消费 UiState，状态持有者处理业务投影与事件。
- [ViewModel 作用域](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-apis)：使用正确的 owner 与生命周期。
- [Android 构建变体](https://developer.android.com/build/build-variants)：构建类型与实体 source set 是不同概念。
- [Gradle 增量构建](https://docs.gradle.org/current/userguide/incremental_build.html)：声明实际输入输出。
