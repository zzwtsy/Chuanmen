package com.github.zzwtsy.chuanmen.features.configuration.lib

import com.github.zzwtsy.chuanmen.lib.profile.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.lib.profile.CoreConfig
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision

/**
 * 连接配置的纯构造与校验；所有构建共用真实输入，调用方显式提供对端和秘密。
 * 不接收外部 Intent、不读写磁盘；Service 装配就绪后消费返回仓库的已保存修订。
 * 所有参数先校验再建立仓库，错误不回显密码或对端原文。
 */
object ConfigurationInput {
    fun create(
        profileId: LocalProfileId,
        instanceId: CoreInstanceId,
        localDisplayName: String,
        networkName: String,
        networkSecret: NetworkSecret,
        peers: List<PeerEndpoint>,
        ipv4: Ipv4Assignment,
        dnsServers: List<String>,
        includeInternet: Boolean = false,
        mtu: Int = 1380,
    ): MemoryProfileRepository {
        require(peers.isNotEmpty()) { "连接必须提供对端" }
        val saved = SavedRevision(
            profileId, 1,
            CoreConfig(instanceId, networkName, null, ipv4, mtu, networkSecret, peers),
            AndroidVpnPolicy(includeInternet, dnsServers),
        )
        return MemoryProfileRepository(listOf(ProfileSummary(saved, localDisplayName)))
    }
}
