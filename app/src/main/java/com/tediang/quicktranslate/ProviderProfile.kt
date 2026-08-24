package com.tediang.quicktranslate

import java.util.UUID

internal enum class ProtocolType(
    val displayName: String,
    val defaultPath: String,
) {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions", "/v1/chat/completions"),
    OPENAI_RESPONSES("OpenAI Responses", "/v1/responses"),
    ANTHROPIC_MESSAGES("Anthropic Messages", "/v1/messages"),
}

internal data class CustomHeader(
    val name: String,
    val value: String,
)

internal data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocolType: ProtocolType,
    val baseUrl: String,
    val endpointPathOverride: String = "",
    val apiKey: String = "",
    val model: String,
    val customHeaders: List<CustomHeader> = emptyList(),
    val allowCleartext: Boolean = false,
) {
    fun endpoint(): String {
        val path = endpointPathOverride.trim().ifBlank { protocolType.defaultPath }
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val normalizedPath = path.trim().let { if (it.startsWith('/')) it else "/$it" }
        if (endpointPathOverride.isNotBlank()) return normalizedBase + normalizedPath
        return if (normalizedBase.endsWith("/v1") && normalizedPath.startsWith("/v1/")) {
            normalizedBase + normalizedPath.removePrefix("/v1")
        } else {
            normalizedBase + normalizedPath
        }
    }
}

internal data class ProviderCatalog(
    val profiles: List<ProviderProfile>,
    val currentProfileId: String?,
) {
    val currentProfile: ProviderProfile?
        get() = profiles.firstOrNull { it.id == currentProfileId }
}
