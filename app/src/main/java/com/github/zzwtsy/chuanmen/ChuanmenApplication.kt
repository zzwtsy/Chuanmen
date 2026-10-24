package com.github.zzwtsy.chuanmen

import android.app.Application
import com.github.zzwtsy.chuanmen.lib.profile.PersistentProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepositoryOwner
import com.github.zzwtsy.chuanmen.lib.vpn.VpnSessionClient

/** 进程持有同一个持久仓库与客户端；重建只恢复保存值，不自动连接 VPN。 */
open class ChuanmenApplication : Application(), ProfileRepositoryOwner {
    open val configCodec: com.github.zzwtsy.chuanmen.lib.profile.CoreConfigCodec by lazy { com.github.zzwtsy.chuanmen.lib.vpn.NativeCoreConfigCodec() }
    val preferences by lazy { com.github.zzwtsy.chuanmen.lib.AppearancePreferences.open(this) }
    override val profiles: ProfileRepository by lazy { PersistentProfileRepository.open(this, configCodec) }
    val sessions by lazy { VpnSessionClient(this) }
}
