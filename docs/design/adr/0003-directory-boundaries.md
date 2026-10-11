# ADR 0003：业务切片、工程工具与实验目录边界

状态：已采纳。当前 Gradle 工程只有 `:app` 模板；正式模块和业务切片在 P01—P05 按实际实现建立。

## 决策依据

目录应使共同变化的代码能够一起找到、修改和验证。Android 按业务能力及操作组织；工程工具按任务组织；实验按待验证问题组织。三者采用各自的职责边界。

[垂直切片架构](https://www.jimmybogard.com/vertical-slice-architecture/) 强调减少切片之间的耦合、聚合一次业务变化；[Android 架构建议](https://developer.android.com/topic/architecture/recommendations) 要求明确状态和数据来源。本项目同时保留服务与 JNI 的独立模块边界，具体目录和模块数量是项目取舍。

## Android 源码归属

源码保留 Android 标准 source set。下列路径相对于 `app/src/main/java/com/github/zzwtsy/chuanmen/`；测试在 `src/test`、`src/androidTest` 中镜像被测包。

| 位置 | 职责与建立时机 |
|---|---|
| `MainActivity.kt` | Activity 生命周期、系统窗口和组合入口；保留应用组件身份 |
| `application/` | 应用组合、导航与依赖装配；当前有 `ChuanmenApp` |
| `shared/designsystem/theme/` | 已有主题；其他共享视觉组件在实际复用后加入 |
| `feature/configuration/` | 配置能力；按操作形成 `edit`、`rename`、`apply`，共同使用本切片的 `storage`、`codec` |
| `feature/overview/` | 首页展示、60 秒趋势与展示状态转换 |
| `feature/devices/` | 列表、详情、复制与按需探测 |
| `feature/logs/` | 查看、过滤、导出、日志存储与轮转 |
| `feature/settings/` | 外观等应用偏好；配置仍归配置切片 |

`feature` 目录在实际业务实现时创建。操作的状态、校验、业务逻辑和测试归操作；持久化与转换实现归所属能力，不建立横跨全部业务的 `domain`、`data` 或 `utils` 桶。UI 通过仓库或操作入口访问数据源；复杂组合操作再提取用例。

未来 Gradle 边界为 `:app`、`:contracts`、`:vpn`、`:core-bridge`，Rust 放 `native/`。模块在出现对应实现和消费者时创建。

- `:contracts` 只保存实际跨模块使用的纯 Kotlin 接口和不可变模型，例如 `ProfileRepository`、`SessionController`、`CoreEngine` 及必要快照；切片私有草稿和校验留在切片。
- 业务切片只通过公开契约协作；共享代码须有至少两个实际消费者，不因名称相似就抽取。
- `application` 装配配置仓库、会话服务与 fake/real 引擎。VPN 通过注入的仓库接口访问保存版本，不反向依赖 `:app`。
- `:vpn` 依赖 `:contracts` 和 `:core-bridge`，拥有唯一真实会话、命令串行化、TUN 和资源生命周期；`:core-bridge` 依赖 `:contracts`，管理窄 JNI 适配。
- 保存和改名归配置能力；实际应用与重连归会话服务。只有服务回执才能更新 applied 状态。

同模块包目录提供组织边界；Kotlin `internal` 的可见范围仍是编译模块。[Kotlin 可见性](https://kotlinlang.org/docs/visibility-modifiers.html) 首条业务切片及跨模块契约建立时，P01 加入允许依赖与 import 边界检查。只有实际隔离、交付或编译收益需要时，再提取功能模块。

## 工程工具归属

`scripts/catalog` 保存配置审计与其测试；`scripts/prototype` 保存原型导出；`scripts/verification` 保存隐私、证据、发布、导出和清理工具。根 `scripts/validate_handoff.py` 是交付聚合入口，使用普通包导入。

后续通用 Android 工具归 `scripts/android`，P01 创建并实测后才登记为可用命令。按任务聚合入口、必要实现和测试，避免横向拆成全局 build/run/check/test 目录，也不建立万能脚本或自动插件注册器。各 CLI 保留帮助、明确输入、稳定退出码和适当超时；路径变更同步文档、skill 和 CI。

## 实验源码与复现闭包

独立问题使用 `experiments/<experiment_id>`。同一实验的相关子场景共用声明与退出安排，不把一个场景列表拆成多个工程。当前 `android-vpn-smoke` 的配置往返、JNI 解析和 VPN 运行分别放 `config-roundtrip`、`jni-parser`、`vpn-runtime`；其构建支持放实验内 `support`，完整复建由根 `rebuild.py` 编排。

实验的可维护源码、合成 fixture 和测试留在实验目录。唯一配置夹具供宿主和 APK 使用，打包副本只进入运行产物。共享支持代码须有实际消费者；只有多个独立实验确有复用后，才提取跨实验支持库。

`experiment.json` 的 `reproduction.inputs` 以角色映射到规范的仓库相对文件路径。仓库导出工具按实验身份读取所选树的声明，导出整个维护实验及额外输入；创建输出前验证输入完整性，输出收据记录当前源码摘要和未执行状态。被忽略的运行目录、缓存和产物不属于源码闭包。导出后 CLI、夹具生成与复建预览须能独立运行。

运行输出归 `build/experiments/<experiment_id>/<run_id>`，预算、采集、隐私、发布和清理沿用 [验证规范](../../development/verification.md)。冻结证据记录保留历史路径与产物身份，当前探针更新不继承旧真机验收。

## 维护与验证

新建目录必须对应真实职责与内容；局部 `AGENTS.md` 只在存在不同约束时添加。长期规则由本 ADR、架构和验证规范维护，入口文档只保留导航与真实命令。

目录变更验证 CLI 导入、默认根目录、声明输入、导出闭包和受影响构建。新增测试优先覆盖输入缺失、边界越界、暂存树隔离、夹具身份和实际打包行为。静态复核、独立复建和真机复跑分别报告；阶段冻结文件的字节与摘要保持不变。
