package com.tediang.quicktranslate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal enum class ConnectionProblem {
    URL,
    CLEARTEXT_BLOCKED,
    AUTHENTICATION,
    MODEL,
    PROTOCOL,
    RATE_LIMIT,
    TIMEOUT,
    CERTIFICATE,
    NETWORK,
    SERVER,
}

internal sealed interface ConnectionTestResult {
    data class Success(val message: String = "连接成功，协议响应有效") : ConnectionTestResult
    data class Failure(val problem: ConnectionProblem, val message: String) : ConnectionTestResult
}

internal class ProviderConnectionTester(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun test(profile: ProviderProfile): ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            ProfileNetworkPolicy.requireAllowed(profile)
            val request = Request.Builder()
                .url(profile.endpoint())
                .post(probeBody(profile).toString().toRequestBody(JSON_MEDIA_TYPE))
                .applyProfileHeaders(profile)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body.string()
                if (!response.isSuccessful) return@withContext classifyHttpFailure(response.code, rawBody)
                if (isExpectedProtocolResponse(profile.protocolType, rawBody)) {
                    ConnectionTestResult.Success()
                } else {
                    ConnectionTestResult.Failure(
                        ConnectionProblem.PROTOCOL,
                        "服务已响应，但内容不符合 ${profile.protocolType.displayName}",
                    )
                }
            }
        } catch (error: ProfileNetworkException) {
            ConnectionTestResult.Failure(error.problem, requireNotNull(error.message))
        } catch (error: IllegalArgumentException) {
            ConnectionTestResult.Failure(ConnectionProblem.URL, "地址或请求头格式无效")
        } catch (error: SSLException) {
            ConnectionTestResult.Failure(ConnectionProblem.CERTIFICATE, "TLS 证书或安全连接失败")
        } catch (error: SocketTimeoutException) {
            ConnectionTestResult.Failure(ConnectionProblem.TIMEOUT, "连接或请求超时")
        } catch (error: UnknownHostException) {
            ConnectionTestResult.Failure(ConnectionProblem.URL, "无法解析服务地址")
        } catch (error: ConnectException) {
            ConnectionTestResult.Failure(ConnectionProblem.NETWORK, "无法连接到服务")
        } catch (error: Exception) {
            ConnectionTestResult.Failure(ConnectionProblem.NETWORK, error.message ?: "网络请求失败")
        }
    }

    private fun probeBody(profile: ProviderProfile): JSONObject = when (profile.protocolType) {
        ProtocolType.OPENAI_CHAT_COMPLETIONS -> JSONObject()
            .put("model", profile.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")),
            )

        ProtocolType.OPENAI_RESPONSES -> JSONObject()
            .put("model", profile.model)
            .put("input", "Reply with OK.")
            .put("stream", false)

        ProtocolType.ANTHROPIC_MESSAGES -> JSONObject()
            .put("model", profile.model)
            .put("max_tokens", 8)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")),
            )
    }

    private fun Request.Builder.applyProfileHeaders(profile: ProviderProfile): Request.Builder = apply {
        header("Accept", "application/json")
        when (profile.protocolType) {
            ProtocolType.OPENAI_CHAT_COMPLETIONS,
            ProtocolType.OPENAI_RESPONSES,
            -> if (profile.apiKey.isNotBlank()) header("Authorization", "Bearer ${profile.apiKey}")

            ProtocolType.ANTHROPIC_MESSAGES -> {
                header("anthropic-version", "2023-06-01")
                if (profile.apiKey.isNotBlank()) header("x-api-key", profile.apiKey)
            }
        }
        profile.customHeaders.forEach { header(it.name, it.value) }
    }

    private fun classifyHttpFailure(status: Int, rawBody: String): ConnectionTestResult.Failure {
        val detail = extractErrorMessage(rawBody)
        val lowerDetail = detail.lowercase()
        val problem = when {
            status == 401 || status == 403 -> ConnectionProblem.AUTHENTICATION
            status == 429 -> ConnectionProblem.RATE_LIMIT
            lowerDetail.contains("model") || lowerDetail.contains("模型") -> ConnectionProblem.MODEL
            status == 404 -> ConnectionProblem.URL
            status >= 500 -> ConnectionProblem.SERVER
            else -> ConnectionProblem.PROTOCOL
        }
        val prefix = when (problem) {
            ConnectionProblem.AUTHENTICATION -> "鉴权失败"
            ConnectionProblem.RATE_LIMIT -> "服务限流"
            ConnectionProblem.MODEL -> "模型不可用"
            ConnectionProblem.URL -> "接口地址不存在"
            ConnectionProblem.SERVER -> "服务端错误"
            else -> "协议请求失败"
        }
        return ConnectionTestResult.Failure(
            problem,
            if (detail.isBlank()) "$prefix（HTTP $status）" else "$prefix：$detail",
        )
    }

    private fun isExpectedProtocolResponse(protocol: ProtocolType, rawBody: String): Boolean = runCatching {
        val json = JSONObject(rawBody)
        when (protocol) {
            ProtocolType.OPENAI_CHAT_COMPLETIONS -> json.optJSONArray("choices") != null
            ProtocolType.OPENAI_RESPONSES ->
                json.optString("object") == "response" || json.has("output") || json.has("output_text")
            ProtocolType.ANTHROPIC_MESSAGES ->
                json.optString("type") == "message" && json.optJSONArray("content") != null
        }
    }.getOrDefault(false)

    private fun extractErrorMessage(rawBody: String): String = runCatching {
        val json = JSONObject(rawBody)
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> json.optString("message")
        }
    }.getOrDefault("")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
