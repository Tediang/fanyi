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

internal enum class ReasoningEffort(val displayName: String) {
    AUTO("自动"),
    OFF("关闭"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
}

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
    val additionalRequirements: String = "",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val streaming: Boolean = true,
    val extraBody: String = "",
    val inputLimit: Int = DEFAULT_INPUT_LIMIT,
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

    companion object {
        const val DEFAULT_INPUT_LIMIT = 20_000
    }
}

internal data class ProviderCatalog(
    val profiles: List<ProviderProfile>,
    val currentProfileId: String?,
) {
    val currentProfile: ProviderProfile?
        get() = profiles.firstOrNull { it.id == currentProfileId }
}
