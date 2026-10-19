# 开发环境与验证命令

当前仅有 `:app`，无 product flavor。正式应用与测试的目录见 [ADR 0005](../design/adr/0005-single-app.md)，阶段状态见 [路线图](../plan/ROADMAP.md)。所有命令从仓库根执行。

## 环境

版本以 [版本目录](../../gradle/libs.versions.toml)、[Wrapper](../../gradle/wrapper/gradle-wrapper.properties)、[Daemon JDK](../../gradle/gradle-daemon-jvm.properties)、[app 构建](../../app/build.gradle.kts)和 [native 输入](../../app/native/inputs.json)为准。使用固定 JDK 25、SDK 37、构建工具 36.0.0、NDK 30.0.16248370、Rust 1.99.0 和 cargo-ndk 4.1.2；不以本机最新版本替代。

SDK 优先读取 `local.properties` 的有效 `sdk.dir`，再用 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，两项环境变量同时存在时须指向同一 SDK。Android Studio SDK Manager 或 CI 中固定的官方 Android CLI 安装所需包。首次 Gradle 同步需要网络，缓存齐备后可加 `--offline`。

Python 工具仅使用 Python 3.10+ 标准库。uv 在隔离环境运行固定 Ruff 0.17.0，CI 固定 uv 0.13.0；宿主版本不覆盖仓库声明。

## JVM、lint 与 Android 构建

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

这条路径不需要 Rust 构建或真实网络。JVM 测试包括模型、配置、导航、正式会话协调器、Service、桥接及 Compose 语义断言。Robolectric 使用固定 API 37 runtime，由 Gradle 解析后以离线模式读取。报告在 `app/build/reports/tests/testDebugUnitTest/`，lint 报告在 `app/build/reports/`。JVM UI 通过不代表设备、系统栏或 TalkBack 验收。

首次 native 准备与构建：将 `SDK_ROOT` 替换为实际 SDK 路径。

```bash
rustup toolchain install 1.99.0 --profile minimal --target aarch64-linux-android --target x86_64-linux-android
cargo +1.99.0 install cargo-ndk --version 4.1.2 --locked
python3 scripts/native/build_native.py build --sdk SDK_ROOT --allow-download
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Linux 宿主还需 Protobuf 编译器、标准 `.proto` 文件及 OpenSSL 开发依赖，以及 pkg-config、clang、cmake。Debian/Ubuntu 对应软件包为 `protobuf-compiler`、`libprotobuf-dev`、`libssl-dev`、`pkg-config`、`clang`、`cmake`。`libprotobuf-dev` 提供 `google/protobuf/*.proto`，使用 `--no-install-recommends` 时也须显式安装。

`--allow-download` 仅用于首次获取固定源码与依赖；日常 Gradle native 任务使用离线 Cargo。源码和输出分别为 `app/native` 与 `app/build/native`，Gradle 根据实际输入输出决定是否重建。修改 Kotlin 测试不会触发 Rust 构建；Rust 源码、补丁或锁文件变化会触发。构建前后动态指纹用于防止构建期间输入变化，无需手工登记自有源码摘要。复用上游缓存时还检查当前补丁与固定后像一致；相同 Android 适配内容不重写，避免准备对端使 Cargo 无谓重编译。

产物分别为 `app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release-unsigned.apk`、`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。release 启用 AGP 默认全包 R8 优化，JNI 入口及回调由 keepRules 保持契约；不限定为只优化第三方包。release 不带签名密钥；debug/release 共用正式配置入口，只保留内存配置，退出进程即消失。

```bash
python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/debug/app-debug.apk
python3 scripts/native/check_apk.py --sdk SDK_ROOT app/build/outputs/apk/release/app-release-unsigned.apk
cargo +1.99.0 test --manifest-path app/native/Cargo.toml --locked --offline --target-dir app/build/native/target
cargo +1.99.0 test --manifest-path scripts/android/native-peer/Cargo.toml --locked --offline --target-dir app/build/device-tests/peer-target
```

APK 检查核对双 ABI、实际库字节、每个 ELF LOAD 段和 ZIP 的 16 KB 对齐；这不代表 16 KB runtime 已实测。正式来源与所有权说明见 [原生桥](../../app/native/README.md)。

## 配置入口与测试数据

`main/.../features/configuration/ConfigurationEntry.kt` 提供唯一真实输入入口，debug/release 共用业务源码。首次启动不提供示例参数；自动分配 IPv4 默认开启，互联网路由默认关闭。保存只初始化内存仓库，不申请 VPN 授权或启动核心。配置不持久化，进程退出即消失。

应用侧无 Preview、演示状态或样例数据。测试记录器和合成输入仅存在 `test`/`androidTest`；入口测试位于 `test/.../features/configuration/ConfigurationEntryUiTest.kt`。仅保留 main/test/androidTest 三类实体源码目录。配置、总览、设备、日志、设置在 `main/.../features/<功能>` 聚合，JVM 测试按功能与 `lib` 镜像归属，真实设备用例统一在 `androidTest/.../lib/vpn`。

## 真实设备回归

```bash
python3 scripts/android/verify_device.py --help
python3 scripts/android/verify_device.py --offline --case bridge --serial DEVICE_SERIAL
python3 scripts/android/verify_device.py --offline --case lifecycle --serial EMULATOR_SERIAL --timeout 180
python3 scripts/android/verify_device.py --offline --case revoke --serial EMULATOR_SERIAL --timeout 180
```

设备须已授权且 API 24+、arm64-v8a 或 x86_64；多设备时显式选择 `--serial`，未选择时要求唯一在线设备。Activity 场景需亮屏、解锁和正常系统授权。`revoke` 的系统 Forget 仅操作本任务测试模拟器；不得用于个人真机。`lifecycle` 的首次拒绝/授权场景需新建或尚未授权的测试模拟器。

有业务对端的回归先构建并启动固定用户态 peer：

```bash
python3 scripts/android/peer.py build --allow-download
python3 scripts/android/peer.py check
python3 scripts/android/peer.py run --bind-ip PEER_LAN_IPV4 \
    --run-id p02-UNIQUE_RUN --out app/build/device-tests/peer/UNIQUE_RUN --duration 1200
python3 scripts/android/verify_device.py --offline --case datapath \
    --peer-host PEER_LAN_IPV4 --peer-run-id p02-UNIQUE_RUN \
    --peer-report app/build/device-tests/peer/UNIQUE_RUN/ready.json
```

peer 不创建宿主 TUN 或修改系统路由。虚拟 DNS 为 53，私有 LAN 外层为 31270，状态入口为 31274；用户态 TCP/UDP 回显为 31271/31272，记录在 `echo.jsonl`。network 另需在 peer 上提供已核验全局 `--ipv6-address`，物理 IPv6 回显为 31275/31276，记录在 `physical-echo.jsonl`。运行结束在 peer 输出目录创建 `stop.request`，核对 report.json 中核心、线程与 DNS socket 均已停止。

`--case` 范围：bridge 的三项 JNI/pipe 契约；lifecycle 的授权/取消/停止；revoke 的系统撤销；datapath 的双外层双协议回包；resources 的预热及 50 轮资源回归（建议 `--timeout 960`）；dns 的系统 resolver 实际回答；policy 的 DHCP/默认路由/显式恢复；network 的切网和 IPv6 阻断。network 会短暂切换 Wi-Fi，只在已授权的测试环境执行。

八个 instrumentation 执行类统一使用前缀 `com.github.zzwtsy.chuanmen.lib.vpn.`：

| case | 完整类名 |
|---|---|
| bridge | `com.github.zzwtsy.chuanmen.lib.vpn.NativeBridgeContractTest` |
| lifecycle | `com.github.zzwtsy.chuanmen.lib.vpn.LiveLifecycleTest` |
| revoke | `com.github.zzwtsy.chuanmen.lib.vpn.LiveSystemRevokeTest` |
| datapath | `com.github.zzwtsy.chuanmen.lib.vpn.LiveDatapathTest` |
| resources | `com.github.zzwtsy.chuanmen.lib.vpn.LiveResourceRegressionTest` |
| dns | `com.github.zzwtsy.chuanmen.lib.vpn.LiveDnsTest` |
| policy | `com.github.zzwtsy.chuanmen.lib.vpn.LivePolicyRecoveryTest` |
| network | `com.github.zzwtsy.chuanmen.lib.vpn.LiveNetworkPolicyTest` |

每次输出到新的 `app/build/device-tests/<case>/run-*`，核对实际安装 APK 摘要、完整 instrumentation 结果、应用报告与 peer nonce/样本关联。已安装 APK 经应用 UID 读取原字节并在宿主计算 SHA-256，不要求设备提供 `sha256sum`。旧成功、缺报、初始化失败、跳过或仅退出码为 0 均不能通过。输出可能含本机信息，只留忽略目录。

退出码为 0 成功、2 参数错误、3 前置缺失、4 任务失败、5 设备阻塞、6 超时、130/143 中断。每步默认超时 600 秒；POSIX 仅清理本次进程组，TERM 最多 5 秒、KILL 再最多 5 秒。CLI 中断不证明设备上的 Service 已停止，仍需核对资源收束。每轮 result.json 原子写入，含步骤、退出码与清理状态。

## Python 与常规检查

```bash
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check scripts
uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --check scripts
python3 scripts/check_privacy.py
python3 scripts/catalog/audit_config_catalog.py
python3 scripts/test_check_privacy.py
python3 -m unittest discover -s scripts/catalog -p 'test_*.py'
python3 -m unittest discover -s scripts/native -p 'test_*.py'
python3 -m unittest discover -s scripts/android -p 'test_*.py'
```

交付前可运行 `python3 scripts/check_privacy.py --tree index`，只扫描暂存字节。扫描报告只包含位置和规则，不输出敏感值。规则例外应局部说明，不能全局关闭告警。

HTML 原型及专用导出器已退役；需要核对历史设计时，从 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329` 的 `prototype/` 和 `scripts/prototype/` 取回至独立目录，不恢复为日常构建步骤。

## CI 与历史材料

[CI](../../.github/workflows/ci.yml) 对全部 PR 执行 Python/目录审计、JVM、lint、native 测试、debug/release/设备测试 APK 和双 ABI 对齐检查；[PR policy](../../.github/workflows/pr-policy.yml) 从事件 JSON 校验标题和 main 目标。`quality-gate` 只接受所有依赖任务成功，失败、取消与跳过均拒绝。工作流修改用固定 actionlint 1.7.12 校验，远端执行结果另行报告。

P00/P02 旧实验、模块边界扫描、handoff 聚合与证据发布工具已退役。冻结的 `docs/verification/**`、handoff.json、SHA256SUMS 保持原样，其旧路径按历史时点解释；当前构建和回归不运行旧工具，也不刷新运输清单。历史复现定位见 [验证规范](verification.md)。
