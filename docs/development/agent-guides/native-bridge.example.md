# Native 桥接局部 AGENTS.md 样板

**状态：设计样板，不是活动指令。** P02 确认真实 JNI/TUN 集成后，再分别提炼到 `core-bridge/AGENTS.md` 或 `native/AGENTS.md`；不要整份同时复制到两处。边界归属以实际工程为准。

## 适用责任

桥接模块把固定版本的 EasyTier 能力转换为领域接口；native 目录记录源码 revision、构建与补丁。UI 不直接访问 native 句柄。局部规则重点是生命周期、版本与诊断证据，避免重复根文件的配置/UI 不变量。

## 必须能定位的契约

落地后局部文件链接这些真实资料：

1. 核心 commit/tag 与 ABI、Rust/NDK 构建记录。
2. JNI 方法/回调签名、错误映射和 `CoreEngine` 适配入口。
3. TUN 文件描述符的所有权转移、取消和关闭时序。
4. `sessionId` / 核心实例 ID 的对应与晚到事件规则。
5. 相关 host 测试、设备验证及故障夹具入口。

若当前核心没有某个 generic RPC 或统计字段，应明确缺失并实现经过验证的窄接口；不得根据另一版本的文档猜一个可调用方法。

## 边界检查

| 变更类型 | 必须解释 |
|---|---|
| 新增 native 调用 | 线程、阻塞性、输入范围、错误返回、取消行为 |
| 传递 TUN/句柄 | 谁创建、何时接管、失败时谁关闭、是否允许重复释放 |
| 新增回调 | 生命周期、线程、背压/缓冲、停止后的晚到消息处理 |
| 导入配置/JSON DTO | 版本、缺省、未知字段、整数范围、脱敏与迁移 |
| 统计 | 数据源、单位、重置边界、零/未知区分、会话归属 |
| 升级核心 | 新旧版本差异、补丁、导出符号/契约、能力集与回归 |

JNI 线程与引用管理有平台约束，不能把对象/环境句柄当成任意线程可复用资源；降低边界复杂度有助于减少生命周期问题。[Android JNI tips](https://developer.android.com/ndk/guides/jni-tips)。

## VPN 联动

外层 socket 的保护/底层网络绑定、TUN 建立和网络切换需与 `:vpn` 协调。修改 JNI 不应悄悄绕过系统 VPN 授权。测试用确定的设备和隔离拓扑，不以“native start 返回成功”代替 TUN 数据路径和 peer 连通验证。[Android VPN 文档](https://developer.android.com/develop/connectivity/vpn)。

## 验证与提交内容

使用落地后存在的构建/测试入口。最少报告核心 revision、工具链、ABI、签名或方法契约变更、相关测试和未验证环境。日志与故障夹具使用合成/脱敏数据。生成文件由生成过程更新，补丁应有去除条件；不把构建产物当成唯一源代码。

在一次真实升级/排障流程验证前，不在此宣布可用的自动升级或诊断 skill。候选流程见 [核心升级设计](../skill-drafts/easytier-core-upgrade.md)、[VPN 诊断设计](../skill-drafts/easytier-vpn-diagnose.md)。可重复流程先经过真实任务，再提炼为 skill。[技能创建最佳实践](https://agentskills.io/skill-creation/best-practices)。
