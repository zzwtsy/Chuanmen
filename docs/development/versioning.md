# 版本与发布治理

本文维护串门（Chuanmen）的应用版本、安装序号、tag 和 Release 规则。日常分支与 PR 见 [Git 工作流](git-workflow.md)，当前计划只安排 [P07 发布准备](../plan/p07-release.md)。

P07 只准备 GitHub APK，不包含 Play/AAB 或设备安装升级验收。下文 tag、签名、上传及正式发布流程仅在另行获得相应授权后执行；未授权时交付 unsigned APK 和待审材料，设备效果注明未验证。

## 版本各归一处

| 对象 | 维护来源 | 含义 |
|---|---|---|
| 应用版本与安装序号 | [app/build.gradle.kts](../../app/build.gradle.kts) | `versionName` 与 `versionCode` |
| Android/Kotlin/Compose 等依赖 | [版本目录](../../gradle/libs.versions.toml)及对应锁定配置 | 不随应用版本同步增长 |
| Gradle、JDK 等工具链 | Wrapper、Daemon JVM 配置及 [开发环境](setup.md)所指向的配置 | 构建条件 |
| EasyTier 核心及来源 | [app/native/inputs.json](../../app/native/inputs.json) 与 Cargo.lock | 上游核心身份，不等于应用版本或生产批准 |
| 阶段状态 | [路线图](../plan/ROADMAP.md) | 开发与验收状态，不以版本号代替验收 |

当前应用包名为 `com.github.zzwtsy.chuanmen`，`versionName = "0.1.0-dev"`、`versionCode = 1`；debug/release 共用真实配置入口与业务源码。这是开发版本，限定范围的 VPN 验证不代表发布签名与分发已就绪。后续数值只在构建配置修改，入口文档不维护另一份版本清单。

## versionName 与兼容性

采用 SemVer 的 `MAJOR.MINOR.PATCH` 和预发布标识：开发使用 `-dev`，对外候选使用 `-alpha.N`、`-beta.N` 或 `-rc.N`，正式版无预发布后缀。

`0.x` 不承诺稳定公共契约，但必须说明不兼容变化与迁移。兼容修复递增 PATCH；新功能或不兼容的配置、导入导出格式、会话契约变化递增 MINOR，并说明影响。达到稳定产品与兼容承诺条件后单独评审 `1.0.0`；此后破坏已承诺兼容性的变化递增 MAJOR，兼容新增递增 MINOR，兼容修复递增 PATCH。

Conventional Commits 的 `feat`、`fix`、`!` 为发布评审提供线索，不机械决定版本；依赖升级与内部重构也可能改变用户兼容性。普通 PR 不自动升版。发布 PR 汇总自上次发布以来的变化，统一决定版本、迁移与说明。

## versionCode 与分发候选

`versionCode` 是安装序号，与 SemVer 分别管理。相同 applicationId 的每个新分发候选都必须使用更大的正整数，包括 alpha、beta、rc 和转为正式版时重新构建的候选。正式版不能复用已分发预发布版的安装序号，不能靠 versionName 排序实现升级。

日常本地构建和未分发的普通 PR 保持当前序号。已发布产物不重建、不替换；修复使用新版本与更大的序号。发布前核对实际 APK 的包名、版本、序号、签名身份、ABI 与源码 SHA，并评估安装和配置升级路径。验收结论绑定对应 variant 与实际产物。

## 发布 PR、固定 SHA 与产物

发布独立于日常 PR 交付。创建 tag、访问发布签名和发布 Release 均须获得对应授权；当前没有发布自动化，普通 CI 不分发 APK。

1. 从最新 `main` 建发布任务分支，提交版本与安装序号、迁移说明、用户可读发布说明及必要的来源/许可更新，按正常 PR 检查与人工复核。
2. 维护者手动 Squash 发布 PR，记录最终 `main` 提交的完整 SHA，并核对主干 CI。构建与 tag 使用这个 SHA，不用可移动的分支名作为发布身份。
3. 从该 SHA 构建 unsigned 候选，按 P07 的 candidate/checks/artifacts 结构记录身份、命令和摘要。先完成工程检查、APK 静态检查及真实存在格式的迁移/拒绝测试。取得签名授权后另行签名，检查签名并重新执行 APK 静态检查、计算最终摘要；unsigned 与 signed 不能复用摘要。没有签名授权时完成发布准备即可，不执行设备安装升级或 release 冒烟。
4. 获得 tag 授权后创建附注 tag `v<versionName>`，指向上述 SHA，如 `v0.1.0-beta.1`、`v0.1.0`。`-dev` 不创建产品发布 tag；注释记录版本及发布说明定位，签名 tag 仅在签名身份和工具已经约定时使用。
5. 创建 Draft Release，使用已存在的 tag；核对解引用 SHA，上传已验收的同一批资产、校验和与说明。gh 创建时使用 `--verify-tag`，避免自动创建轻量 tag；候选标记 prerelease。
6. 复核版本、源码、附件、升级说明和发布授权后正式发布。启用 Immutable Releases；发布后 tag 与附件保持固定，补充资产须在草稿阶段完成。

`v*` tag 禁止更新与删除，不限制授权后的首次创建。错误 tag 不强推、不移动，使用新版本处理并保留必要说明。发布问题通过新版本修复或在说明中注明撤回建议，不删除或覆盖附件；修改说明文字不改变源码与产物身份。

## 最少发布记录

发布说明记录用户可观察的新功能、修复、不兼容变化与迁移、已知问题、设备/API/ABI 范围和限制。产物记录对应 PR、最终 SHA、tag、versionName/versionCode、签名身份、文件 SHA-256 和验收定位，不含私钥、凭据或设备序列号。

P00 运输清单与冻结证据保持原范围。应用发布资产使用自己的校验和与身份记录，不修改根 `SHA256SUMS` 来收纳新 APK；技术证据仍按 [验证规范](verification.md) 管理。

## 参考依据

核对日期：2026-10-14。

- [SemVer](https://semver.org/lang/zh-CN/)
- [Android 应用版本](https://developer.android.com/studio/publish/versioning)
- [Git 附注 tag](https://git-scm.com/book/en/v2/Git-Basics-Tagging)
- [Immutable Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
- [gh release create](https://cli.github.com/manual/gh_release_create)
