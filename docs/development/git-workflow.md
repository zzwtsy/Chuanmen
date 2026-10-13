# Git 开发工作流

本文是串门（Chuanmen）分支、PR、检查和合并规则的维护来源。版本与发布见 [版本治理](versioning.md)，环境与检查命令见 [开发环境](setup.md)。

## 分支与任务边界

使用 GitHub flow：从最新 `main` 创建短期任务分支，完成一个完整意图，通过检查与复核，由维护者手动 Squash 合并回 `main`。当前没有并行维护多个产品版本的需求，不建立长期 `develop` 或发布分支；未来确需维护旧版本时再评审分支模型。

分支使用 `feat/描述`、`fix/描述`、`docs/描述`、`refactor/描述`、`test/描述`、`build/描述`、`ci/描述` 或 `chore/描述`，描述建议用小写英文和连字符。这是文档约定，暂不配置分支名强制规则。一个 PR 包含同一意图所需的代码、测试和文档；无关的依赖升级、格式调整和功能修改分开审阅。

开始前核对工作区、当前分支、远端仓库和已有 PR，保留他人改动；已有同任务 PR 时更新它。工作区干净时：

```bash
git fetch origin
git switch main
git merge --ff-only origin/main
git switch -c feat/profile-import
```

任务分支落后主干时，将最新 `origin/main` 合入任务分支并重新检查。任务分支可以保留更新主干的 merge commit，最终 Squash 后主干保持线性。只有明确授权重写已推送历史时才使用 rebase 和 `--force-with-lease`。

## 提交、标题与正文

本地提交复用 [git-commit skill](../../.agents/skills/git-commit/SKILL.md)，根据实际 diff 分组和起草，只暂存本任务文件并检查暂存树。

PR 标题为 `type(scope)!: 描述`，scope 和 `!` 可选，描述可以用中文，不设长度硬限制。类型沿用 `feat`、`fix`、`docs`、`refactor`、`perf`、`test`、`build`、`ci`、`style`、`chore`、`revert`。scope 指向稳定模块或能力，描述说明最终行为，例如 `fix(configuration): 保存失败时保留草稿`。不兼容变化使用 `!`，正文补充 `BREAKING CHANGE:`、受影响对象和迁移方法。

PR 以 `main` 为目标，按 [PR 模板](../../.github/pull_request_template.md) 记录触发条件、原有问题、最终结果、实际验证命令与结果、未覆盖范围及兼容影响。静态、JVM、构建、模拟器和真机结果分别报告，运行链接对应具体 SHA。普通 CI 不构成真机、阶段退出或发布验收。

## 使用 gh 交付

先核对 `gh auth status` 和 `gh repo view --json nameWithOwner,defaultBranchRef`。推送、创建或修改 PR 应在任务授权范围内；普通本地开发不自动授予发布权限。正文写入 UTF-8 文件后传给 `--body-file`，避免将多行正文或事件标题拼接成 shell 命令。

```bash
git push -u origin feat/profile-import
gh pr create --draft --base main --head feat/profile-import \
    --title 'feat(configuration): 导入配置草稿' \
    --body-file build/git-workflow/pr.md
gh pr checks --watch
```

Draft 用于尚在开发或等待检查的 PR，不绕过 CI。完成代码复核、本地检查、远端检查和正文更新后执行 `gh pr ready`，交付 PR 链接、head SHA、检查结果和限制。**代理默认停在待审 PR，由维护者决定合并；自动合并关闭。**

## CI 与必需检查

[统一 CI](../../.github/workflows/ci.yml) 在 PR 的 `opened`、`synchronize`、`reopened` 和 `main` 推送时运行，不设置顶层路径过滤；手动运行执行全部检查。它复用 [验证工作流](../../.github/workflows/verification.yml) 和 [Android 工作流](../../.github/workflows/android.yml)，两者保留各自手动入口。

| 路径变化 | Android 检查 |
|---|---|
| `app/`、`contracts/`、`gradle/`、根 Gradle 构建与配置、Wrapper、`scripts/android/`、`scripts/catalog/`、`docs/design/config/`、`ruff.toml`、`.github/` | 必须运行 |
| 其余 `docs/`、`.agents/`、`prototype/`、`experiments/`，根 README、AGENTS、CONTRIBUTING | 可以省略 |
| 未知路径 | 必须运行 |

验证每次运行。PR 按分叉点到 head 的差异分类，主干推送按前后 SHA 分类；删除保留原路径，重命名同时检查原路径与目标路径。识别失败不能输出免检。分类与回归位于 [scripts/git](../../scripts/git/workflow_policy.py)，使用 Python 标准库；变更分类时同步脚本、回归和本表。

固定必需检查为 `pr-policy` 和 `quality-gate`，绑定实际 GitHub Actions 来源。[PR 策略](../../.github/workflows/pr-policy.yml) 在创建、编辑、提交更新、重开和转 Ready 时验证标题及目标分支，元数据只从事件 JSON 读取。`quality-gate` 使用 `always()` 汇总：识别与验证必须成功；需要 Android 时必须成功，明确免检时只接受预期 `skipped`。失败、取消、缺失或意外跳过均不通过。

默认分支规则仅覆盖 `main`，要求 PR、上述检查、合并前更新主干、解决讨论和线性历史，禁止删除与非快进更新，无旁路身份。单维护者模式审批人数为 `0`，仍须维护者复核；增加协作者时再评审审批要求。首次启用必需检查前，先让引导 PR 实际产生这两个成功检查。

## 手动合并与下一任务

维护者核对 Ready 状态、head SHA、最新检查、变更与讨论后手动 Squash。仓库只开放 Squash，最终标题取 PR 标题、正文取 PR 描述；合并时核对 GitHub 添加的 PR 编号及最终预填内容。合并后自动删除远端任务分支。

代理只有收到明确的后续合并请求才执行合并，并核对用户已审阅的 SHA；新增提交后重新复核。CLI 使用 `gh pr merge --squash --match-head-commit <已审阅SHA>`，不使用 `--admin` 或 `--auto`。创建 tag 与发布 Release 独立授权。

合并后核对主干 Squash 标题/正文、远端分支清理和 `main` CI。工作区干净时 fetch、切回 `main` 并 `--ff-only` 更新；核对 Squash 已包含全部变更后清理本地旧任务分支。下一任务从最新 `main` 开分支。

## 规范的落地与维护

日常规则维护在本文，版本规则维护在版本治理；README、CONTRIBUTING、AGENTS 和模板保留入口与必要边界。[github-pr skill](../../.agents/skills/github-pr/SKILL.md) 组织已经验收的 PR 流程，默认交付待审结果，不复制规范。GitHub 设置承载合并与 tag 保护，CI 和脚本承载确定性判断。

修改远端设置前，将相关原值保存到忽略的 `build/git-workflow/`；逐项修改、回读核对，失败恢复对应设置。备份和提交不包含凭据。GitHub 设置不会随源码自动同步，规范变化须核对远端状态。

## 参考依据

核对日期：2026-10-14。短期分支与人工合并基于当前单维护者、单主干和阶段开发状态。

- [GitHub flow](https://docs.github.com/en/get-started/using-github/github-flow)
- [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)
- [Actions 事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
- [复用工作流](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
- [必需检查](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)
- [gh pr create](https://cli.github.com/manual/gh_pr_create)、[gh pr merge](https://cli.github.com/manual/gh_pr_merge)
