# P00-02 固定来源与独立复建

固定commit7592951b808c4839cb9d77cc4bc642f124915951。另一AI在第二主机空缓存完成配置、无补丁双ABI JNI、解析APK、v3 arm64库和边界APK，实际退出0。旧执行器actor模板不是执行身份。新旧产物及临时签名分别记录，不声明字节完全一致或设备重跑。452项依赖技术复核不构成发行批准。

## 必要证据

- [config-roundtrip-results.json](../inputs/config-roundtrip-results.json)
- [config-result.json](../runs/independent-build-04/config-result.json)
- [independent-review.json](../reviews/independent-review.json)
- [report.json](../runs/independent-build-04/report.json)
- [independent-ai-process-exit.json](../runs/independent-build-04/independent-ai-process-exit.json)
- [native-build-native-build-report.json](../runs/independent-build-04/native-build-native-build-report.json)
- [parser-apk-build-report.json](../runs/independent-build-04/parser-apk-build-report.json)
- [boundary-build-native-build-report.json](../runs/independent-build-04/boundary-build-native-build-report.json)
- [boundary-apk-build-report.json](../runs/independent-build-04/boundary-apk-build-report.json)
- [parser-structure-independent.json](../runs/independent-build-04/parser-structure-independent.json)
- [independent-build-check.json](../reviews/independent-build-check.json)
- [inventory.json](../licensing/inventory.json)
- [reproduction-inputs.json](../inputs/reproduction-inputs.json)

判据和精确关联以 [阶段清单](../manifest.json) 为准。迁移没有重做实验；历史版本缺口见 [输入身份](../inputs/reproduction-inputs.json)。
