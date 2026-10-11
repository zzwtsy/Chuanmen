# P00 复跑入口

原结论、精确范围及未验证项见 [报告](../report.md)。复跑使用 [当前维护探针](../../../../experiments/android-vpn-smoke/README.md)，输出放被忽略的build/experiments；不要把当前源码当作所有历史APK的精确输入。

1. 按 [实验声明](../../../../experiments/android-vpn-smoke/experiment.json) 选择结论，确认输入、预算、停止条件和实际设备/对端授权。
2. 获取固定上游commit与工具链，核对Cargo.lock和六文件补丁before/after身份。宿主配置往返与无补丁JNI解析先执行。
3. 原生设备实验使用明确自有补丁及其构建报告。构建报告、实际APK及打包库摘要必须一致；设备结果绑定实际安装APK。
4. 受控对端由build-host-peer及controlled-host-peer运行，不创建主机TUN、不改系统路由。使用不冲突的实验网段，host外层地址纳入手机TUN测试路由，确保protect真实生效。
5. run-device明确选择单个原生模式；connected-cycles仅配合native-datapath。JNI解析不启动VPN。断网与性能采用各自两段采集入口，恢复必须属于同一run_id。
6. 对端请求nonce/摘要与设备回包对应，停止并回收结果，再按验证规范筛选、脱敏和发布。

常规完整性检查不执行上述设备步骤：

```bash
python3 scripts/verification/check-evidence.py --stage p00 --require-stage
python3 scripts/verification/check-privacy.py
python3 scripts/verification/export_reproduction.py --tree index --out /tmp/chuanmen-probes-new
```

导出目录必须不存在；导出只提供当前工作流，不恢复缺失历史版本，也不执行实验。独立复建工具使用明确actor和全新目录，原Arch JDK工具包等外部输入必须满足固定身份；缺失时停止，不改用未经验证的版本。

独立设备复现、迁移后的完整复建状态为not_run。输入缺口见 [输入身份](../inputs/reproduction-inputs.json)。自动CI只进行静态判据和结果重算。
