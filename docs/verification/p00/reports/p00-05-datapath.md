# P00-05 真实数据与系统异常

TCP/UDP各20次真实回包，另20轮连接中启停；请求/响应与对端摘要对应。实际TUN读写及外层路由/protect判据限定IPv4/TCP外层。实际拒绝、撤销通知释放与断网分别采集；断网停止归实验工作线程，不证明核心自动恢复。计数遗漏和代理关闭负向记录保留。

## 必要证据

- [host-proxy-feature-evidence.json](../inputs/source/host-proxy-feature-evidence.json)
- [xiaomi-native-datapath.json](../runs/p00-7dd889eefb90/xiaomi-native-datapath.json)
- [xiaomi-native-connected-cycles.json](../runs/p00-5425a620eda2/xiaomi-native-connected-cycles.json)
- [xiaomi-system-denied.json](../runs/legacy-permission-denial/xiaomi-system-denied.json)
- [xiaomi-system-revoke-notification.json](../runs/p00-5b00f361a649/xiaomi-system-revoke-notification.json)
- [xiaomi-system-offline.json](../runs/p00-a9cc7a521f55/xiaomi-system-offline.json)
- [xiaomi-datapath-write-counter-failure.json](../runs/p00-159e4cbdd2e9/xiaomi-datapath-write-counter-failure.json)
- [xiaomi-datapath-proxy-disabled.json](../runs/p00-47b1a033c36c/xiaomi-datapath-proxy-disabled.json)
- [independent-review.json](../reviews/independent-review.json)
- [result.json](../runs/system-errors/result.json)

判据和精确关联以 [阶段清单](../manifest.json) 为准。迁移没有重做实验；历史版本缺口见 [输入身份](../inputs/reproduction-inputs.json)。
