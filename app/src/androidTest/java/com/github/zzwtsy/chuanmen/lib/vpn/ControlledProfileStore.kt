package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.WriteResult

/** 设备测试使用正式仓库，创建或保存失败立即终止，不用临时仓库替代。 */
internal fun <T> WriteResult<T>.requireSuccess(): T = when (this) {
    is WriteResult.Success -> value
    is WriteResult.Failure -> error("CONTROLLED_PROFILE_WRITE_FAILED:$error")
}

/** 受控参数也经过正式固定核心 codec，不能直接绕过解析构造保存文档。 */
internal suspend fun com.github.zzwtsy.chuanmen.lib.profile.CoreConfig.controlledDocument(): com.github.zzwtsy.chuanmen.lib.profile.CoreConfigDocument =
    when (val result = NativeCoreConfigCodec().create(this)) {
        is com.github.zzwtsy.chuanmen.lib.profile.ConfigResult.Success -> result.value
        is com.github.zzwtsy.chuanmen.lib.profile.ConfigResult.Failure -> error("CONTROLLED_CONFIG_REJECTED:${result.problem.code}")
    }

/** 受控设备测试只构造领域输入；文档仍由真实 codec 生成，不向 main 提供测试工厂。 */
internal fun controlledInput(instanceId: com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId, networkName: String,
    networkSecret: com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret, peers: List<com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint>,
    ipv4: com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment, dnsServers: List<String>):
    Pair<com.github.zzwtsy.chuanmen.lib.profile.CoreConfig, com.github.zzwtsy.chuanmen.lib.profile.AndroidVpnPolicy> =
    com.github.zzwtsy.chuanmen.lib.profile.CoreConfig(instanceId, networkName, null, ipv4, networkSecret = networkSecret, peers = peers) to
        com.github.zzwtsy.chuanmen.lib.profile.AndroidVpnPolicy(dnsServers = dnsServers)
