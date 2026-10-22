package com.github.zzwtsy.chuanmen

import android.app.Application
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepositoryOwner
import com.github.zzwtsy.chuanmen.lib.vpn.VpnSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** P02 配置与客户端仅存于进程内；Service 经公开仓库接口核对已保存修订。 */
class ChuanmenApplication : Application(), ProfileRepositoryOwner {
    private val mutableRepository = MutableStateFlow<ProfileRepository>(MemoryProfileRepository(emptyList()))
    val repository: StateFlow<ProfileRepository> = mutableRepository
    override val profiles: ProfileRepository get() = repository.value
    val sessions by lazy { VpnSessionClient(this) }
    fun setRepository(repository: ProfileRepository) {
        check(profiles.profiles.value.isEmpty()) { "连接配置只能初始化一次" }
        mutableRepository.value = repository
    }
}
