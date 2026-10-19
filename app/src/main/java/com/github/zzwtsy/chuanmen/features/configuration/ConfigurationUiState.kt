package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.Draft
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.OperationFailure
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState

/** 配置页投影；草稿、已保存内容与真实 applied 分开，界面不修改会话事实。 */
data class ConfigurationUiState(
    val profile: ProfileSummary? = null,
    val draft: Draft? = null,
    val viewingRunningProfile: Boolean = false,
    val pendingApplication: Boolean = false,
    val operationPending: Boolean = false,
    val failure: OperationFailure? = null,
    val message: String? = null,
) {
    companion object {
        internal fun project(
            profiles: List<ProfileSummary>, session: SessionState, viewedId: LocalProfileId?,
            draft: Draft?, message: String? = null,
        ): ConfigurationUiState {
            val profile = profiles.find { it.saved.profileId == viewedId }
            val applied = session.applied?.takeIf { it.saved.profileId == viewedId && profile != null }
            val running = applied?.freshness == DataFreshness.Fresh &&
                applied.sessionId == session.sessionId && session.phase != ConnectionPhase.OutcomeUnknown
            val pending = running && profile != null && applied != null &&
                (profile.saved.core != applied.saved.core || profile.saved.vpnPolicy != applied.saved.vpnPolicy)
            return ConfigurationUiState(
                profile, draft, running, pending, session.pending != null,
                session.failure.takeIf { session.requestedProfileId == viewedId && profile != null }, message,
            )
        }
    }
}
