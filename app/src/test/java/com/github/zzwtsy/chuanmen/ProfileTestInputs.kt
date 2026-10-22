package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.profile.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.lib.profile.CoreConfig
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import com.github.zzwtsy.chuanmen.lib.vpn.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.Observation
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId

/** 构造独立的已保存测试输入；不同配置必须显式传入不同身份，不注册全局场景。 */
internal fun testSavedRevision(
    profileId: LocalProfileId = LocalProfileId("00000000-0000-4000-8000-000000000001"),
    ipv4: Ipv4Assignment = Ipv4Assignment.Automatic,
): SavedRevision = SavedRevision(
    profileId, 1,
    CoreConfig(CoreInstanceId("test-instance-${profileId.value}"), "test-network", "test-host", ipv4),
    AndroidVpnPolicy(),
)

/** 构造明确已确认的运行边界输入，不启动引擎、采样器或模拟状态机。 */
internal fun testAppliedSnapshot(saved: SavedRevision = testSavedRevision()): AppliedSessionSnapshot =
    AppliedSessionSnapshot(
        SessionId("test-session", 1), saved,
        Observation("10.42.0.1", 1000, DataFreshness.Fresh),
    )
