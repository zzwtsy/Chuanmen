# 最小 Android VPN 可行性探针

本目录维护宿主配置往返、上游 JNI 解析及六文件自有补丁的真实原生边界/数据实验。实验已关闭，保留探针用于后续工程对照；[声明](experiment.json) 定义范围、判据、预算和退出安排。[P00 报告](../../docs/verification/p00/report.md) 记录历史验收，当前源码不冒充所有历史 APK 的精确输入。

独立纯平台探针、模拟器包装器、单次续跑和许可证补取工具已退役。上游无补丁解析与实验补丁网络明确分开；实验补丁 production_approved=false。

## 输入与输出

从仓库根执行，使用 Python 3.11+。需要固定 EasyTier commit、Rust 1.99.0、JDK 21.0.12.1+1、SDK Platform android-37.0、Build Tools 36.0.0、NDK 30.0.16248370。实验 minSdk=24、targetSdk=37；历史设备是 API36，不构成 Android7、其他 OEM 或16KB支持承诺。

SDK_ROOT 和 CORE_CHECKOUT 由本机环境配置。输出使用未占用的 build/experiments/android-vpn-smoke/<run_id>，运行收据和缓存不入库。新建构建目录必须不存在或为空，设备结果文件必须不存在。临时实验签名会改变 APK 摘要，禁止使用生产签名。

```bash
python3 experiments/android-vpn-smoke/config-roundtrip/build.py --core-checkout "$CORE_CHECKOUT" --rust-toolchain 1.99.0 --out build/experiments/android-vpn-smoke/config-01
python3 experiments/android-vpn-smoke/config-roundtrip/run.py --build-report build/experiments/android-vpn-smoke/config-01/build-report.json --fixture experiments/android-vpn-smoke/config-roundtrip/fixtures/config-cases.json --output build/experiments/android-vpn-smoke/config-01/result.json
python3 experiments/android-vpn-smoke/support/build_native.py --checkout "$CORE_CHECKOUT" --sdk "$SDK_ROOT" --rust-toolchain 1.99.0 --abi arm64-v8a --abi x86_64 --bindgen-api-target --out build/experiments/android-vpn-smoke/native-01
python3 experiments/android-vpn-smoke/support/build_probe.py --sdk "$SDK_ROOT" --native-build-report build/experiments/android-vpn-smoke/native-01/native-build-report.json --out build/experiments/android-vpn-smoke/parser-01
```

## 目录归属

`config-roundtrip` 负责宿主往返与唯一配置夹具；`jni-parser` 负责无补丁 JNI 解析；`vpn-runtime` 聚合补丁、Android 生命周期和受控对端；`support` 只保存本实验实际共享的构建与检查能力。子场景共用根声明；JNI 包名和符号保持原契约。

`experiment.json` 的 `reproduction.inputs` 定位额外映射、补丁基准和离线测试使用的导出工具依赖。完整复建、夹具生成与离线测试从声明读取位置；`support/build_probe.py` 将共享夹具写入输出 APK 的 `assets/config-cases.json`，源码树不保存重复副本。

## 离线复核与导出

```bash
python3 experiments/android-vpn-smoke/test_workflow.py
python3 experiments/android-vpn-smoke/rebuild.py --dry-run --root build/experiments/android-vpn-smoke/rebuild-preview
python3 scripts/verification/export_reproduction.py --experiment android-vpn-smoke --tree index --out /tmp/chuanmen-probe-export
```

预览要求源码和声明输入完整、输出目录未被占用；不会创建目录、下载工具链或执行构建。导出输出必须是仓库外的新目录，导出整个维护实验及声明输入，收据记录文件摘要并明确未执行。实际复建另须 `--jdk-package`、`--jdk-package-sha256`、`--actor`，固定工具链和环境约束沿用原契约。

离线测试覆盖夹具复算、合成 ZIP assets、补丁源码身份和导出后入口，不代表重新构建或安装过 APK。fixture 生成使用 `config-roundtrip/make_cases.py --out`，输出为仓库外的新文件，不能覆盖已收录样本。

## 保留入口

| 入口 | 职责 |
|---|---|
| config-roundtrip/build.py / run.py | 88案例的真实核心 getter、dump、还原；不启动VPN |
| config-roundtrip/make_cases.py | 从冻结映射生成 fixture；常规复跑使用已收录 fixture |
| support/build_native.py / build_probe.py | 无补丁JNI构建与解析APK；--boundary构建明确补丁的边界APK |
| vpn-runtime/prepare_patch.py | 固定commit六文件补丁、before/after身份及锁文件检查 |
| jni-parser/run.py | 已授权设备JNI解析；--mapping-cases核对88案例 |
| vpn-runtime/run.py | 必须显式选择一个原生模式：boundary、datapath、denied、revoke、address、advanced |
| vpn-runtime/offline.py | 跨ADB失联采集；--resume-ready回收同一运行 |
| vpn-runtime/performance.py | --start-only/--collect两段回收同一run_id，保留实际灭屏采样 |
| vpn-runtime/host_peer/build.py / run.py | 不建主机TUN、不改系统路由的受控对端；依赖benchmark_endpoints |
| support/inspect_apk.py / rebuild.py | ZIP/native/DEX核验与固定输入完整复建；换主机不自动形成独立验收 |

运行需要明确的设备授权与受控拓扑。安装、卸载、VPN系统授权/撤销和物理断网均属于具体设备操作，授权与运行条件须在执行前确认。重新签名安装可能需先卸载旧实验包。

## 发布与关闭

本地运行收据可能包含后续工具需要的绝对位置，不能直接提交。按 [验证规范](../../docs/development/verification.md) 编写只包含必要材料的 bundle.json，预览、脱敏校验后发布；不生成整目录归档。

历史冻结输入留在 docs/verification/p00/inputs；当前探针可以维护，修改不能改变既有历史验收。静态复核、当前工作流构建与设备重跑分别登记，不把 --help、静态检查或宿主通过写成真机通过。

运行结束在输出目录写 lifecycle.json，字段为 experiment_id、run_id、state=closed。cleanup-local.py 默认预览过期候选；raw输出默认7天，选定证据随结论保留。
