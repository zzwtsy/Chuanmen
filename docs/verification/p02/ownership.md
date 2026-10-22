# P02 运行资源所有权

本表描述当前正式实现，阶段仍为 `in_progress`；退出结果见 [P02](../../plan/p02-vpn-core.md)。P00 的冻结所有权观察不作为本次正式 APK 的验收记录。

| 资源 | 持有者与借用边界 | 正常释放与未确认行为 |
|---|---|---|
| TUN `ParcelFileDescriptor` | `NativeVpnService` 建立，`VpnSessionCoordinator.Run` 持有；JNI 只传 fd 和 attachment 身份 | 安装回执确认实际 I/O 就绪；移除或停止确认借用结束后关闭 PFD。超时保留 Run 和 PFD，阻止再次连接 |
| 待安装请求和 TUN I/O | 固定核心的 Android TUN 宿主，带会话、操作和 attachment 身份 | 队列关闭拒绝新请求并取消未安装借用；installer 停止及读写任务 join 完成后确认排空；安装入队不等于已安装 |
| 外层 socket | 创建它的核心任务持有；protector 只借用 | Service 保护和物理网络绑定都成功后才能继续连接；撤销、旧代次和失效网络拒绝回调；临时复制 fd 只用于绑定，不关闭原 socket |
| JNI GlobalRef / protector | native 会话持有 callback 强引用，回调持有临时 Arc | 停止关闭回调入口并等待在途回调结束；PFD 关闭后执行独立 release，临时 Arc 未释放时报告未知，不能允许新会话 |
| 生命周期回执 | bridge 的有界 Channel；Service 的实际 Flow 消费者 | 完成回执不能丢弃；Service 作用域保留至停止与引用释放确认，慢 UI 只消费合并状态，不读取回执 Channel |
| 运行快照和流量窗口 | Service 的 StateFlow 与 60 秒窗口 | 核对会话、修订与 attachment。结果未知时历史标记；attachment 替换或计数回退切断速率基线，缺测不补零 |
| 核心诊断 | 进程级有界缓冲，仅静态 target / level / code | 不读取原始日志字段或包正文；最多 128 条，拥塞丢弃并计数，不能标成当前网络的专属日志 |
| 共享 runtime | 固定 FFI 上下文，原生宿主复用该 Handle | 不随单会话销毁。实际线程创建/退出钩子提供进程计数，资源回归采用预热后的固定基线，不按轮次上移 |
| 受控配置和草稿 | 应用配置仓库与 ViewModel，均为 P02 内存数据 | Service 只消费已保存修订；保存和本地改名不重连，恢复运行不回写 saved、名称或草稿。进程恢复归 P03 |

核心重建递增会话 generation，TUN 替换递增 attachment；整数 fd 只用于系统调用，不能作为归属身份。正常停止的顺序为拒绝入口、等待原生借用与回调结束、关闭 PFD、释放 JNI 引用，最后解除生命周期作用域。Service 的系统入口受 `BIND_VPN_SERVICE` 保护，控制 Binder 每次核对同 UID 和主线程。

当前采用 `START_NOT_STICKY`、单进程、单活动 VPN，显式不支持 Always-on。进程终止属于资源由系统回收的路径，不等于应用取得了停止确认，也不自动恢复运行意图。
