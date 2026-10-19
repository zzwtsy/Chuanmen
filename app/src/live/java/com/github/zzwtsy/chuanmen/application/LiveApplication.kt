package com.github.zzwtsy.chuanmen.application

import android.app.Application
import com.github.zzwtsy.chuanmen.contracts.ProfileRepository
import com.github.zzwtsy.chuanmen.feature.configuration.storage.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.vpn.VpnProfileProvider
import com.github.zzwtsy.chuanmen.vpn.VpnSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** P02 配置与客户端仅存于进程内；Service 经公开仓库接口核对已保存修订。 */
class LiveApplication : Application(), VpnProfileProvider {
    private val mutableRepository = MutableStateFlow<ProfileRepository>(MemoryProfileRepository(emptyList()))
    val repository: StateFlow<ProfileRepository> = mutableRepository
    override val profiles: ProfileRepository get() = repository.value
    val sessions by lazy { VpnSessionClient(this) }
    fun setRepository(repository: ProfileRepository) {
        check(profiles.profiles.value.isEmpty()) { "受控配置只能初始化一次" }
        mutableRepository.value = repository
    }
}
