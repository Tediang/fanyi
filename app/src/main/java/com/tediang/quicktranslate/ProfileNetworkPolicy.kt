package com.tediang.quicktranslate

import android.net.Uri

internal object ProfileNetworkPolicy {
    fun requireAllowed(profile: ProviderProfile) {
        val uri = Uri.parse(profile.baseUrl.trim())
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw ProfileNetworkException(ConnectionProblem.URL, "Base URL 不是完整的 HTTP(S) 地址")
        }
        if (scheme == "http" && !profile.allowCleartext) {
            throw ProfileNetworkException(
                ConnectionProblem.CLEARTEXT_BLOCKED,
                "此配置未允许局域网明文 HTTP",
            )
        }
        if (scheme == "http" && !uri.host.orEmpty().isLocalNetworkHost()) {
            throw ProfileNetworkException(
                ConnectionProblem.CLEARTEXT_BLOCKED,
                "明文 HTTP 仅允许 localhost、.local 或私有局域网地址",
            )
        }
    }

    private fun String.isLocalNetworkHost(): Boolean {
        val host = lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            parts[0] == 127
    }
}

internal class ProfileNetworkException(
    val problem: ConnectionProblem,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
