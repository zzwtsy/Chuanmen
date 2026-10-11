# 候选搜索与校准

搜索用于定位需要阅读的段落，不用于判定错误或直接生成替换。命中、未命中和命中数量均不是质量结论。

## 目录

- [先确定范围](#先确定范围)
- [建立文件清单](#建立文件清单)
- [五组候选探针](#五组候选探针)
- [处理命中与补查](#处理命中与补查)
- [报告实际覆盖](#报告实际覆盖)

## 先确定范围

| 用户指定的对象 | 搜索方式 |
| --- | --- |
| 粘贴的片段 | 直接阅读；不为搜索而落盘或扫描仓库。 |
| 明确文件 | 阅读文件及相关上下文；需要搜索时只传入这些文件。 |
| 明确目录 | 先列出符合项目约定的文件，再在同一范围搜索。 |
| diff | 确认比较基准，阅读变更行及理解它们所需的上下文；不自动扩成全仓审校。 |

先读取适用的项目指令、文档约定、ignore 规则与生成来源说明，记录语言、文件用途和版本基准。
diff 若未说明是工作区、暂存区还是提交间比较，先利用会话和仓库状态确定；不能确定时询问。
例如，已明确审查未暂存变更时，可运行以下只读命令；其他基准使用对应的已确认命令：

```bash
git diff --no-ext-diff --no-textconv --unified=3 -- ./docs ./src
```

列出的路径只是示例，必须替换为用户授权范围。已删除的文本用于理解变更，不属于当前正文。
读取范围外的事实来源只为核实主张，不将它自动加入修改清单。

## 建立文件清单

以下示例使用 Bash 数组，并假定当前目录是项目根目录。先确认 `./docs`、`./src` 属于审校范围。
小范围可直接阅读；没有 `rg` 时使用现有文件工具做语义阅读，无须安装扫描工具或扩大范围。

```bash
trim_scope=('./docs' './src')
trim_visibility=()
trim_include=(--type-add 'authoring:*.{md,mdx,rst,txt,py,kt,kts,js,ts,tsx,go,rs}' --type authoring)
trim_exclude=(-g '!.git' -g '!**/.git/**')
trim_options=(--no-config "${trim_visibility[@]}" "${trim_include[@]}" "${trim_exclude[@]}")
rg "${trim_options[@]}" --files -- "${trim_scope[@]}"
```

按项目实际文件类型调整 `trim_include`。扩展名只筛文件，不能区分注释、字符串、代码、提示词与测试输入。
`--type` 保留 ignore 的优先级；正向 `-g '*.md'` 会覆盖 ignore，不用它作为默认包含方式。
`--no-config` 避免用户全局搜索配置意外开启宽扫描；它不会禁用项目 ignore 文件。
不要使用 `-uuu`、`--no-ignore` 或默认启用 `--follow`。不得通过符号链接越出已确定范围。

依赖、生成物、冻结归档、快照和原始记录应根据项目说明及文件角色排除，不能仅凭目录名判定。
例如，只有确认下列路径承担这些角色，且它们不是用户明确的审查对象时，才追加相应排除：

```bash
trim_exclude+=(-g '!src/third_party/**' -g '!docs/generated/**')
trim_exclude+=(-g '!docs/archive/**' -g '!tests/__snapshots__/**')
trim_options=(--no-config "${trim_visibility[@]}" "${trim_include[@]}" "${trim_exclude[@]}")
rg "${trim_options[@]}" --files -- "${trim_scope[@]}"
```

包含条件在前，排除条件在后；负向 glob 也必须引用，避免被 shell 展开。修改选项后重新核对清单。
显式传给 `rg` 的单个文件会覆盖 glob 与 ignore 过滤；必须先确认每个文件，不将排除选项当作权限边界。
ignore 造成的遗漏要在报告中说明；用户点名的被忽略文件可单独读取，不为它关闭全项目 ignore。

只有已选范围包含需要审查的隐藏目录时，才设置 `trim_visibility=(--hidden)` 并重建 `trim_options`。
例如用户明确审查 `.agents/skills/example` 时，把它加入 `trim_scope`，然后开启隐藏文件搜索。
不要因此排除整个 `.agents`、`.codex` 或所有 `SKILL.md`；被指定的技能正文也是审校对象。
审查本技能时，可将 `references/examples.md` 的示例语料排除出自动候选；仍可只读检查其中的引用。
本页的探针和坏例本身也不是待清理残留；若任务要求校准示例，按示例角色阅读，不能批量删改。

## 五组候选探针

下列命令沿用已核对的 `trim_scope` 和 `trim_options`，输出文件、行号及两行邻接上下文。
按任务选择探针，无须机械运行全部。正则使用单引号与 `--`，避免 shell 展开或把文本当选项。
英文词边界只为降低词形误报；中文片段只提供阅读线索，不按字符集、字词或语言判定质量。

### 1. 临时会话与方案指代

```bash
rg "${trim_options[@]}" -n -H -C 2 -i -- '\bthis[[:space:]]+(PR|patch|round)\b|\bas[[:space:]]+(discussed|agreed)\b|本轮|本次讨论|按方案[[:space:]]*[A-Z甲乙丙丁]' "${trim_scope[@]}"
```

- 候选：`This PR fixes the wording.`；`本轮按方案 C 处理，依据是讨论 12。`
- 近似反例：`This project parses YAML.` 不因 `PR` 前缀命中。
- 语义放行：有清楚定义和正式引用的“方案 C”；规则文档中具有业务含义的“本轮投票”。
- 检查：指代能否独立定位，引用是否支持主张；不能只删除指代后留下无依据断言。

### 2. 变更叙述与历史表达

```bash
rg "${trim_options[@]}" -n -H -C 2 -i -- '\b(this[[:space:]]+change|previously|no[[:space:]]+longer|used[[:space:]]+to)\b|现改为|这次修改|不再|旧版' "${trim_scope[@]}"
```

- 候选：当前接口说明中的“现改为返回列表”；`This change replaces the old workaround.`
- 近似反例：`This key is used to decrypt tokens.` 会命中，但 `used to` 在这里说明功能。
- 语义放行：迁移指南中的“不再接受空字符串”；复盘中的 `previously` 和时间线。
- 检查：新旧差异是否正是该章节的用途。`/api/v1/items`、`v1` 目录及版本号不单独作为残留探针。

### 3. 评审对话与无依据的宽慰

```bash
rg "${trim_options[@]}" -n -H -C 2 -i -- '\b(for[[:space:]]+the[[:space:]]+reviewer|should[[:space:]]+be[[:space:]]+enough|nothing[[:space:]]+to[[:space:]]+worry)\b|为避免评审误解|这里不是偷懒|应该够|无需担心' "${trim_scope[@]}"
```

- 候选：`100 connections should be enough.`；`这里不是偷懒，容量开到 100 应该够。`
- 近似反例：实验计划中的“一小时应该够；此值只是排期估计，尚未实测”。
- 语义放行：有用途且明确标示为估计的判断；用于展示错误表达的原文引语。
- 检查：是否有必要的条件、证据或限制；不能把“应该够”改成“支持 100”或“上限为 100”。

### 4. 含糊的后续计划

```bash
rg "${trim_options[@]}" -n -H -C 2 -i -- '\b(for[[:space:]]+now|future[[:space:]]+work|we[[:space:]]+(may|could)[[:space:]]+(later|eventually))\b|以后再|先这样|后续可考虑|暂且认为' "${trim_scope[@]}"
```

- 候选：当前行为说明中的“先这样，以后再说”；没有条件或内容的 `future work`。
- 近似反例：提案中“后续可考虑分片，前提是基准测试达到已定义的扩展阈值”。
- 语义放行：路线图的未实现状态、实验假设、有效 TODO、待迁移义务及带条件的计划。
- 检查：删除空话是否会抹去未完成状态；不能自行补造 issue、负责人、日期或已批准方案。

### 5. 写作者独白与待核实话语

```bash
rg "${trim_options[@]}" -n -H -C 2 -i -- '\b(let[[:space:]]+me|I[[:space:]]+(think|guess)|we[[:space:]]+need[[:space:]]+to[[:space:]]+(check|verify))\b|我先想想|让我看看|我猜|需要再确认' "${trim_scope[@]}"
```

- 候选：长期文档中的 `Let me check the earlier answer.`；`我先想想，这个结论需要再确认。`
- 近似反例：`We need to verify the backup before migration.` 是操作要求。
- 语义放行：原始访谈引语、复盘记录、测试步骤与研究结论中的真实不确定性。
- 检查：区分作者临时自述与读者需要履行的动作；审校者未核实的信息写进报告，不自动写进正文。

## 处理命中与补查

先读所属段落、标题、相邻代码和引用；两行上下文不够时扩大阅读，不能直接据匹配行修改。
命中字符串字面量、UI 文案、工具描述或模型提示词时，先确认授权与行为影响，不能当作普通注释。
保留用于举例、测试、引用及冻结原始记录的原文；事实、适用范围和信息性质按主文件的规则核对。
每个候选可归为 `keep`、`rewrite`、`delete`、`suggest_relocation` 或 `defer`，不能只按探针编号决定。

所有探针都会漏检。例如“采用第三种以免再次争议”没有命中上述词，但可能依赖未提供的备选方案。
跨行表达、同义改写、未覆盖语言和藏在图像中的文字也可能漏检。继续阅读主要说明段落及逻辑连接。
重点检查没有明确指向的“上述”“相应调整”、突兀的理由、未经支持的保证和随文体变化的时态。
小范围读完全文；大范围说明抽样位置与未读部分，不以搜索替代语义审校。

## 报告实际覆盖

报告已读范围、选用探针、排除项、人工补查和待核实问题；必要时分别给出候选数与确认问题数。
`rg` 返回 `0` 表示找到匹配，`1` 表示本次搜索未匹配，`2` 表示错误；读取 stderr，不用 `|| true` 淹没错误。
零命中只表述为“这些探针在上述范围未找到候选”，不能表述为“没有写作残留”或“全仓通过”。
调整探针后在独立临时语料中核对候选、近似反例和合法命中；不把校准语料写入目标项目。
