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
    }
}

internal class ProfileNetworkException(
    val problem: ConnectionProblem,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
