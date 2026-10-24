# 正式原生桥

本目录构建串门（Chuanmen）唯一的 `libchuanmen_core.so`。固定输入、上游文件前后摘要与补丁身份集中在 [inputs.json](inputs.json)，依赖锁定在 [Cargo.lock](Cargo.lock)。P00 的候选批准状态保留历史含义，不参与正式构建放行。

当前来源为 EasyTier commit `7592951b808c4839cb9d77cc4bc642f124915951`，Rust `1.99.0`、cargo-ndk `4.1.2`、NDK `30.0.16248370`，Android API 24，ABI 为 `arm64-v8a` 和 `x86_64`。核心、原生宿主和 FFI 由同一固定源码构建；FFI 的共享 runtime 和管理器供桥复用，不创建第二个核心上下文。

## 输入与构建

从仓库根目录执行，`SDK_ROOT` 替换为已安装的 Android SDK：

```bash
python3 scripts/native/build_native.py check-inputs
python3 scripts/native/build_native.py prepare --allow-download
python3 scripts/native/build_native.py build --sdk SDK_ROOT
python3 scripts/native/build_native.py verify-artifacts
cargo +1.99.0 test --manifest-path app/native/Cargo.toml --target-dir app/build/native/target --locked
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/debug/app-debug.apk
```

`prepare` 将固定上游源码放入忽略的 `app/build/native/upstream/`，核对原文件后应用 [session-host.diff](patches/session-host.diff)，再复制两个 [host overlay](host/)。产物和构建报告放入 `app/build/native/`。没有 `--allow-download` 时不主动获取缺失上游。

构建核对完整输入、编译器 commit、依赖锁文件及目标工具链；验证器检查双 ABI、JNI 符号、ELF 对齐和产物摘要。APK 检查再核对实际打包库、ABI 集合、ZIP 存储方式及 16 KB 对齐。构建或静态对齐通过不等于设备上的 16 KB runtime 通过。

自有 Rust、overlay、diff 与构建脚本由动态指纹和 Gradle 输入追踪，不手工登记文件摘要；上游原文件与补丁身份仍由 inputs.json 固定。修改后重新构建。旧报告不能放行新输入。设备结果必须绑定实际安装的 APK 和测试 APK，不从上一个版本继承。

## 配置转换

P03 增加 `config_parse`、`config_patch` 和 `config_runtime`，能力标识 `config-codec-v1`。这些动作不创建 runtime 会话或 TUN；错误仅带 key、UTF-8 位置和代码。原始 TOML 保留未知项，未知或未接入参数可以保存但不能运行；数值通过带类型的十进制文本投影跨 JNI。运行输出独立覆盖 listeners、no_tun、enable_ipv6 与稳定实例身份，原件不变，预览仅列覆盖 key。

## 可观测性与宿主补丁

正式补丁身份为 `p04-observability-host-v2`，核心 commit 和工具链保持不变。保留 P03 的 `ConfigLoader::get_ipv4` 适配：裸地址继续解释为 /24，显式 /0–32 保留原前缀。P04 将 TUN 成功/失败的计量判断集中到 `TunAttachment::read_result / write_result`，普通与向量写入共用入口，失败或 Pending 不计量；原文件及后像摘要集中在 inputs.json。设备观测保留 `observability-v1` 并增加 `observability-v2` capability：活动邻居按核心网络身份分类，仅角色跨 JNI；RTT 的 `latency_valid` 来自完整样本发布，初始化零不作测量。两份 PeerConnStats protobuf 追加 optional 字段 6，经核心与 API 转换贯通；旧数据缺少标志时不推测 RTT。u64 计数继续以十进制字符串穿过 JNI。结构化事件订阅与通用 tracing 共用有界 sink，只允许静态模块/code 和事件白名单数字。宿主回归不构成设备网络效果结论。

## 生命周期边界

JNI 接口使用协议版本、会话 generation、操作序号与 attachment 身份。核心启动与 TUN 实际安装分别确认，Service 完成平台参数准备后才发布 Running。安装入队没有完成回执；整数 fd 可以复用，不能用于会话归属判断。

Kotlin 持有 PFD，native 只借用。停止先拒绝新命令与保护回调，排空安装队列、I/O 和会话任务，等待在途回调，再返回停止确认。Kotlin 随后关闭 PFD，调用独立 `release` 释放 JNI 强引用。任何一步未知都保留对应资源并阻止新会话。详细归属见 [所有权表](../../docs/verification/p02/ownership.md)。

protector 必须在核心启动前注册。外层 socket 只有完成 `VpnService.protect`、物理网络绑定及回调归属复核才能继续连接；旧代次和撤销回调拒绝放行。共享 runtime 线程数量来自实际线程生命周期钩子，不能用配置线程数替代资源测量。

诊断只保留有界的静态 target、level 和 code，不读取日志消息或字段，不回显配置和密码。快照的累计计数使用精确整数；流量窗口由 Service 按 attachment 计算，缺测不补零。

`diagnostics_debug` 显式调整安全 sink 的 Debug 采集，默认关闭；页面过滤不调用此动作。诊断 drain 带捕获时刻供 Kotlin 校正事件时间。`probe_nonblocking` 仅借用 Kotlin 临时 dup 的 ICMP socket fd，同步设置 `O_NONBLOCK` 后返回，不创建 runtime、不取得关闭所有权、不改变 TUN 或路由，供最低 API 24 的 Android 适配器使用。

## 验证范围

宿主回归验证队列取消、attachment 隔离、回调排空、诊断边界和实际线程计数；设备 JNI 用例使用 pipe 验证借用关闭顺序。它们不证明 VPN 业务连通。正式 Service 的业务回包、平台生命周期和 50 轮资源回归经设备入口分别执行，安排见 [开发环境](../../docs/development/setup.md)。

当前阶段由路线图维护，P03–P07 只执行工程验证，本轮不执行设备补验；当前重构 APK 不继承历史设备结论。范围见 [路线图](../../docs/plan/ROADMAP.md) 和 P02 报告。

## 配置 codec

`config_parse`、`config_patch`、`config_runtime` 不创建会话。配置回执声明 `codec_version=2`，能力标记为 `config-codec-v2`，Kotlin/Rust 同批更新；既有会话协议仍为 1。`peer_edits` 是补丁命令，不是核心 TOML 字段：按结果顺序传入 `keep(source_index)`、`replace_uri(source_index, uri)`、`insert(uri)`，每个旧索引最多引用一次，未引用的节点删除。原节点的其他成员完整保留，旧的整表 `peer` 补丁拒绝执行。

源语义保留未知、缺省和空值；运行语义使用固定核心 getter 的有效值，托管覆盖不参与比较。未接入字段保持不可运行。宿主 JNI 回归使用 `./gradlew :app:testHostNativeCodec --offline`；普通 JVM/lint 不依赖此任务。
