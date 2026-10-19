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
cargo +1.99.0 test --manifest-path native/Cargo.toml --locked
./gradlew :app:assembleLiveDebug :app:assembleLiveDebugAndroidTest
python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/live/debug/app-live-debug.apk
```

`prepare` 将固定上游源码放入忽略的 `build/native/upstream/`，核对原文件后应用 [session-host.diff](patches/session-host.diff)，再复制两个 [host overlay](host/)。产物和构建报告放入 `build/native/`。没有 `--allow-download` 时不主动获取缺失上游。

构建核对完整输入、编译器 commit、依赖锁文件及目标工具链；验证器检查双 ABI、JNI 符号、ELF 对齐和产物摘要。APK 检查再核对实际打包库、ABI 集合、ZIP 存储方式及 16 KB 对齐。构建或静态对齐通过不等于设备上的 16 KB runtime 通过。

修改 Rust、overlay、diff 或构建入口时，先同步 `inputs.json` 的准确文件集合与摘要，再重新构建。旧报告不能放行新输入。设备结果必须绑定实际安装的 APK 和测试 APK，不从上一个版本继承。

## 生命周期边界

JNI 接口使用协议版本、会话 generation、操作序号与 attachment 身份。核心启动与 TUN 实际安装分别确认，Service 完成平台参数准备后才发布 Running。安装入队没有完成回执；整数 fd 可以复用，不能用于会话归属判断。

Kotlin 持有 PFD，native 只借用。停止先拒绝新命令与保护回调，排空安装队列、I/O 和会话任务，等待在途回调，再返回停止确认。Kotlin 随后关闭 PFD，调用独立 `release` 释放 JNI 强引用。任何一步未知都保留对应资源并阻止新会话。详细归属见 [所有权表](../docs/verification/p02/ownership.md)。

protector 必须在核心启动前注册。外层 socket 只有完成 `VpnService.protect`、物理网络绑定及回调归属复核才能继续连接；旧代次和撤销回调拒绝放行。共享 runtime 线程数量来自实际线程生命周期钩子，不能用配置线程数替代资源测量。

诊断只保留有界的静态 target、level 和 code，不读取日志消息或字段，不回显配置和密码。快照的累计计数使用精确整数；流量窗口由 Service 按 attachment 计算，缺测不补零。

## 验证范围

宿主回归验证队列取消、attachment 隔离、回调排空、诊断边界和实际线程计数；设备 JNI 用例使用 pipe 验证借用关闭顺序。它们不证明 VPN 业务连通。正式 Service 的业务回包、平台生命周期和 50 轮资源回归经显式 live 入口分别执行，安排见 [设备判据](../experiments/p02-vpn-core/README.md)。

当前仍处于 P02 实施阶段，正式补丁的退出评审和全部设备矩阵尚未完成；liveDebug 的受控测试产物不代表可发布版本。阶段状态集中在 [路线图](../docs/plan/ROADMAP.md)。
