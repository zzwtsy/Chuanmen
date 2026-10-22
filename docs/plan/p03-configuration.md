# P03 · 配置、独立本地元数据与序列化

实施依据：2026-10-23 计划。当前状态由 [路线图](ROADMAP.md) 统一维护；本文件描述待实施任务。仅工程验收，不安排模拟器、真机或设备进程恢复测试。

## 1. 目标与已有基础

交付创建、编辑、加密保存、应用和读取恢复闭环。当前已有 LocalProfileId、CoreInstanceId、MemoryProfileRepository、按配置隔离的草稿、双模式、改名及 Service 应用确认；草稿仅覆盖网络名称与 MTU，保存和外观均不落盘。复用这些行为及回归，不重建会话状态机。

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

CoreConfigCodec 提供 parse、patch、encodeForRuntime 和脱敏预览。原始 TOML 是保留未知内容的文档，字段投影只读；编辑通过 patch 更新文档，不维护两套可写配置。Rust 复用锁定 TOML 工具和固定 EasyTier 解析器。保持缺省/显式空/空列表/显式 false；跨 JNI 的 u64 使用十进制字符串，不能经 Double。

解析和编码不启动 TUN 或会话；沿用唯一 .so。新增 JNI 动作与能力标识保持旧生命周期动作兼容；错误只返回安全字段 key、位置与错误码，不返回原始输入。平台托管参数只在运行投影调整，原件保留差异；未知或未接入项阻止直接运行，不静默删除。所有运行变更继续受控重连，不新增热应用。

## 4. 分任务与实施顺序

按 P03-01 → 03 → 02 → 04 → 05 → 06 → 07 → 08 执行。先完成内存数据契约与 codec，再把可解析的文档落盘；存储加载不依赖尚未交付的解析接口。下文 Kotlin 路径相对 `app/src/main/java/com/github/zzwtsy/chuanmen`；测试位于 `app/src/test` 的镜像包。标注“新增”的类型或文件是交付要求，当前尚不存在。

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
6. Application 只创建一个仓库并异步加载；Service 与所有 ViewModel 持有同一实例。同一步修改 ConfigurationInput：只返回校验后的创建参数，不再构造 MemoryProfileRepository；ConfigurationEntry 调用这个已装配仓库的 suspend create，持久化成功后通知选择新 profileId。AppAssembly 按 Loading/ReadFailed/Ready 分支展示，移除 setRepository；现有输入控件先保留，P03-05 再统一为双标签表单。
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

1. 每个 profileId 只保留一份草稿，包含 baseRevision、rawInput、fieldErrors、模式和递增 editSequence。基础/完整标签、搜索和设置入口引用同一对象。
2. 保存时捕获 profileId、baseRevision、editSequence 和文档快照；每个配置最多一个保存请求在途。等待期间允许编辑，但禁用再次保存。
3. 回执成功后更新 saved 与 baseRevision；只有当前 editSequence 等于提交值时才清除 dirty。等待期间新增的输入、错误和模式不被回执覆盖。
4. Conflict 显示“重新载入已保存版本”和“保留当前草稿”。前者需用户确认后丢弃草稿；后者不自动改 baseRevision，也不重试覆盖。用户可切页继续查看，直到显式重新载入后再编辑保存；本阶段不实现自动合并或强制覆盖。
5. Activity 旋转通过现有 Activity owner 的 ViewModel 保留草稿；进程重新创建从 saved 建新草稿。秘密不写 SavedStateHandle、Bundle 或磁盘草稿。

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
