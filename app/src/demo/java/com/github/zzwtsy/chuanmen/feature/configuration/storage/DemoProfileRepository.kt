package com.github.zzwtsy.chuanmen.feature.configuration.storage

import com.github.zzwtsy.chuanmen.contracts.ProfileRepository
import com.github.zzwtsy.chuanmen.contracts.ProfileSummary

/** demo 装配保留独立类型，共用不触发会话动作的内存保存与改名实现。 */
class DemoProfileRepository(initial: List<ProfileSummary>) : ProfileRepository by MemoryProfileRepository(initial)
