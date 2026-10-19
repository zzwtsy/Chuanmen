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

### 结构精简验收

结构变化后的程序须完成 JVM、debug/release lint、Python、宿主 Rust、三类 APK 和双 ABI/JNI/16 KB 静态检查，并对实际 APK 独立执行以下 11 个设备场景及 release 冒烟。构建、静态对齐和历史 APK 的通过结果均不能替代新产物的设备验收。

`--timeout` 是每条子命令预算，外层总预算用 `timeout --signal=INT --kill-after=15s` 限制；预算耗尽算未通过，并须完成资源收尾。例如 API24 的 lifecycle 使用：

```bash
timeout --signal=INT --kill-after=15s 300s python3 scripts/android/verify_device.py --offline --case lifecycle --serial EMULATOR_SERIAL --timeout 180
```

| 设备 | `--case` | CLI 超时/外层总预算（秒） | 必须确认 |
|---|---|---:|---|
| API24/x86_64 模拟器 | bridge | 120 / 240 | 三项正式 JNI/pipe 契约完整执行 |
| API24/x86_64 模拟器 | lifecycle | 180 / 300 | 拒绝、启动中取消、正常授权、真实 TUN、Activity 重建、停止 |
| API37/x86_64 模拟器 | bridge | 120 / 240 | 三项正式 JNI/pipe 契约完整执行 |
| API37/x86_64 模拟器 | lifecycle | 180 / 300 | 与 API24 相同的生命周期边界 |
| API37/x86_64 模拟器 | revoke | 180 / 300 | 系统撤销、资源排空、用户停止/授权状态不被伪造 |
| API36/arm64 真机 | bridge | 120 / 240 | 真机 JNI/pipe 与固定核心身份 |
| API36/arm64 真机 | datapath | 240 / 360 | TCP/UDP 两种外层，各自 TCP/UDP 业务回包，共四组，均有对端关联 |
| API36/arm64 真机 | dns | 240 / 360 | 系统 resolver 的本轮唯一域名查询与实际虚拟 DNS 回答，TCP/UDP 外层均覆盖 |
| API36/arm64 真机 | policy | 480 / 600 | 实际 DHCP、默认路由、失败应用和显式恢复，保存/名称/草稿保留 |
| API36/arm64 真机 | network | 480 / 600 | 真实切网恢复、物理 IPv6 可达而 VPN IPv6 被阻断、停止后不重连 |
| API36/arm64 真机 | resources | 960 / 1200 | 4 轮预热 + 50 轮正式资源与真实业务回包 |

用例和 instrumentation 身份以 [CASES](../../scripts/android/device_contracts.py)为准。模拟器 lifecycle 不带可通信对端，不能计为数据通路通过；预期用例缺失、初始化错误、跳过、只返回 0 或缺报告均失败。每个需要对端的 case 使用独立的请求身份、peer 和新输出目录，按前述命令核对 ready.json 后执行；network 还须提供已核验全局 IPv6 对端。

资源回归严格沿用 [resource_results](../../scripts/android/device_contracts.py)的判据：

- 54 个完整周期包含 4 次预热与 50 次正式周期、108 个唯一关联的 TCP/UDP 业务回包，两种外层交替；第 4 次预热后的 FD 和共享 runtime worker 为固定基线，不滚动重设。
- 正式周期 FD 不超过基线 +2；最初和最后 10 个正式 FD 样本各自排序取下标 5，后者不超过前者 +1。共享 runtime worker 基线至少 2，正式周期数量保持不变。
- 每轮停止后 Idle，拥有资源、保护失败及对端连接/路由满足归零判据；共享 runtime 可继续存活，Service 保留一个作业和一个生命周期订阅。实际收发与 socket protect 必须发生，对端收束不超过 45000 ms。
- 任一周期失败则整组失败并收尾；修正后用新运行重验完整 54 周期，不从失败位置续跑或删掉异常样本。

#### release 冒烟

在专用 API37 模拟器完成 debug 和基线对照后，使用独立测试密钥签名 release APK 的副本，保留原 unsigned APK 和正式签名配置。核对副本的签名、核心库字节和对齐，保持 release 非 debuggable；签名冲突仅在该专用模拟器清理测试应用。

从干净启动确认空输入及默认值、错误输入保留、保存不连接、拒绝授权如实显示、再次授权后真实核心 Running 和实际地址、显式断开及系统无活动 VPN。只在测试操作中输入公开合成参数，不写回应用初值。该冒烟验证入口、保存、授权、连接和断开；完整数据通路矩阵绑定 debug 与 androidTest，不能写成 release 完整网络回归。

#### 失败、对照与收尾

每个 case 首次执行一次。失败后先保留源码/APK 身份、失败点与输出并收尾；仅在前置条件或源码问题修复后，同一候选、同一环境最多补跑一次。仍失败则保留未通过状态，不扩大预算。输入身份变化、对端不受控、授权无法确认、资源未收束或影响其他网络应用时停止整组矩阵。

API24 DNS 和 API37 TCP 属于矩阵之外的附加对照：真实 P02 基线和候选使用各自匹配的 APK、工具和对端，在同一模拟器、相同网络与参数下，以 240 秒子命令 / 360 秒总预算比较；必要时按各自公开协议核对对端。基线通过而候选失败或明显恶化时，按回归停止验收；两边同类失败且条件可比时，均不计通过，归因未证实时保留未知及待查范围，不降低必测矩阵要求。基线构建/安装/工具受阻，或身份与环境不可比时，归因未完成，不能宣布验收闭合；两边通过也不倒改旧失败记录。既有附加失败不作为顺带升级或改写核心的理由。

正常或失败都显式断开应用并核对 Idle、会话与 PFD 释放；网络场景恢复原 Wi-Fi。请求 peer 停止，45 秒内核对 `peer_stopped`、`echo_threads_stopped`、`dns_socket_stopped` 均为 true 且进程退出成功。关闭未确认时不启动下一轮；结束时关闭本任务新建的模拟器与 peer。只处理本任务拥有的应用和进程，不重置个人真机网络或操作其他 VPN。原始输出留本机忽略目录，保留失败与补跑；证据整理遵循 [验证规范](verification.md)。

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
