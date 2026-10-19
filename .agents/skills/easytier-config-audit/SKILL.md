---
name: easytier-config-audit
description: "审计本项目 EasyTier 配置目录、官网助手映射与 Android 显隐。用于核对配置缺项、助手版本变化、基础/完整表单范围、默认值或配置序列化的跨层影响。依赖本仓库的目录与审计脚本；不用于普通主题排版、通用 Android 开发或仅凭目录判定真实 VPN 连通。"
---

# EasyTier 配置目录审计

## 输入与范围

从项目根定位 `docs/design/config/assistant-catalog.snapshot.json`、`docs/design/config/android-capability-plan.json` 和 `scripts/catalog/audit_config_catalog.py`。默认只读审计；用户已经要求修正时，按其范围提交可审阅修改并重新验证。

先区分任务：冻结目录内部审计、官网部署更新比较、还是原生实现对齐。不要把其中一类结果冒充另一类。

## 步骤

1. 阅读目录 `meta`、能力台账状态与本次请求涉及的字段。确认部署来源、捕获时间、资源哈希和参考核心版本分别代表什么。
2. 在包根执行：

   ```bash
   python3 scripts/catalog/audit_config_catalog.py
   ```

   保留退出码和结果。输入缺失或 JSON 损坏时报告审计未完成；不得从文档中的旧数字推断通过。

3. 比较用户提供的新目录时，使用明确的输入路径：

   ```bash
   python3 scripts/catalog/audit_config_catalog.py --catalog candidate.json --baseline docs/design/config/assistant-catalog.snapshot.json
   ```

   `candidate.json` 是本次待审计文件，不由技能凭空生成。外部候选默认不套用项目能力台账；有相应台账时用 `--capability-plan` 明确指定，并核对 `capability_plan_checked`。退出 `0` 为约束通过且无比较差异；`1` 为错误或待审差异；`2` 为输入问题。完整读取 `errors` 与 `differences`，不要只看其中一个。报告选项和契约见 [审计边界](references/audit-contract.md)。

4. 用户要求核对官网现状时，读取实际部署助手入口及实际加载的配置组件/资源，记录日期、URL 和 SHA-256。部署 commit 未公开就写未知。仓库主分支可解释语义，不能替代线上部署事实。无法获取时保留冻结快照并说明未完成的在线比较，不重写它。
5. 逐项解释变更：新增、删除、改名、默认值、类型、正反向语义、依赖、平台显隐和运行影响。复合编辑单元与叶子字段分开统计；37+7 是当前快照，不是永久功能上限。
6. 对受影响字段检查基础/完整共享草稿、搜索、原始错误输入、missing/empty、序列化和保留未知项。需要原生代码才能确认的项目列证据缺口与对应阶段，不编造实现或测试。
7. 输出差异表、实际执行的验证与剩余证据。默认不修改目录；已授权修正时同步元数据、能力台账和源引用，重新运行相关检查。

## 项目特有的误判防护

- 历史原型 `profile.label` 表达本地显示名语义；当前 `lib/profile/Profile.kt` 中的 `localDisplayName` 是本地元数据，不写核心 `instance_name`，不制造核心待应用。原型已退役，历史源码在 P02 基线 `872bea7e3898881e4787b0adb012726933cd8329`；配置目录与 Android 能力计划仍是现行消费者。
- `availability=available` 和 `apply=core-hot` 是原型历史研究标签，不证明当前 JNI/二进制支持。
- “Android 平台托管”“确实不适用”“暂未实现”分别判断。`ipv6_public_addr_auto` 的旧隐藏理由是尚未适配，不能据此永久删项。
- 隐藏同时约束表单与搜索，但保存/导出不能无提示删除原始配置。保留原文也不等于无条件执行未支持参数。
- 保存与实际应用分开；离线保存不是待应用；切标签不是另一份配置。
- 不把日志调级别 RPC 当日志流；不把目录中 Portal/DNS/中继存在当作真机生效证据。

## 输出

用一张表列 `key / 差异 / Android 决定 / 影响 / 证据 / 下一步`，随后写：执行命令及退出码、未验证项目、是否改文件。只输出差异属性，不打印真实网络密码、私钥或含凭据的 URI。

如果只有结构审计通过，结论写“冻结目录内部一致”；只有核对实际部署后才写“与本次观察的官网版本对齐”；只有对应原生版本和设备测试后才可写“该能力在测试范围内生效”。
