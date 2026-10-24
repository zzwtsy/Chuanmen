# P03 · 配置、独立本地元数据与序列化

实施依据：2026-10-23 计划。当前状态由 [路线图](ROADMAP.md) 统一维护；本文件保留实施契约，完成情况与工程命令见第 6 节。仅工程验收，不安排模拟器、真机或设备进程恢复测试。

## 1. 目标与已有基础

交付创建、编辑、加密保存、应用和读取恢复闭环。实施前基线已有 LocalProfileId、CoreInstanceId、MemoryProfileRepository、按配置隔离的草稿、双模式、改名及 Service 应用确认；草稿仅覆盖网络名称与 MTU，保存和外观均不落盘。复用这些行为及回归，不重建会话状态机。

前置：现行单 app 基线工程检查通过；读取总体设计、技术架构和配置台账。P03 不依赖被取消的结构精简设备补验。固定核心为 app/native/inputs.json 中的来源，不升级核心或工具链。

## 2. 字段范围与归属

首批 10 项：`network_identity.network_name`、`network_identity.network_secret`、`hostname`、`dhcp`、`ipv4`、`peer`、`flags.mtu`、`android.internetRouting`、`android.dnsServers`、`android.ipv6Policy`。基础标签固定三个主要输入：网络名称、网络密码、连接节点；地址方式显示摘要。本地配置名称在共享标题区。

peer 首批保留现有 TCP/UDP 节点 URL 输入，复合对象其他成员与协议扩展交给 P05-02；不能把首批 URL 编辑记成全部 peer 成员已交付。完整标签展示已接入字段、同页分组与搜索。Android 三项先承接现有 includeInternet、自定义 DNS 和 Block 行为；完整选项与应用名单在 P05-06 扩展。其他 34 项编辑器和运行接线归 P05，不能提前放置可操作的假能力。搜索与表单共用过滤条件。

新建默认自动 IPv4、MTU 1380、互联网路由关闭、IPv6 阻断；密码为空与缺省按固定核心语义区分，不能复用当前非空秘密断言作为完整规则。已有值和导入值不被新建默认覆盖。静态 IPv4 保留前缀语义；历史无前缀受控输入按原 /24 语义接管。

| 归属 | 内容 |
|---|---|
| lib/profile | 共享身份、已保存文档、元数据、仓库、加载/写入结果、加密存储 |
| features/configuration/lib | 原始输入、草稿、字段目录、校验投影和编辑动作 |
| features/configuration 与 features/settings | 表单/管理入口、同一策略摘要与外观 |
| lib/vpn 与 app/native | codec 边界、固定核心解析、运行投影、既有应用事务 |
| test 与 native tests | 对应功能/共享代码回归及合成 fixture；不创建应用样例 |

## 3. 公共接口与固定决策

### 存储与加载

采用 Android AtomicFile + 版本化 JSON + Android Keystore/AES-GCM，配置规模为本地配置列表，不引入数据库或通用存储框架。一个加密文档原子保存元数据、核心原始文档、Android 策略和明确的连接目标身份；全部写入后台串行执行。文件在 noBackupFilesDir；系统备份和设备迁移规则同时排除配置及日志。首个磁盘 schemaVersion 为 1，当前没有生产旧磁盘格式可迁移。完整存储 JSON 的 UTF-8 明文上限为 8 MiB，单项核心 TOML 上限为 128 KiB；超限返回 StoreTooLarge/DocumentTooLarge 并保持旧存储，不截断。解密读取也执行同一上限，密文封装额外只允许格式头、IV 和认证标签的固定开销。

AES-256 密钥由 Keystore 创建，普通存取不要求每次生物认证；每次写入使用新的 96 位随机 IV 和 128 位认证标签，格式版本作为认证附加数据。磁盘只写密文；持久化成功后才发布内存状态。AtomicFile 的中间备份同样只含密文。密钥缺失/失效、认证失败、损坏或未来 schema 均保留原件并进入读取失败状态，不自动删库或重建密钥覆盖旧文件。

JSON 存储包含存储版本与配置列表；每项包含稳定身份、本地可空名称、排序/时间、configRevision、来源版本、核心 TOML 及 Android 策略。只保存最新已保存修订，不建立无界历史。外观与最后查看 ID 属于非敏感 SharedPreferences，后台 commit 成功后发布状态；最后查看 ID 不作为自动连接目标。最后明确连接目标只在用户授权已经具备、连接请求已通过校验后写入；持久化成功才发送该次 Start，写失败则不启动。普通停止清除内存自动恢复意图但保留此目标供以后明确系统 Always-on 启动使用；删除目标时同一事务清空引用。切换查看、仅保存、应用和系统恢复不改该目标。

### 仓库与编辑结果

| 接口/状态 | 必须遵守的契约 |
|---|---|
| RepositoryState | Loading / Ready / ReadFailed；失败不投影成空配置列表 |
| profiles / findSaved | 读取最近成功提交的不可变内存快照；Service 不同步读磁盘 |
| suspend create | 新 UUID、新稳定核心身份、修订 1；提交成功才进入列表 |
| suspend save(id, expectedRevision, document, policy) | 串行核对基准修订；冲突返回 Conflict，不覆盖新值；语义未变不增加修订 |
| suspend rename | 只更新元数据；与保存串行，保存不得覆盖期间改名 |
| suspend delete | 仅对会话已确认不持有的目标提交；运行目标先由既有控制器停止，停止未知则不删除 |
| WriteResult | Success / ValidationError / Conflict / ReadUnavailable / WriteFailed / NotFound / RevisionExhausted / StoreTooLarge / DocumentTooLarge 分别表达，错误不携带秘密 |
| Draft | profileId、基准修订、rawInput、解析值、fieldErrors、模式及提交身份；一份草稿支撑两个标签 |

Application 从启动起持有同一个仓库实例；加载期间禁用依赖配置的动作，不再通过 setRepository 替换仓库。会话客户端仍唯一。删除期间串行阻止该目标的新连接/应用，停止确认后再次核对目标再删除；失败解除删除处理中状态且保留配置。删除当前查看项后选择排序首项，没有配置则显示创建入口。

### codec 与运行投影

CoreConfigCodec 提供 parse、patch、encodeForRuntime 和脱敏预览。原始 TOML 是保留未知内容的文档，字段投影只读；节点行使用内存身份，补丁按唯一源索引保留或替换完整节点，新节点显式插入，顺序由结果条目决定；编辑通过 patch 更新文档，不维护两套可写配置。Rust 复用锁定 TOML 工具和固定 EasyTier 解析器。保持缺省/显式空/空列表/显式 false；跨 JNI 的 u64 使用十进制字符串，不能经 Double。

解析和编码不启动 TUN 或会话；沿用唯一 .so。新增 JNI 动作与能力标识保持旧生命周期动作兼容；错误只返回安全字段 key、位置与错误码，不返回原始输入。平台托管参数只在运行投影调整，原件保留差异；未知或未接入项阻止直接运行，不静默删除。运行比较使用固定核心 getter 的有效值，源语义仍保留缺省和空值。非 secure 模式下固定核心将缺省密码与空密码解析为同一运行值（`easytier-core/src/config/toml.rs:TomlConfigLoader::new_from_config`），不改变源文档。所有运行变更继续受控重连，不新增热应用。

## 4. 分任务与实施顺序

按 P03-01 → 03 → 02 → 04 → 05 → 06 → 07 → 08 执行。先完成内存数据契约与 codec，再把可解析的文档落盘；存储加载不依赖尚未交付的解析接口。下文 Kotlin 路径相对 `app/src/main/java/com/github/zzwtsy/chuanmen`；测试位于 `app/src/test` 的镜像包。标注“新增”的类型或文件是交付要求，实施前尚不存在，实际交付位置见第 6 节。

### P03-01 · 分离本地元数据与核心参数

**开始条件**：现有 Profile、MemoryProfileRepository、ConfigurationEditor 及其测试已读取；现有身份与改名测试通过。

**修改位置**：`lib/profile/Profile.kt`、`MemoryProfileRepository.kt`、`features/configuration/lib/ConfigurationEditor.kt` 及直接消费这些接口的 ViewModel。

1. 保留 LocalProfileId、CoreInstanceId 的现有类型。创建配置时各生成一个新 UUID；保存和改名不得重新生成。导入生成身份的实现归 P05-07，本任务只提供身份创建接口。
2. 将 localDisplayName 放入独立元数据。输入首尾空白移除，空结果存 null；显示时 null 回退当前网络名称。同名配置合法，所有查找和命令都使用 ID。
3. 将仓库写操作改为第 3 节的 suspend 接口及 WriteResult；现有消费方逐一迁移，不能保留 Boolean/null 结果与新错误类型两套入口。同步迁移 ConfigurationViewModel、ConfigurationEditor 和 test/androidTest 的调用：ViewModel 在 viewModelScope 中发起，测试在现有协程测试作用域中等待结果，不能用 runBlocking 阻塞生产主线程。本任务仍使用现有 CoreConfig 值模型；P03-03 在统一 codec 交付时一次性切换到文档参数，不提前创建无法解码的 TOML 存储。
4. configRevision 初始为 1；本任务先按现有 CoreConfig/AndroidVpnPolicy 值比较，P03-03 切换为 TOML 文档后按解析值比较。核心配置值或 Android 策略发生变化才递增；改名、顺序、时间和仅 TOML 排版变化不递增。原文有变化仍需落盘，是否待应用由运行投影比较决定，不能仅比较修订号。
5. `save` 只更新文档/策略，合并当前元数据；不得使用提交时捕获的旧名称覆盖并发改名。

**失败处理**：目标 ID 不存在返回 NotFound；保存试图更换 CoreInstanceId 返回 ValidationError；expectedRevision 不匹配返回 Conflict，不修改任何值。修订溢出返回 RevisionExhausted，不绕回 1。

**完成断言**：A 运行时改名 B，B 名称变化但修订不变且会话命令数为 0；随后保存 B 不恢复旧名称；同名 A/B 各自编辑互不覆盖；相同值重复保存不递增修订；更换身份和过期修订均保留旧数据。JVM 相关回归通过且 androidTest Kotlin 源码可编译；有未迁移调用时不能结束本任务。

### P03-02 · 实现版本化存储

**开始条件**：P03-01 的异步仓库结果与 P03-03 的文档模型、parse/patch/encodeForRuntime 已交付；加载端可以由原始 TOML 重建只读投影，相关测试通过。

**修改位置**：在 `lib/profile` 新增持久化仓库、文档序列化与 Keystore 加密适配；修改 `ChuanmenApplication.kt`、`AppAssembly.kt`、`lib/AppearancePreferences.kt`、`res/xml/backup_rules.xml` 和 `data_extraction_rules.xml`。

1. 定义 schemaVersion=1 的存储结构，包含配置列表、顺序和最后明确连接目标 ID。每个配置包含两种身份、localDisplayName、configRevision、来源 commit、原始 TOML 和 Android 策略。时间使用 UTC epoch 毫秒，列表按持久化顺序展示，创建追加末尾。
2. 将完整文档序列化为 UTF-8 后用第 3 节的 AES-GCM 参数加密，再交给 AtomicFile。写操作在同一后台串行队列中完成；保存、改名、删除和连接目标更新都必须经过该队列。
3. 首次启动没有配置文件时创建空仓库；存在密文时必须使用原密钥解密，不能因密钥缺失自动生成新密钥覆盖。只有完全读取、校验通过后才发布 Ready。
4. `startWrite → 写入 → finishWrite` 成功后发布新内存快照；任何步骤失败调用回滚路径并返回 WriteFailed。启动读取使用 AtomicFile 恢复规则，不自行忽略备份文件。
5. 外观偏好写入 SharedPreferences，在后台 commit 返回 true 后发布新值；失败维持原值并报告。配置和日志从旧备份规则及 Android 12+ 设备传输规则同时排除。
6. Application 只创建一个仓库并异步加载；Service 与所有 ViewModel 持有同一实例。同一步修改 ConfigurationInputFactory：只返回校验后的创建参数，不再构造 MemoryProfileRepository；创建表单提交动作给配置 ViewModel，由 ViewModel 调用这个已装配仓库的 suspend create，持久化成功后通知选择新 profileId。AppAssembly 按 Loading/ReadFailed/Ready 分支展示，移除 setRepository；现有输入控件先保留，P03-05 再统一为双标签表单。
7. MemoryProfileRepository 移至 test。androidTest 不再构造或替换 Application 仓库；它等待正式仓库 Ready，再通过正常 create/save 接口装入合成配置并取得生成的 ID。同一步迁移全部 setRepository 调用，不保留仅用于设备测试的生产替换入口；只编译这些测试，不安装或执行。

**失败处理**：读取失败进入 ReadFailed，保留原文件和安全错误码。页面提供“重试读取”，不得自动清空、新建示例或展示空列表。写失败保留旧快照与草稿；失败后队列仍能处理下一次显式重试。

**完成断言**：保存修订 1 后重新实例化仓库仍读到修订 1；写修订 2 时在打开、写入和提交三个位置分别注入故障，重开仓库仍读到完整旧版；篡改密文、缺密钥、未来 schema 都返回 ReadFailed 且原件字节不变；两个并发写按队列次序提交；生产装配的仓库对象身份一致。Keystore 实际设备行为不在本轮验证。

### P03-03 · 建立配置目录与转换层

**开始条件**：P03-01 已提供稳定身份、独立元数据及异步写入结果；本任务在持久化之前执行，仍使用唯一的现有内存仓库装配。

**修改位置**：`features/configuration/lib` 新增字段目录；`lib/vpn/CoreConfigEncoder.kt` 迁移至共享 CoreConfigCodec 消费路径；`app/native` 新增 parse/patch/runtime 编码动作及宿主测试。

1. 定义文档值对象，原始 TOML 是可保存源，CoreConfig 是只读解析投影；仓库 save 改接文档参数并同步全部调用，不保留两套可写配置入口。当前受控创建参数通过统一 codec 生成文档，原有 CoreConfigEncoder 由该路径承接。为第 2 节的 10 项建立稳定 key、中文标签、所属组、输入类型、缺省/空值语义和运行消费者。每项记录固定核心源码路径与符号；Android 策略记录 TunPlatform 消费位置。
2. 在台账补齐这 10 项的来源和成员映射。遇到核心与快照默认值不同，保留快照，另写 Android 新建默认和转换规则；不能直接改快照数值。
3. parse 返回原始文档及只读字段投影；patch 仅修改指定 key；encodeForRuntime 根据保存文档生成运行配置。解析动作不创建核心实例、runtime 会话或 TUN。
4. 用固定 EasyTier 解析器校验生成配置；保留 unknown、missing、empty、false 的区别。未知项和未接入项可以保存，但 encodeForRuntime 返回 UnsupportedField，列出 key，不透传运行。
5. secret 输入和错误对象不得调用普通字符串输出；错误只返回 key、位置和代码。JNI JSON 编码后的 UTF-8 字节数不得超过 256 KiB，超限在发起 JNI 前返回 RequestTooLarge。
6. 外部 TUN、runtime 等平台托管字段沿用现行宿主要求的值，只覆盖运行投影；在内部预览中列出覆盖的 key，原文不改。P05-01 复审全部隐藏项，本任务不把尚未调查的隐藏项直接启用。

**失败处理**：TOML 语法错保留 rawInput 并标位置，不能提交为已保存配置；未知字段是可保存但不可运行的语义状态，不与语法错合并。当前要求非空密码的旧断言只有在固定解析器证实允许空值后才调整，同时保留空与缺省差异。

**完成断言**：10 项逐项修改后固定核心能解析；修改 hostname 不改变未知键；缺省、空字符串、空列表和 false 往返保持；u64 最大值用十进制文本无损通过 JNI；JSON 转义后超限的输入被拒绝；任何解析动作的会话创建计数为 0。

### P03-04 · 落实统一草稿和校验

**开始条件**：P03-03 的 parse、patch、encodeForRuntime 可调用，P03-02 已把同一文档模型接入持久化仓库及创建入口；错误类型稳定。

**修改位置**：`features/configuration/lib/ConfigurationEditor.kt`、`ConfigurationViewModel.kt`、`ConfigurationUiState.kt`。

1. 每个 profileId 只保留一份草稿，包含 baseRevision、rawInput、fieldErrors、模式、草稿代次和递增 editSequence。基础/完整标签、搜索和设置入口引用同一对象。
2. 保存时生成独立 SaveToken，捕获 profileId、baseRevision、草稿代次、editSequence 和文档快照；每个配置最多一个保存请求在途。等待期间允许编辑，但禁用再次保存。
3. 回执成功后更新 saved 与 baseRevision；只有回执令牌匹配、草稿代次及 editSequence 等于提交值时才清除 dirty。等待期间新增的输入、错误和模式不被回执覆盖。
4. Conflict 显示“重新载入已保存版本”和“保留当前草稿”。前者需用户确认后丢弃草稿；后者不自动改 baseRevision，也不重试覆盖。用户可切页继续查看，直到显式重新载入后再编辑保存；本阶段不实现自动合并或强制覆盖。
5. 创建及编辑草稿均通过现有 Activity owner 的 ViewModel 保留；保存中拒绝丢弃和重载，新建首次提交锁定表单；进程重新创建从 saved 建新草稿。秘密不写 SavedStateHandle、Bundle 或磁盘草稿。

**失败处理**：校验失败不发仓库请求；写失败保持 dirty 和原输入；已删除配置的迟到回执被丢弃，不恢复列表项；切换查看配置不取消其他配置已发出的保存，也不把回执展示给错误目标。

**完成断言**：提交 X 后编辑 Y，收到 X 成功时 saved=X、draft=Y；提交期间改名不被覆盖；切到 B 后收到 A 回执只更新 A；Conflict 两个动作分别满足上述规则；非法 MTU 在切标签/旋转后原文及错误均保留。

### P03-05 · 实现基础 / 完整配置页面

**开始条件**：P03-04 已区分保存中、dirty、校验错误与 Conflict。

**修改位置**：`features/configuration` 的 Screen/UiState/ViewModel、`features/configuration/ConfigurationEntry.kt`、根导航和配置选择动作。

1. 用正常创建页替代当前受控输入入口。用户填写基础配置后显式保存才写入仓库；取消创建不留下空配置。新建操作不连接 VPN。
2. 基础标签只提供网络名称、网络密码、连接节点三个主要输入，地址方式显示摘要；本地名称和配置选择位于两标签共享标题。完整标签展示本阶段 10 项，按目录组排序。
3. 搜索同时匹配中文标签与 key，不区分英文大小写，去掉搜索词首尾空白；搜索只过滤显示，不修改草稿。清空搜索恢复原分组与错误。
4. 改名对话框取消不写入；保存成功再更新列表。删除前显示目标名称和正在运行状态，必须用户确认。
5. 删除运行目标时通过会话协调器锁定该目标的新连接/应用，发送停止并等待确认；确认释放后原子删除，失败解除锁定但不删除。删除非运行目标不停止其他配置。
6. 删除当前查看项后选择剩余排序首项；列表为空显示创建入口。删除最后连接目标时在同一存储事务清除该目标引用。

**失败处理**：停止结果未知时显示“尚未确认停止，未删除”，允许重试查询或取消删除；不能靠等待超时删除。创建/改名/删除磁盘失败保留原列表。

**完成断言**：同名 A/B 按 ID 区分；新建取消后列表数量不变；删除运行 A 的磁盘删除发生在停止确认之后；停止失败时 A 仍存在；删除 B 不发 A 的停止命令；搜索/切标签不重置静态地址和错误输入。

### P03-06 · 实现保存与应用状态

**开始条件**：P03-05 的创建、选择、删除和表单已接同一仓库。

**修改位置**：`ConfigurationEditor.kt`、`ConfigurationUiState.kt`、`ConfigurationViewModel.kt` 与 `lib/vpn/VpnSessionClient.kt` 的调用处，不在 UI 新建应用事务。

1. “保存”只持久化；没有活动会话时保存成功不显示待应用。A 运行而保存 B 时也不显示 B 的待应用。
2. A 运行且 saved A 的运行投影与 AppliedConfig 不同时显示待应用；改名、排版和修订号变化本身不触发。dirty 未保存时禁用应用，先由用户保存。
3. “应用”捕获确定的 saved 修订并交给现有 Service 受控重连事务；应用期间继续编辑产生新草稿，但不改变已发出的修订。
4. 成功回执只更新它实际应用的修订；若期间又保存新版本，重新比较并继续显示待应用。失败保留 saved、名称和新草稿。
5. 结果未知时保留查询/显式停止入口，禁用重复应用；旧快照仅作为历史，不显示为已恢复成功。恢复上次运行版本必须用户显式操作，并使用该操作的回执确认。

**失败处理**：仓库保存失败不发送应用命令；未知回执不能把 UI 改成 Idle 或 Running；迟到的旧操作结果按现有 session/token 拒绝。

**完成断言**：仅保存时 Service 命令数为 0；保存失败后应用命令数为 0；应用修订 2 期间保存修订 3，回执后 applied=2、saved=3；应用失败/结果未知均不丢草稿，恢复成功必须来自新回执。

### P03-07 · 放置当前网络的流量范围

**开始条件**：P03-06 的保存/应用状态已完成，设置能取得当前查看 profileId。

**修改位置**：`features/settings`、配置页 Android 分组、根导航参数。

1. 设置中的网络策略摘要只读取当前查看配置的 saved；摘要旁提供编辑入口，进入同一 profileId 的草稿并定位 Android 分组，不另建设置仓库。
2. P03 只编辑现有互联网路由开关、自定义 DNS 地址列表；IPv6 显示固定 Block。全量应用清单、system DNS、bypass/tunnel 与自动重连归 P05-06。
3. 进入已有 dirty 草稿时保留输入；返回设置不自动保存。设置摘要继续反映 saved，未保存提示来自同一草稿。
4. 首页不新增范围切换控件。查看目标不存在时显示创建/选择入口，不能编辑实际运行配置作为替代。

**失败处理**：导航期间配置被删除，关闭对应编辑页并显示目标不存在；不退回到另一配置的草稿执行操作。

**完成断言**：A 运行、查看 B，摘要与编辑均属于 B；修改后返回不改变 saved；保存 B 不改变 A 的 TUN 参数；无配置时不会调用仓库写入。

### P03-08 · 序列化与跨版本保护

**开始条件**：P03-01 至 P03-07 的完成断言全部通过。

**修改位置**：P03 新增存储/codec 的测试、Application 正式装配、技术架构的存储契约和能力台账的 10 项工程证据。

1. 为每个首批字段保存合成输入、预期 TOML/Android 策略及固定核心解析结果的黄金样例；含秘密样例只使用专门构造的测试字符串。
2. 当前只有 schema 1，不实现虚构 schema 0 迁移。覆盖 schema 1 读取、未来 schema 拒绝、结构缺失和密文损坏；以后确有旧发布格式时才新增迁移分支。
3. 静态复核 P03-02 已完成的装配迁移：main 不再调用临时内存初始化或 setRepository，配置新建、加载、编辑、连接均经过同一仓库；test/androidTest 没有旧同步接口或仓库替换调用。此处只复核，不把前面任务应同步完成的调用迁移延后；androidTest 仍只编译。
4. 运行本阶段完整工程检查组合；在本文件登记实现 SHA、10 项对应测试位置和命令结果，再更新台账工程状态。

**失败处理**：有字段未接生产消费者、黄金样例失败或工程命令未执行时，保持该任务及阶段未完成，不以“计划已写”关闭。

**完成断言**：重新创建仓库仅恢复 saved；未知字段原文保留且未经接入不能运行；10 项各有编辑、codec、生产消费和错误回归；检查组合全部成功。提交记录中不包含真实密码或新设备结论。

## 5. 阶段验收与交接

在本文件追加实施记录表，列 P03-01 至 P03-08、源码 SHA、实现路径/符号、测试路径/用例、实际命令及结果。没有完整证据的一行保持未完成；台账的首批状态按 completionTasks 核对，扩展状态不提前修改。

运行 [工程检查组合](../development/setup.md#工程阶段检查组合)。重点矩阵为：原子提交/失败、身份/修订、三种状态、草稿并发、多配置隔离、秘密边界、原始文档保留与 fixed-core codec。普通进程内/仓库实例重建测试不记成设备进程恢复。

交给 P04 的接口为稳定仓库、查看身份、既有 SessionState 和正式数据来源；交给 P05 的接口为字段目录、统一草稿、codec、保存/应用与策略入口。10 项首批完成必须具备工程结果定位；nativeStatus 不提升为设备验证通过。

P03 不实现剩余高级编辑器、用户级导入导出、跨进程草稿恢复或热应用。阶段退出为 verified（工程范围），设备运行未验证。

## 6. 实施记录（2026-10-24）

本轮从主干 `9a54bb73d3a7ae58fcbb738cb789565cc6fe633a` 建立 `feat/p03-configuration`。实现位于未提交工作区，已通过本节列出的工程验收；本节记录实现，不覆盖冻结的 P02 设备记录。每条命令的开始/结束时间、退出码、源码基线及工作区摘要写入忽略目录 `app/build/p03/<检查名>.json`，完整日志为同名 `.log`；这些是本机构建记录，不是新的设备证据包。

下表 Kotlin 路径相对 `app/src/main/java/com/github/zzwtsy/chuanmen`，测试位于 `app/src/test/java/com/github/zzwtsy/chuanmen` 的镜像目录。

| 任务 | 实现路径与符号 | 回归位置与可观察结果 | 状态 |
|---|---|---|---|
| P03-01 | `lib/profile/Profile.kt`：LocalProfileMetadata、SavedRevision、WriteResult；PersistentProfileRepository 的 save/rename | ProfileContractTest、PersistentProfileRepositoryTest：身份不变、相同值不增修订、CAS 冲突/溢出拒绝、并发写与改名合并 | 工程检查通过 |
| P03-03 | CoreConfigDocument；NativeCoreConfigCodec；`app/native/src/config_codec.rs`；ConfigurationFields | NativeCoreConfigCodecTest：JNI 请求大小与差异；Rust 黄金矩阵：7 项核心字段、u64 上界、unknown/empty/false/日期、peer 成员保留/重排、URL 运行范围、无会话创建及运行覆盖 | 工程检查通过 |
| P03-02 | PersistentProfileRepository、ProfileDocumentCodec、EncryptedProfileFile；Application 唯一装配；AppearancePreferences | PersistentProfileRepositoryTest：重开、schema 1/未来 schema、缺密钥/篡改、三处原子写故障、大小限制与目标引用；PreferencesPersistenceTest：正式 SharedPreferences 重开和 commit 失败不发布；androidTest 通过正式 create/save 装入合成配置 | 工程检查通过 |
| P03-04 | ConfigurationEditor 的 editSequence、提交快照、saving/conflict；ConfigurationViewModel | P03ConfigurationTest：A 在途保存、继续编辑及切 B，晚回执只更新 A；重复保存拒绝；冲突不换基准，显式重载才丢弃；删除后不复活草稿 | 工程检查通过 |
| P03-05 | ConfigurationEntry、ConfigurationForm、ConfigurationScreen；共享配置选择区；Service deleteProfile 删除锁 | NavigationUiTest：新建取消不写、搜索及双标签保留原文；VpnSessionCoordinatorTest：停止/PFD/JNI 确认后才删除，未知保留，删 B 不停止 A | 工程检查通过 |
| P03-06 | 文档 sameRuntimeValues；既有 apply/restoreRunning 事务；ConfigurationUiState | ConfigurationUiStateTest、ConfigurationBehaviorTest、NavigationBehaviorTest、P02 故障重放：保存不连接、运行/保存/草稿分开、应用捕获旧修订、恢复必须来自 Service 回执，未知有查询/停止入口 | 工程检查通过 |
| P03-07 | SettingsRoute → 同一 ConfigurationViewModel 的 Android 分组；策略摘要只读 saved | NavigationUiTest.settingsPolicyEntry_targetsViewedDraftAndDoesNotSaveOnReturn：A 运行、查看 B，编辑及摘要属于 B，返回不保存，再进入保留 dirty | 工程检查通过 |
| P03-08 | `profiles/schema-1.json`、`profiles/p03-fields.json`；能力台账 sourceMapping/成员范围；技术架构 | P03ConfigurationTest.firstBatch_fieldsRoundTripThroughEditor 与 Rust first_batch_golden_fields_parse_without_session_creation 共同覆盖首批 10 项；完整检查组合见下表 | 工程检查通过 |

### 首批字段与来源

| key | 来源符号 / 转换 | 工程消费者与样例 |
|---|---|---|
| network_identity.network_name | `easytier-core/src/config/toml.rs:NetworkIdentity.network_name` | 文档 patch → 固定解析 → 受控重连；黄金样例同 key |
| network_identity.network_secret | `NetworkIdentity.network_secret`；缺省与显式空字符串分别保留 | 加密文档、遮罩输入、运行编码；黄金样例使用显式空和专门合成秘密 |
| hostname | `Config.hostname / ConfigLoader::get_hostname` | 完整表单 → patch → 固定解析 |
| dhcp | `Config.dhcp / ConfigLoader::get_dhcp`；仅新建默认 true | 地址组 → 固定核心实际地址，不伪造分配结果 |
| ipv4 | `Config.ipv4 / ConfigLoader::get_ipv4` | 首批支持 /0–32；宿主补丁保留显式前缀，裸地址沿用 /24 |
| peer | `PeerConfig.uri`；首批仅 TCP/UDP URL，保留原节点其他成员 | 完整/基础共用列表；其他成员/协议可以保留但阻止运行，扩展仍归 P05-02 |
| flags.mtu | `easytier-proto/proto/common.proto:Flags.mtu`，新建 1380 | codec 校验与 TunPlatform.interfaceSpec；黄金 1400 |
| android.internetRouting | AndroidVpnPolicy.includeInternet，新建 false | TunPlatform.interfaceSpec 的默认路由；设置编辑同一草稿 |
| android.dnsServers | AndroidVpnPolicy.dnsServers，仅自定义 IPv4 | interfaceSpec 的 DNS 主机路由与 NativeVpnService.establish 的 Builder.addDnsServer |
| android.ipv6Policy | Ipv6Policy.Block，固定值 | Service 不添加 IPv6 family/地址/路由；无 P05 扩展选项 |

固定来源仍为 `7592951b808c4839cb9d77cc4bc642f124915951`，工具链未升级；正式补丁身份更新为 `p03-config-host-v1`。`ConfigLoader::get_ipv4` 原行为把显式 /32 也解释为 /24，本轮宿主适配将其与裸地址区分；原文件与后像摘要在 app/native/inputs.json，针对性断言为 Rust `explicit_ipv4_prefix_is_preserved_by_host_adapter`。新产物不继承 P02 真机结论。

### 工程命令

以 [开发环境](../development/setup.md#工程阶段检查组合) 为命令定义。以下命令均已实际执行且退出 0，结果只适用于工程范围。当前 androidTest 仅编译，未安装或执行；Keystore 实际设备行为、真实网络效果、设备进程恢复及 16 KB runtime 均未验证，本轮不安排设备执行。

源码基线为上文主干 SHA，加本轮未提交实现；没有新的实现 commit。代码文件清单及各文件摘要见 `app/build/p03/implementation-manifest.json`，本次实现集合 SHA-256 为 `92c47c3399a85fef2d70f9bb49dff7704114f5bc720bb29167a719fdd0b25ae0`。该集合涵盖 app/、gradle/、scripts/ 及根构建文件，排除 Markdown；最终检查聚合在 `app/build/p03/checks.json`。每条 JSON 的 workspaceSha256 还包含执行当时的文档字节，因此文档登记期间摘要可不同，不将基线 SHA 冒充实现提交。

JVM/Compose 155 项、Rust bridge 18 项、Rust peer 宿主 4 项、Python 四组共 65 项均通过，无跳过。debug、release（未签名）和 androidTest APK 均构建成功；debug/release 双 ABI、JNI 与 16 KB 静态对齐检查通过。后者只验证包和 ELF 布局，不证明设备 16 KB runtime。

下表时间均为 2026-10-24 UTC，开始/结束精确值与源码工作区摘要见同名 JSON；退出码全部为 0。SDK_ROOT 是本机 SDK 路径的脱敏占位，实际命令参数仅保留在忽略的本地 JSON。

| 命令 | 开始 → 结束（UTC） | 退出码 | 本地日志 |
|---|---|---|---|
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff check scripts` | 12:05:23 → 12:05:25 | 0 | `app/build/p03/ruff-lint.log` |
| `uv tool run --isolated --no-python-downloads --from ruff==0.17.0 ruff format --check scripts` | 12:05:20 → 12:05:24 | 0 | `app/build/p03/ruff-format.log` |
| `python3 scripts/test_check_privacy.py` | 12:04:57 → 12:04:57 | 0 | `app/build/p03/privacy-tests.log` |
| `python3 -m unittest discover -s scripts/catalog -p 'test_*.py'` | 12:04:57 → 12:04:59 | 0 | `app/build/p03/catalog-tests.log` |
| `python3 -m unittest discover -s scripts/native -p 'test_*.py'` | 12:19:16 → 12:19:17 | 0 | `app/build/p03/native-python-final.log` |
| `python3 -m unittest discover -s scripts/android -p 'test_*.py'` | 12:04:57 → 12:05:01 | 0 | `app/build/p03/android-python-tests.log` |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease --offline` | 12:20:52 → 12:21:38 | 0 | `app/build/p03/jvm-lint-delivery.log` |
| `cargo +1.99.0 test --manifest-path app/native/Cargo.toml --locked --offline --target-dir app/build/native/target` | 12:29:54 → 12:29:58 | 0 | `app/build/p03/native-delivery.log` |
| `cargo +1.99.0 test --manifest-path scripts/android/native-peer/Cargo.toml --locked --offline --target-dir app/build/device-tests/peer-target` | 12:14:17 → 12:14:35 | 0 | `app/build/p03/peer-native-final.log` |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline` | 12:30:22 → 12:31:57 | 0 | `app/build/p03/apk-delivery.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/debug/app-debug.apk` | 12:32:12 → 12:32:12 | 0 | `app/build/p03/apk-debug-alignment.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/release/app-release-unsigned.apk` | 12:32:12 → 12:32:12 | 0 | `app/build/p03/apk-release-alignment.log` |
| `python3 scripts/native/build_native.py verify-artifacts` | 12:32:12 → 12:32:12 | 0 | `app/build/p03/native-artifacts.log` |
| `cargo +1.99.0 fmt --manifest-path app/native/Cargo.toml --check` | 12:32:12 → 12:32:12 | 0 | `app/build/p03/rust-format.log` |
| `git diff --check` | 12:32:27 → 12:32:28 | 0 | `app/build/p03/diff-format.log` |
| `python3 scripts/catalog/audit_config_catalog.py` | 12:32:27 → 12:32:27 | 0 | `app/build/p03/catalog-delivery.log` |
| `python3 scripts/check_privacy.py` | 12:32:27 → 12:32:27 | 0 | `app/build/p03/privacy-delivery.log` |

交接边界：P04–P07 仍为 planned；配置目录冻结快照、docs/verification、handoff.json 和 SHA256SUMS 未改。Keystore 真设备行为、设备进程恢复、Android 网络效果、设备兼容性与 release 冒烟未验证，本轮不安排补跑。工程回归中的仓库重开、假核心和故障注入只证明相应模型及调用契约。

## 7. 复审修复记录（2026-10-24）

本节接续第 6 节，保留此前检查结果的原范围。修复基于 `feat/p03-configuration` 的现有未提交实现，Git HEAD 为 `9a54bb73d3a7ae58fcbb738cb789565cc6fe633a`；未把该基线 SHA 当作本次实现提交。最终源码摘要、命令、时间、退出码和日志在 `app/build/p03-review-fixes`，阶段状态由路线图维护。

### 实现与分项断言

| 任务 | 实现与接口 | 正式回归及可观察结果 |
|---|---|---|
| P03-01 | Application 注入 `CoreConfigCodec`，仓库接口不暴露 codec；唯一仓库/客户端不变 | 全部生产及 test/androidTest 消费方编译；测试 codec 只在测试装配 |
| P03-03 | `RawConfigurationInput` / `ConfigurationInputFactory` 共用校验；`FieldInputType`、显隐和启用规则供表单使用 | `ConfigurationInputTest.sharedFactory_*`、`typedFieldPolicies_*`、首批黄金样例 |
| P03-03 | `PeerRowId` 与显式 `PeerPatchEntry` 保留原节点完整对象；配置 codec v2，既有会话协议 1 | Rust 节点增改、重复 URI、非法索引；`NativeCoreConfigCodecHostTest` 跨真实 JNI 保留嵌套 false/u64，未知项仍阻止运行 |
| P03-02 | `ProfileKeyAccess` 区分首次初始化及原密钥访问；KeyUnavailable 阻止写入并保留最后快照 | `keyLostAfterLoad_*`、`backupOnlyStore_*`、`interruptedFirstWrite_*`；使用 JCE/密钥提供者接缝，不宣称 Android Keystore 实测 |
| P03-02 | `PreparedProfileCommit` 编码与快照同源，成功落盘后发布，加载才调用全库 decode | `candidateCommit_*` 改名/目标更新零解析，重开按配置数解析；`preparedCommit_*` 验证树/字节复制；原子失败及大小限制回归保留 |
| P03-04 | 编辑器仅归约状态，ViewModel 唯一保存 I/O；SaveToken、DraftEpoch、序号分别识别请求和输入 | `discardDuringSave_*`、`editSequence_*`、`peerOrigins_*`、`cancelledSave_*`；仅保存不发送会话命令 |
| P03-05 | 空库及非空库共用 Activity 配置 ViewModel；创建路由归根，表单只提交动作 | `emptyStoreDraft_*`、`nonEmptyStoreCreationRoute_*` 保留 Activity 重建输入；`creationSubmission_*` 验证锁定、失败重试和成功结果消费 |
| P03-06 | UI 与 Service 继续共用 `sameRuntimeValues`，按固定核心有效值判断待应用 | 宿主 JNI 验证缺省/显式 MTU 1380 不待应用、1400 有差异；已有保存/应用修订隔离及未知回执测试保留 |
| P03-07 | 设置继续读取查看对象的 saved，入口引用同一草稿，不增加策略能力 | Settings、Navigation、配置行为测试；查看 B 不改变运行 A，返回不隐式保存 |
| P03-08 | 独立 `testHostNativeCodec` 加入 CI 和工程检查组合，普通 JVM/lint 不依赖 Rust | JVM/Compose、宿主 Rust/JNI、Python、lint、双 variant APK、androidTest 编译和 APK 静态检查 |

### 配置目录差异与边界

| key | 差异 | Android 决定 | 影响 | 证据 | 下一步 |
|---|---|---|---|---|---|
| peer | 原整段文本无法识别同时替换/新增及重复 URI | 逐行身份与明确源索引命令 | 未知成员随原节点保留，未接入成员仍不可运行 | Rust 与真实 JNI 回归 | 成员/协议扩展仍归 P05-02 |
| flags.mtu | 原运行比较把缺省与显式默认区分 | 按固定核心 `get_flags()` 比较 | 源修订可不同，运行等价不产生待应用 | `resolvedDefaults_doNotCreatePendingApplication` | 设备效果未验证 |
| network_identity.network_secret | 源缺省与空值仍区分；固定核心非 secure 模式运行值相同 | 保存保留源存在性，运行按核心 getter 比较 | 不产生无效重连；secure mode 仍未接入 | 核心 `new_from_config`、Rust/JNI 源与运行双断言 | 不扩大安全模式范围 |
| 首批其余字段 | 创建/编辑及控件规则曾重复 | 共享输入工厂与类型化目录 | 双模式和搜索只过滤显示，隐藏不删除值 | 首批黄金样例及 UI/状态回归 | 其他 34 项仍归 P05 |

目录审计只证明冻结目录内部一致。本次不比较官网部署，不更新冻结快照，不提高 nativeStatus，不执行设备或 release 冒烟。

### 工程检查结果

本次验收的实现摘要为 `86131ca14a4094efdf5ccbf5456abde3714924b815d7aad5786aa85fbef4e37c`；清单位于 `app/build/p03-review-fixes/86131ca14a4094efdf5ccbf5456abde3714924b815d7aad5786aa85fbef4e37c.implementation-files.json`，覆盖候选文件中的 `app/`、`scripts/`、`gradle/`、`.github/` 及根构建配置。所有下列检查的完整工作区摘要均为 `a3f3569c45b7c8708730d8c97fb15e56ae6f7d3ef9a600f170102e2059ed6e54`；结果回填只修改文档及能力台账，最终工作区摘要另记本地 `summary.json`。

- JVM/Compose：171 项通过，无失败、错误或跳过；真实宿主 JNI：5 项通过。
- 核心 Rust：18 项单元、1 项 runtime、2 项 TUN 契约测试通过；受控对端仅运行 4 项宿主单元测试，没有启动网络实验。
- Python：隐私 6 项、目录 10 项、原生工具 9 项、Android 工具 40 项，共 65 项通过；固定 Ruff lint/格式、隐私和冻结目录内部一致性检查通过。
- debug/release lint、debug/release APK 和 androidTest APK 编译通过；正式双 ABI 库字节校验、实际 ELF LOAD 段及 APK ZIP 的 16 KB 静态对齐通过。

下表时间均为 2026-10-24 UTC，退出码全部为 0。每条命令的完整参数、精确时间、基线 HEAD、两种源码摘要和日志路径在同名 JSON；`SDK_ROOT` 为本机 SDK 路径的脱敏占位。汇总及 APK/双 ABI 库 SHA-256 在 `app/build/p03-review-fixes/summary.json`，这些普通工程产物保持忽略，不发布或冻结为新的实验包。

| 命令 | 开始 → 结束（UTC） | 退出码 | 日志 |
|---|---|---|---|
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:testHostNativeCodec --offline --console=plain` | 14:05:38 → 14:06:08 | 0 | `app/build/p03-review-fixes/engineering-final.log` |
| `cargo +1.99.0 test --manifest-path app/native/Cargo.toml --locked --offline --target-dir app/build/native/target` | 14:08:04 → 14:08:06 | 0 | `app/build/p03-review-fixes/rust-final.log` |
| `cargo +1.99.0 test --manifest-path scripts/android/native-peer/Cargo.toml --locked --offline --target-dir app/build/device-tests/peer-target` | 14:08:10 → 14:08:10 | 0 | `app/build/p03-review-fixes/peer-rust-final.log` |
| `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain` | 14:11:06 → 14:12:55 | 0 | `app/build/p03-review-fixes/assemble-final.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/debug/app-debug.apk` | 14:13:15 → 14:13:16 | 0 | `app/build/p03-review-fixes/apk-debug-final.log` |
| `python3 scripts/native/check_apk.py --sdk $SDK_ROOT app/build/outputs/apk/release/app-release-unsigned.apk` | 14:13:15 → 14:13:16 | 0 | `app/build/p03-review-fixes/apk-release-final.log` |
| `python3 scripts/native/build_native.py verify-artifacts` | 14:13:15 → 14:13:16 | 0 | `app/build/p03-review-fixes/native-artifacts-final.log` |
| `uv tool run --offline --isolated --no-python-downloads --from ruff==0.17.0 ruff check scripts` | 14:08:11 → 14:08:11 | 0 | `app/build/p03-review-fixes/final-ruff.log` |
| `uv tool run --offline --isolated --no-python-downloads --from ruff==0.17.0 ruff format --check scripts` | 14:08:11 → 14:08:11 | 0 | `app/build/p03-review-fixes/final-ruff-format.log` |
| `python3 scripts/check_privacy.py` | 14:08:11 → 14:08:11 | 0 | `app/build/p03-review-fixes/final-privacy.log` |
| `python3 scripts/catalog/audit_config_catalog.py` | 14:08:11 → 14:08:11 | 0 | `app/build/p03-review-fixes/final-catalog.log` |
| `python3 scripts/test_check_privacy.py` | 14:08:11 → 14:08:12 | 0 | `app/build/p03-review-fixes/final-python-privacy.log` |
| `python3 -m unittest discover -s scripts/catalog -p 'test_*.py'` | 14:08:12 → 14:08:12 | 0 | `app/build/p03-review-fixes/final-python-catalog.log` |
| `python3 -m unittest discover -s scripts/native -p 'test_*.py'` | 14:08:12 → 14:08:12 | 0 | `app/build/p03-review-fixes/final-python-native.log` |
| `python3 -m unittest discover -s scripts/android -p 'test_*.py'` | 14:08:12 → 14:08:15 | 0 | `app/build/p03-review-fixes/final-python-android.log` |
| `cargo +1.99.0 fmt --manifest-path app/native/Cargo.toml --check` | 14:08:15 → 14:08:16 | 0 | `app/build/p03-review-fixes/final-rust-format.log` |
| `git diff --check main` | 14:08:16 → 14:08:16 | 0 | `app/build/p03-review-fixes/final-diff-check.log` |

保存中编辑、重复提交、回执过期、密钥不可用、原子写入中断与未知节点成员均有正式回归。Activity 重建测试只证明同一 Activity owner 的 ViewModel 保留草稿，不声称真实进程恢复；JCE/提供者接缝不声称 Android Keystore 实测。设备、模拟器、release 冒烟、16 KB runtime 及真实网络效果均未执行或验证；原冻结文件保持原字节，nativeStatus 不变。
