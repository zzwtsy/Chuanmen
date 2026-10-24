package com.github.zzwtsy.chuanmen.features.configuration.lib

import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId

/** 未保存的新建内容只由 Activity 的配置 ViewModel 持有，不进入系统状态恢复。 */
data class CreationDraft(
    val input: RawConfigurationInput = ConfigurationInputFactory.forNew(), val localName: String = "",
    val mode: EditorMode = EditorMode.Basic, val search: String = "",
    val validation: InputValidation = InputValidation(emptyMap(), emptyMap(), null),
    val token: SaveToken? = null, val createdId: LocalProfileId? = null, val notice: String? = null,
) {
    val saving get() = token != null && createdId == null
    override fun toString() = "CreationDraft(<redacted>)"
}
sealed interface CreationAction {
    data object Begin : CreationAction
    data class Edit(val field: DraftField, val raw: String) : CreationAction { override fun toString() = "Edit(<redacted>)" }
    data class Peer(val action: PeerAction) : CreationAction
    data class Name(val raw: String) : CreationAction { override fun toString() = "Name(<redacted>)" }
    data class Mode(val mode: EditorMode) : CreationAction
    data class Search(val raw: String) : CreationAction
    data object Save : CreationAction
    data object Cancel : CreationAction
    data class Acknowledge(val token: SaveToken) : CreationAction
}
