# P00 技术可行性报告

P00-01–08 / M1 于2026-10-12在约定范围通过。此处汇总既有实验；证据治理迁移没有重做真机、扩展支持范围或批准生产补丁。逐项可检查判据见 [manifest](manifest.json)。

## 范围与结论

固定EasyTier commit为7592951b808c4839cb9d77cc4bc642f124915951，源码声明2.7.0，不表示稳定发行版支持。真机范围为Xiaomi 2509FPN0BC、Android16/API36、arm64-v8a、4096字节页、免Root、受控私有Wi-Fi、IPv4/TCP外层，VPN只覆盖实验包。首次补丁和v3被测APK/native身份分别保存，production_approved=false。

| 项目 | 已观察到的结果 | 细节 |
|---|---|---|
| P00-01 | R01–R24设计基线 | [需求](reports/p00-01-baseline.md) |
| P00-02 | 固定来源、双ABI构建及另一AI空缓存独立复建 | [版本与复建](reports/p00-02-versions.md) |
| P00-03 | 44编辑单元/52来源键/8隐藏项，88案例宿主往返和真机JNI | [配置](reports/p00-03-config.md) |
| P00-04 | 20轮TUN释放，真实protect及独立进程panic | [原生边界](reports/p00-04-native-boundary.md) |
| P00-05 | TCP/UDP各20次，20轮连接中启停及系统异常 | [数据通路](reports/p00-05-datapath.md) |
| P00-06 | 六个有限地址/平台参数场景与旧代次过滤 | [地址](reports/p00-06-address.md) |
| P00-07 | 六个真实请求、监听释放；Portal仅绑定 | [高级探针](reports/p00-07-advanced.md) |
| P00-08 | 单轮首回包、串行回显、19个拔电灭屏样本 | [测量](reports/p00-08-measurement.md) |

## 复核与输入完整性

[独立复核](reviews/independent-review.json)实际执行空缓存复建并交叉检查既有真机记录，没有重做设备实验。主代理换主机执行、静态复核和实际独立执行分别说明；旧模板署名不能替代执行身份。构建流程可复跑不等于APK逐字节一致。

[输入身份](inputs/reproduction-inputs.json)保留30项历史构建检查及83项版本缺口记录；该数字是缺口条目数，不是83个独特文件。部分早期APK的精确源码未找到。当前维护探针不是这些历史版本的替代品，迁移后的完整Android/native复建及真机重跑没有执行。

## 未验证项与停止条件

正式服务/bridge、会话代次、并发fd替换、指定物理网络、生产异步事件与恢复，由P02验收。DNS实际查询、IPv6、WireGuard/Portal互操作、入站公布、打洞/中继、蜂窝/NAT及平台TLS，由对应能力阶段验收。其他OEM/API、x86_64数据通路、16KB runtime及核心增量耗电、多轮性能置信度未通过。

[许可清单](licensing/inventory.json)记录452项固定来源、必要许可证文本/通知与技术复核。最终APK链接清单、对应源码/通知包、共享库替换或重链接机制、适用安装信息及JVM源码关联仍待实际发行前验收；distribution_approval=not_granted。材料或机制不符即停止发行。

## 证据维护

按 [验证规范](../../development/verification.md) 先筛选、脱敏、检查，再冻结所选记录。阶段清单不冻结主应用或持续维护工具。重要计数/代理/必需键负向结果保留，工具环境失败合并为 [原因摘要](failures.json)。原件已退出交付，旧摘要仅提供转换来源；[处置清单](migration.json)登记全部原有文件的处理。

常用入口为 [最小探针](../../../experiments/android-vpn-smoke/README.md) 和 [复跑说明](reproduce/README.md)。运输清单只覆盖选定阶段材料，校验通过不等于新增设备或发行验收。
