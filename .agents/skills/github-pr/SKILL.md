---
name: github-pr
description: 执行串门（Chuanmen）的 GitHub PR 工作流：核对任务分支与远端，复用 git-commit，使用 gh 创建或更新 PR、跟踪检查并交付待审结果。用于“提交 PR”“更新 PR”“跟踪 PR 检查”及明确的后续合并请求；普通本地提交使用 git-commit，tag 与 Release 另行授权。
---

# GitHub PR 交付

将已授权的开发任务交付为检查完成的 Ready PR，默认保留待审。

## 规范来源

- 分支、PR、检查与合并：[Git 工作流](../../../docs/development/git-workflow.md)。
- 版本、tag 与 Release：[版本治理](../../../docs/development/versioning.md)。
- 本地提交：[git-commit](../git-commit/SKILL.md)。
- 正文要求：[PR 模板](../../../.github/pull_request_template.md)。

按需读取这些来源，不在技能中维护提交格式、路径分类或版本规则的副本。技能本身不授予提交、推送、合并或发布权限；沿用当前任务已经给出的授权。

## 1. 核对仓库与任务

在仓库根目录检查 `git status --short --branch`、暂存和未暂存差异、未跟踪文件、当前分支及远端 URL；使用 `gh auth status`、`gh repo view --json nameWithOwner,defaultBranchRef` 核对目标仓库、认证与默认分支。不要输出凭据。

查询当前任务分支的已有 PR，同任务已有 PR 时更新它。新任务从最新 `main` 创建短期分支，未合并任务沿用原分支；主干更新按工作流同步。不覆盖无关改动，不自行 stash、reset 或重写已推送历史；不能安全隔离时暂停受影响的操作并说明原因。

## 2. 检查与提交

审阅实际补丁和新文件，执行与改动风险相符的本地检查，记录实际结果与未覆盖范围。在已有提交授权内复用 `git-commit`，只纳入本任务改动，保留 hooks 和签名要求。提交后核对内容与工作区。

## 3. 创建或更新 PR

在已有推送与 PR 授权内推送任务分支；核对远端 head 与本地 SHA 一致。按模板将正文写入忽略目录中的 UTF-8 文件，再传给 `--body-file`；标题与正文作为数据传递，不拼接为可执行 shell 文本。

新 PR 使用 `gh pr create --draft --base main --head ... --title ... --body-file ...`。已有 PR 使用 `gh pr edit` 更新标题或正文，目标与范围仍须符合规范；不要重复创建同任务 PR。

## 4. 跟踪检查并交付

使用 `gh pr checks <PR> --required --watch` 跟踪最新 head 的必需检查，必要时查看关联的 workflow run 与日志。标题编辑和转 Ready 会重新运行 PR 策略；同名检查有历史记录时核对最新运行，不能任选旧结果。

统一 CI 对所有 PR 执行 static 和 android，quality-gate 要求两者均为 success；pr-policy 也必须成功。失败、取消、缺失或跳过均不能作为交付通过；检查结果与当前 head SHA 不符时重新核对。

检查完成后更新正文中的实际验证结果与限制，执行 `gh pr ready`，等待新触发的 `pr-policy` 完成，并复核最终状态。

交付 PR 链接、完整 head SHA、检查结果及必要的运行链接、验证限制。**默认停在 Ready 待审 PR，等待维护者决定合并。** 普通 CI 不写成真机、阶段退出或发布验收。

## 5. 明确的后续合并请求

只有用户后续明确要求合并才进入此步骤。合并前重新核对用户已审阅的完整 head SHA、Ready 状态、最新必需检查、主干同步与讨论解决情况；SHA 变化时等待重新审阅，不把旧批准用于新增提交。

使用 `gh pr merge <PR> --squash --match-head-commit <已审阅SHA>`，不使用 `--admin` 或 `--auto`。合并后按工作流核对最终标题与正文、远端分支清理及 `main` CI；核对变更完整后更新本地主干和清理旧任务分支。

创建 tag 与发布 Release 继续独立授权，不从 PR 或合并请求推导。
