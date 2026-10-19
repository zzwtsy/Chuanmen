package com.github.zzwtsy.chuanmen.feature.configuration.controlled

import com.github.zzwtsy.chuanmen.contracts.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.contracts.CoreConfig
import com.github.zzwtsy.chuanmen.contracts.CoreInstanceId
import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.LocalProfileId
import com.github.zzwtsy.chuanmen.contracts.NetworkSecret
import com.github.zzwtsy.chuanmen.contracts.PeerEndpoint
import com.github.zzwtsy.chuanmen.contracts.ProfileSummary
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.github.zzwtsy.chuanmen.feature.configuration.storage.MemoryProfileRepository

/**
 * liveDebug 专用受控配置入口；调用方显式提供对端和秘密，源码不含真实默认凭据。
 * 不接收外部 Intent、不读写磁盘；Service 装配就绪后消费返回仓库的已保存修订。
 * 所有参数先校验再建立仓库，错误不回显密码或对端原文。
 */
object ControlledProfileInput {
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
        require(peers.isNotEmpty()) { "受控连接必须提供对端" }
        require(localDisplayName.isNotBlank()) { "本地名称不能为空" }
        val saved = SavedRevision(
            profileId, 1,
            CoreConfig(instanceId, networkName, null, ipv4, mtu, networkSecret, peers),
            AndroidVpnPolicy(includeInternet, dnsServers),
        )
        return MemoryProfileRepository(listOf(ProfileSummary(saved, localDisplayName)))
    }
}
