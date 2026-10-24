package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository

/** JVM 平台测试的 Application；生产 Application 没有仓库替换入口。 */
class TestChuanmenApplication : ChuanmenApplication() {
    override val configCodec = com.github.zzwtsy.chuanmen.lib.profile.TestCoreConfigCodec()
    override var profiles: ProfileRepository = MemoryProfileRepository(emptyList())
}
