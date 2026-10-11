# ADR 0001：原生宿主边界

状态：实验边界已验证；正式桥与生产补丁仍待 P02 工程化审查。

2026-10-11 的 [原生边界实验](../../verification/p00/reports/p00-04-native-boundary.md) 使用六文件自有补丁，在小米真机完成真实 TCP/UDP protector 回执、false/异常阻断及 20 轮已安装 TUN 的借用终止。release 合成 panic 实际导致独立实验进程 native crash。结论仅属于该补丁、ABI 和设备；上游无补丁 JNI 没有因此获得新增契约。

后续 [P00-05 实测](../../verification/p00/reports/p00-05-datapath.md) 使用六文件 v3 补丁验证真实双向数据及系统错误。v3 追加 `poll_write_vectored` 的实际成功写入计数；旧计数遗漏导致的失败报告保留，没有用业务回包反填 TUN 计数。生产接口、会话代次和替换竞态仍待工程化。

## 已核实的上游接口

实验候选固定为 `7592951b808c4839cb9d77cc4bc642f124915951`，对照版本为 `8428a89d2dabc94c97d370ec607c6ca142473626`。完整定位、源码哈希和版本差异见 [P00-04 证据](../../verification/p00/reports/p00-04-native-boundary.md)。候选 manifest 的 `2.7.0` 是源码版本，不能当成已发布版本。

候选 JNI 有 `parseConfig`、`runNetworkInstance`、`setTunFd`、`retainNetworkInstance`、`collectNetworkInfos`、`getLastError`，并新增 `deleteNetworkInstance`、`listInstances`、`callJsonRpc` 等入口。它们不是具有稳定 Android 生命周期契约的官方 SDK。Rust 的 `NativeSocketProtector` 与 `set_native_socket_protector` 存在，但候选 JNI 没有注册 protector 的入口。`LoggerRpc` 的配置读写不构成日志订阅。

`setTunFd` 经 manager / runtime host 到 mobile TUN 队列；成功仅代表入队。`close_fd_on_drop(false)` 表示核心不负责关闭借用的 fd，不能将返回值当作 NIC 已安装、地址已应用或连接已成功。

## 项目自有契约候选

以下名称描述 Chuanmen 的正式接口候选，尚无对应生产实现或上游 SDK API；实验实现另用 `P00NativeBridge` 命名：

- `parseConfiguration`：返回规范化配置或结构化错误；保存不启动核心。
- `startSession(sessionId, configuration, socketPolicy)`：服务创建唯一会话；注册 protector 成功后才允许底层传输创建和连接。
- `attachTunAndAwaitInstalled(sessionId, fd)`：仅借用；必须通过新增确认机制证明安装完成。
- `stopSessionAndAwaitBorrowReleased(sessionId)`：完成取消、队列清理和全部 fd 使用者退出后才返回；当前上游返回值不足以证明这一契约。
- `observeSession(sessionId)`：地址、设备与统计需有来源、采样时间和会话编号；缺失数据返回未知。

不将这些候选命名写入 JNI 调用点，也不提供返回模拟成功的空实现。

## 所有权与关闭次序

| 资源 | 所有者 | 约束 |
|---|---|---|
| Android `ParcelFileDescriptor` | `VpnService` | `establish` 后持有；核心借用期间不得关闭 |
| 核心收到的原始 TUN fd | 服务仍拥有，核心借用 | 不 `detachFd`，不假定 `setTunFd` 转移所有权 |
| TUN 队列中的待安装 fd | 借用来源必须可追溯 | 取消时清空并确认不再读取，防止复用 fd 被误访问 |
| 底层 socket | 创建它的核心任务 | `protect` 不接管关闭；错误、取消、停止均由任务释放 |
| protector JNI 全局引用 | 项目桥 | 停止所有回调后释放，不从 native 线程跨线程复用 `JNIEnv` |
| 业务流量计量 | 现有 TUN 消费路径中的计数 | 不添加第二个 TUN reader |

停止顺序候选：禁止新任务 → 请求取消并等待核心任务与 NIC 排空 → 确认借用终止 → 关闭 PFD → 释放回调 → 移除通知。若确认机制不可用，不能在同进程冒险关闭仍被使用的 fd；必须阻塞 P00-04，评估受控进程终止方案及其额外成本。

## 最小适配缺口与实验停止条件

1. 给项目 JNI 桥暴露真实 protector 注册与错误传播；确认每条底层 socket 路径调用，失败即停止连接，不能静默继续。升级冲突点在 `socket_protector.rs`、socket 创建路径及 JNI 回调生命周期。
2. 为 mobile TUN 的异步安装、替换与取消增加带 `sessionId` 的确认；仅队列成功不能驱动运行状态。冲突点在 mobile TUN 队列和 NIC 任务所有权。
3. 实验补丁为 TUN `JoinSet` 增加显式取消与等待退出，停止后观察 `released`，平台再关闭 PFD。已安装 fd 的 20 轮通过；任意队列替换、迟到回调和正式会话 epoch 仍需 P02 回归。补丁只应用到独立临时 checkout，完整文件哈希、构建特性和 `.so` 见实验报告，原始上游 checkout 不变。
4. `panic=abort` 的 release 配置不能通过 `catch_unwind` 获得可靠恢复；panic 需在独立的可丢弃实验进程中观察。是否将生产 VPN 放独立进程，留待测量后决定。

Android 平台探针只能证明平台 `establish` / `protect` 的实际返回与本探针的资源释放；没有核心使用者，不能验证上述借用终止或 native 回环防护。
