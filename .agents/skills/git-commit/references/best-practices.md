# Git commit 编写：调研依据与示例

核查日期：2026-09-12。以下区分 Git 行为、项目约定、格式规范和本技能的工作流选择；不要求每次写提交都重新联网。

## 来源与结论

| 来源 | 可采用的结论 | 适用边界 |
| --- | --- | --- |
| [Git 官方 SubmittingPatches](https://github.com/git/git/blob/master/Documentation/SubmittingPatches) 的 Separate commits / Describe your changes | 按逻辑拆分；说明动机、前后行为和必要取舍；查看路径历史了解标题习惯；50 字符为软限制 | Git 项目自身的 `area:`、大小写、邮件补丁及 DCO 约定不自动适用于所有仓库 |
| [Pro Git：Contributing to a Project](https://git-scm.com/book/en/v2/Distributed-Git-Contributing-to-a-Project)，[本次读取的原文](https://github.com/progit/progit2/blob/main/book/05-distributed-git/sections/contributing.asc) 的 Commit Guidelines | 一条提交一个逻辑改动；可用部分暂存拆分同文件修改；简短标题、空行、解释性正文；英文祈使式；正文约 72 列 | 可读性建议不能升级为 Git 技术限制，也不能代替现有项目规则 |
| [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) 的 Summary / Specification / FAQ | 类型前缀；可选 scope、正文及尾注；`feat`、`fix` 的语义；`!` 或 `BREAKING CHANGE:` 标注破坏性变化 | 本 skill 统一采用该规范并遵守其 MUST；额外类型由 skill 或项目约定，规范未规定 50/72 长度 |
| [git-commit 手册](https://git-scm.com/docs/git-commit)，[官方 HTML 镜像](https://github.com/git/htmldocs/blob/gh-pages/git-commit.html) 的 `--only` / `--file` | 普通提交以 index 为输入；指定路径时默认采用其工作区内容并忽略其他路径的暂存内容；`-F` 从文件读取文案 | `--only` 不是部分暂存过滤器 |

本次直接读取 Git 贡献指南、Pro Git 原文及 Conventional Commits 规范；git-scm 的命令手册返回 HTTP 403，`--only` 通过 Context7 检索官方 Git HTML 镜像核对，`-F` 通过本地临时仓库验证。

本技能采用的工程判断：先依据差异再写文案；让实现及必要测试构成完整改动；保护原有暂存选择；提交授权不延伸到推送和改写历史。它们服务于准确性和范围控制，不冒充 Git 或 Conventional Commits 标准条款。

## 内容质量比前缀更重要

以下均为假设示例，不描述任何真实仓库的现状或已完成测试。实际使用时必须根据 diff 重写。

含糊：

```text
fix: 修复问题并优化逻辑
```

具体，并说明触发条件与行为：

```text
fix(parser): 拒绝超出范围的节点端口

节点端口超过 65535 时，直接转换会截断数值，使后续请求发往错误端口。
在构造节点地址前校验端口范围，非法输入返回解析错误。
```

中文描述同样保留类型前缀，例如 `fix(parser): 拒绝超出范围的节点端口`；历史中的普通中文标题不改变本 skill 统一采用 Conventional Commits 的要求。

简单文档改动不需要填充正文：

```text
docs: 修正快速开始中的配置文件路径
```

Breaking change：

```text
feat(cli)!: 将监听地址统一为 --listen 参数

使用完整套接字地址同时表达主机与端口，消除两个参数的组合歧义。

BREAKING CHANGE: 移除 --host 和 --port；启动脚本需改为
  --listen 127.0.0.1:8080。
```

同时使用 `!` 和尾注是为了提供迁移信息，规范不要求两者同时存在。即使类型是 `refactor` 或 `chore`，真实的破坏性变化也需要标识。是否自动发版取决于项目配置，写 `feat` 本身不等于发生发布。

## 什么时候拆分

- 修复端口校验、增加相应回归测试、更新错误行为说明：通常同一提交。
- 修复端口校验，同时独立调整日志配色：通常拆分。
- 引入依赖并立即用它实现功能：通常一起提交；不要只为按文件归类，把锁文件留到导致中间提交无法构建的位置。
- 重命名模块与功能修复：能分别保持调用路径正确时拆分；否则先保证每个提交自洽。

测试整个工作区得到的结果仅证明该工作区状态。拆分后若未验证各自快照，不写“每个提交都已通过”。写文案任务通常无需运行测试；可如实引用已有、适用于该差异的验证结果。

## 格式与工作流的条件性规则

- **50/72**：来自常见阅读和邮件展示习惯；中文显示列数与字符数并不相等。URL、标识符、代码片段不要为了凑列宽切断。已有 lint 上限是另一个具体约束。
- **类型集合**：Conventional Commits 定义 `feat` 和 `fix` 的语义，允许额外类型；完整枚举通常来自项目或 commitlint 配置。`style` 在常见约定里指不影响含义的格式变化，不应仅凭“视觉样式”字面就用于 UI 功能。
- **Issue 与 trailer**：关联编号需有来源。`Reviewed-by`、`Tested-by` 等是事实声明；没有证据就不添加。DCO sign-off 和 GPG/SSH 签名是不同机制，按项目要求和真实授权处理。
- **Squash**：仓库若通过 squash 生成最终提交，最终 message 应描述合并后的完整结果；是否校验 PR 标题取决于仓库配置。本 skill 起草的本地提交同样采用 Conventional Commits，不推导出自动 squash/rebase 的权限。
- **部分暂存**：`git diff --cached -- path` 看待提交快照相对 HEAD 的变化，`git diff -- path` 看工作区相对暂存区的变化。两者同时存在时，标题与正文必须对应实际选中的快照。

## 使用时的情景自检

这些是人工审阅技能行为的情景，不是自动化测试通过记录：

| 请求与材料 | 应观察到的行为 |
| --- | --- |
| “只给已暂存内容写 message”，同文件还有未暂存修改 | 文案仅描述暂存 diff；没有 add 或 commit |
| “提交当前修复”，无关文件已暂存，目标文件另有未获授权的 hunk | 不用整文件 `--only` 吞入额外 hunk；先明确并隔离提交边界，保护原 index |
| “按仓库规范起草”，历史使用中文普通标题且没有格式规则 | 使用 Conventional Commits 类型前缀加具体中文描述；不因历史无前缀而省略类型 |
| 使用 Conventional Commits，补丁删除旧 CLI 参数 | 标注破坏性变化，交代替代参数及迁移；不把行为删除仅归为内部重构 |
| 补丁增加了测试但没有执行记录 | 可以说“增加回归覆盖”，不能说“测试已通过” |
| “执行前面已确认的本地提交方案” | 按已有授权检查并提交；不重复要求确认，不 push、不 amend |
