# 实验、真机可行性验证与证据治理规范

本规范适用于技术可行性实验、设备行为调查及阶段验收。目标是用最小充分证据支撑明确结论，并让输入、范围、隐私和退出方式可检查。当前实例见 [P00 报告](../verification/p00/report.md)。

## 1. 选择验证方式

常规开发使用最小充分的领域测试、构建和 CI；文案及低影响修改不生成专门证据包。存在技术不确定性、只能在设备上观察的行为或阶段门槛时，才建立实验。模拟器、真机、静态检查与独立复建分别声明，不互相继承通过状态。

实验开始前明确目标、主要因素和响应指标，优先回答会改变工程决策的问题。[NIST 实验设计](https://www.itl.nist.gov/div898/handbook/pri/section1/pri11.htm) 设备测试按行为和风险选择，普通逻辑先用宿主测试。[Android 测试基础](https://developer.android.com/training/testing/fundamentals)

## 2. 实验声明与生命周期

实验源码放在 `experiments/<experiment_id>`，声明文件为 `experiment.json`。每次运行使用新的 `run_id`；重试不能覆盖旧结果。声明至少包含：

| 项目 | 要求 |
|---|---|
| 身份与职责 | experiment_id、owner_role、state、review_at、claim_ids |
| 问题与范围 | 待解决问题、预期工程决策、适用设备/网络/能力和不覆盖项 |
| 场景与判据 | 固定输入、实际操作、可观察结果、通过/失败条件；负向场景单独声明 |
| 预算与停止 | 执行次数/时长、发布文件/字节预算、失败与资源上限停止条件 |
| 退出安排 | 转为正式测试、继续维护最小探针或退役；下次检查时点 |

独立问题使用独立实验身份；共享被测产物、判据和退出安排的子场景可在同一实验内聚合，保留唯一声明。目录与工具归属见 [目录边界 ADR](../design/adr/0003-directory-boundaries.md)。

维护工作流的额外输入登记为 `reproduction.inputs`，以角色映射到规范的仓库相对文件路径；实验内源码和 fixture 由导出器按实验身份收集。实验或其测试依赖仓库通用工具时，也须声明所需工具及导入依赖，保证导出闭包可独立使用。例如：

```json
{
  "reproduction": {
    "inputs": {
      "config_mapping": "docs/verification/p00/inputs/config-mapping.json",
      "native_boundary_patch": "docs/verification/p00/inputs/upstream/native-boundary-patch-v3.json"
    }
  }
}
```

没有额外输入的实验使用空对象。声明与输入必须来自同一选定树；越界路径、符号链接、缺失输入或实验身份不符均阻止导出。所有输入验证后才创建仓库外的新输出目录，收据保存所导出文件摘要并明确 `experiment_executed=false`。当前维护源码不等于历史 APK 的精确输入。

生命周期为设计、执行、分析、发布必要证据、关闭。关闭表示本次问题已有结论，并不表示所有能力都通过。关闭实验仍可保留可维护探针；每次复跑继续使用新 run_id。发布预算须在运行前确定，超出时说明新增材料支撑的判据并修订声明，不能为了预算删除必要观测。

运行后修改判据必须建立新实验修订。遇到身份不匹配、授权条件不足、对端不受控、资源预算耗尽或采集不能解释的异常时停止；不能无限重试直到偶然成功。

## 3. 结论、运行与产物身份

分别记录以下三个维度：

- layer：源码检查、构建、配置解析、平台行为、原生边界、数据通路或测量。
- environment_kind：宿主、模拟器或物理设备。
- review_kind：静态复核、结果重算、独立复建或独立设备重跑。

运行记录保存实际执行者及 UTC 时间、输入身份、工具版本、必要命令/退出码、观察、判据、状态、范围、缺口。缺测使用 null，不填 0。旧记录没有的字段不补造成采集事实；迁移补充信息放在 publication 中。

构建记录绑定固定源码与库摘要；APK 记录绑定实际打包库；设备记录绑定实际安装 APK 和 native 库。新构建产物不能继承旧设备记录。构建通过、配置接受、监听绑定都只支持对应层次；实验补丁不自动成为生产接口。

请求/响应保留 nonce 或载荷摘要、协议、次数及设备/对端关联。测量保存必要原样本、单位、时钟、公式、样本数、缺测和对照条件。单轮结果不推广为稳定性能，整机耗电不推广为核心增量耗电。

独立执行者身份来自实际复核活动，不来自旧执行器模板。换一台主机不自动形成独立复核，静态重算不自动形成设备重跑。

## 4. 筛选与冻结

保留关系为：结论 → 判据 → 观察 → 被测产物身份 → 必要输入。文件必须支撑该关系、解释重要负向结果或承担必要许可材料职责。仅因生成过、被旧全量清单引用过、方便保存会话过程而保留，均不成立。

同一载荷可保存一次，各运行的身份、时间、参数和结果分别引用。必要代码版本通过固定上游 commit、摘要、锁文件和自有补丁定位；不可恢复输入才保留内置副本。当前维护工具不冒充历史精确源码，缺失版本明确登记。

制品应相关、可理解并包含执行所需材料；这不要求保存全部开发过程。[ACM SIGSIM 制品评估](https://sigsim.acm.org/conf/pads/2022/blog/artifact-evaluation/) 来源记录采用必要参数、依赖 URI/版本/摘要和运行信息，删除缓存位置等无关环境细节。[SLSA v1.2 Provenance](https://slsa.dev/spec/v1.2/build-provenance)

顺序固定为：筛选 → 脱敏 → 判据与隐私检查 → 发布 → 冻结发布字节。发布记录禁止覆盖，更正和补测建立新记录与替代关系。阶段 manifest 是可修订的所选证据目录，revision 递增；它引用的冻结记录不得原地重写。

维护导航、现行工程说明与规范可随实际项目更新；发布的选定记录、历史基线和原始来源记录保留执行或获取时的身份，不补写成新的验证事实。根 handoff 与 SHA256SUMS 描述所选阶段的运输完整性，不是当前仓库状态台账；普通文档维护不扩大其范围，也不生成新的实验包。

阶段清单 schema_version=3，包含 stage、revision、phase、claims 和 entries。entries 每项保存仓库相对路径、sha256、role、claim_ids；不包含自身，不冻结持续开发的 app、AGENTS 或整个 experiments。claims 保存范围、证据与可重算判据。阶段内未登记文件、越界路径、缺项和摘要变化均失败。

判据使用 record、JSON pointer、operation、expected。现有操作包括 equals、length、at_least、all_passed、hash_pairs、roundtrip_cases 、not_empty、accepted_count，以及ownership_cycles/host_roundtrips/loop_observations/performance_samples的领域检查。涉及额外契约时补充有意义的判据及反例测试，不用一个 passed 字段替代全部观察。

## 5. 隐私与采集边界

发布材料不得包含个人主目录、真实设备序列号及其稳定摘要、宿主用户名/主机名、凭据和不必要的真实网络标识。设备使用范围内匿名编号；保留必要型号、API、ABI、页大小及系统版本。

路径使用仓库相对路径及 SDK_ROOT、RUN_ROOT 等逻辑标识。执行器为后续步骤使用的本地绝对路径仅属于被忽略的临时运行收据，不能直接复制入库。发布时转换嵌套 JSON、argv、环境、输出和异常栈；秘密不能自动替换成看似合法的测试输入，发现后阻止发布。

公开项目 namespace、上游作者名称、合成配置 hostname 和合成测试数据保留其语义，不进行同名字串全局替换。真实网络标识按角色匿名化，保留路由、网段、端点对应及判据相关关系。[OWASP 日志规范](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)

二进制可能嵌入构建路径，须在构建阶段设置路径映射并检查输出。修改后的二进制是新产物，不能替换历史被测身份。[Reproducible Builds 构建路径](https://reproducible-builds.org/docs/build-path/)

旧证据脱敏记录旧摘要、新摘要、转换类别和理由，迁移前后核对状态、观察数值、样本和产物身份。已删除原件的摘要仅提供转换来源，不能代替原字节或证明历史精确复建。

## 6. 输出、保留与关闭

源码、fixture 和声明留在 experiments。默认运行输出放在被忽略的 `build/experiments/<experiment_id>/<run_id>`。必要发布记录进入 `docs/verification/<stage>`；运输、进度、缓存、私钥、APK 和普通完整日志不进入源码目录。

目录迁移验证导出闭包、CLI 导入、夹具身份和复建编排；构建与设备重跑分别登记。Android VPN 探针的 `test_workflow.py` 使用离线夹具与合成 ZIP 验证源码闭包，`rebuild.py --dry-run` 只检查输入并预览步骤。这两类检查都不创建 native 构建或新增真机验收。

本地调试材料默认 7 天，已脱敏 CI 诊断制品默认 14 天；例外写入实验声明。选定证据随结论保留，结论失效/被替代时重新评估。重复工具失败可合并原因摘要，重要负向结果、架构取舍及未解决阻塞保留。

运行关闭时在输出目录登记 lifecycle.json，包含 experiment_id、run_id、state=closed。清理只接受 build/experiments 下过期且明确关闭的运行；默认预览，显式 --apply 后删除。无后台自动清理或固定日期自动开展下一阶段。

## 7. 发布接口与检查

先从本地运行选出必要文件，在同目录写 bundle.json：

```json
{
  "experiment_id": "android-vpn-smoke",
  "run_id": "config-check-01",
  "case_id": "config",
  "declaration_sha256": "<运行前experiment.json规范JSON的SHA256>",
  "actor": {"role": "developer", "id": "executor-01"},
  "scope": "固定核心的宿主配置往返",
  "claim_ids": ["P00-03"],
  "records": [{"path": "result.json", "role": "observation"}],
  "criteria": [
    {"record": "result.json", "pointer": "/status", "operation": "equals", "expected": "passed"},
    {"record": "result.json", "pointer": "/cases", "operation": "length", "expected": 88},
    {"record": "result.json", "pointer": "/cases", "operation": "roundtrip_cases"},
    {"record": "result.json", "pointer": "/cases", "operation": "accepted_count", "expected": 82}
  ]
}
```

真机记录必须同时选取对应构建，records 条目用 build_record 指向同一 bundle 内构建 JSON。发布器只向已有版本 3 阶段和已声明结论发布，不能自动扩大阶段范围或批准生产能力。该示例是接口说明，实际判据必须来自运行前的实验声明。case_id选择声明中的场景；declaration_sha256为experiment.json经evidence_io.json_bytes规范化后的摘要，运行前保存，发布器拒绝判据或声明摘要变化。

```bash
python3 scripts/verification/publish-evidence.py --experiment android-vpn-smoke --run build/experiments/android-vpn-smoke/config-check-01 --stage p00
python3 scripts/verification/publish-evidence.py --experiment android-vpn-smoke --run build/experiments/android-vpn-smoke/config-check-01 --stage p00 --publish
python3 scripts/verification/check-privacy.py
python3 scripts/verification/check-evidence.py --stage p00 --require-stage
python3 scripts/verification/test-evidence.py
python3 scripts/catalog/audit_config_catalog.py
python3 experiments/android-vpn-smoke/test_workflow.py
python3 scripts/validate_handoff.py --strict
```

发布前默认仅预览，--dry-run 与默认相同。发布后刷新阶段运输清单；生成器只读选定树，不重新生成冻结记录摘要：

```bash
python3 scripts/verification/freeze_handoff.py --tree index --stage p00
python3 scripts/verification/check-privacy.py --tree index
python3 scripts/verification/check-evidence.py --tree index --stage p00 --require-stage
python3 scripts/validate_handoff.py --tree index --strict
```

生成 handoff.json、SHA256SUMS 后由任务执行者暂存它们。暂存检查只读暂存字节，不借用未暂存文件。check-evidence 退出码为 0 检查通过、1 完整性或声明冲突、2 要求阶段门槛但未满足。PASS 不新增任何构建、设备或发行验收。

本地与 CI 使用相同检查；CI 不生成或上传原始个人环境日志。失败必须解释和修复，不删断言、关闭检查或更新一个 PASS 文件掩盖问题。
