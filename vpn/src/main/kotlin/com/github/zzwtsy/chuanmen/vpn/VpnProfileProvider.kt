package com.github.zzwtsy.chuanmen.vpn

import com.github.zzwtsy.chuanmen.contracts.ProfileRepository

/** 应用提供配置仓库窄边界；Service 再次核对 Binder 请求确实对应已保存修订。 */
interface VpnProfileProvider {
    val profiles: ProfileRepository
}
