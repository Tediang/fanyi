package com.tediang.quicktranslate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

internal enum class TranslationErrorType(val displayName: String) {
    INPUT_LIMIT("输入过长"),
    CONFIGURATION("配置不兼容"),
    URL("地址错误"),
    CLEARTEXT_BLOCKED("明文 HTTP 已阻止"),
    AUTHENTICATION("鉴权失败"),
    MODEL("模型不可用"),
    RATE_LIMIT("服务限流"),
    CERTIFICATE("证书错误"),
    TIMEOUT("请求超时"),
    NETWORK("网络错误"),
    PROTOCOL("协议解析错误"),
    SERVER("服务端错误"),
    PARTIAL_STREAM("流式响应中断"),
}

internal data class TranslationMetrics(
    val requestDispatchMs: Long,
    val firstTextMs: Long?,
    val totalMs: Long,
)

internal data class TranslationDiagnostics(
    val protocol: String,
    val endpoint: String,
    val model: String,
    val httpStatus: Int?,
    val errorType: TranslationErrorType?,
    val requestDispatchMs: Long,
    val firstTextMs: Long?,
    val totalMs: Long,
) {
    fun asSanitizedText(): String = buildString {
        appendLine("协议：$protocol")
        appendLine("接口：$endpoint")
        appendLine("模型：$model")
        appendLine("HTTP：${httpStatus ?: "—"}")
        appendLine("分类：${errorType?.displayName ?: "成功"}")
        appendLine("请求发出：${requestDispatchMs}ms")
        appendLine("首片段：${firstTextMs?.let { "${it}ms" } ?: "—"}")
        append("总耗时：${totalMs}ms")
    }
}

internal data class TranslationResult(
    val text: String,
    val incomplete: Boolean,
    val diagnostics: TranslationDiagnostics,
)

internal class TranslationFailure(
    val type: TranslationErrorType,
    val userMessage: String,
    val partialText: String = "",
    val diagnostics: TranslationDiagnostics? = null,
    cause: Throwable? = null,
) : IOException(userMessage, cause)

internal interface TranslationGateway {
    suspend fun translate(
        profile: ProviderProfile,
        sourceText: String,
        targetLanguage: TargetLanguage,
        onRequestDispatched: () -> Unit = {},
        onDelta: suspend (String) -> Unit,
    ): TranslationResult
}

internal class TranslationEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : TranslationGateway {
    override suspend fun translate(
        profile: ProviderProfile,
        sourceText: String,
        targetLanguage: TargetLanguage,
        onRequestDispatched: () -> Unit,
        onDelta: suspend (String) -> Unit,
    ): TranslationResult = withContext(Dispatchers.IO) {
            val startedAt = elapsedMillis()
            var sentAt: Long? = null
            var firstTextAt: Long? = null
            var statusCode: Int? = null
            val partial = StringBuilder()
            val endpoint = sanitizedEndpoint(profile.endpoint())
            val adapter = TranslationProtocolAdapters.forType(profile.protocolType)

            fun diagnostics(errorType: TranslationErrorType? = null): TranslationDiagnostics {
                val now = elapsedMillis()
                return TranslationDiagnostics(
                    protocol = profile.protocolType.displayName,
                    endpoint = endpoint,
                    model = profile.model,
                    httpStatus = statusCode,
                    errorType = errorType,
                    requestDispatchMs = (sentAt ?: now) - startedAt,
                    firstTextMs = firstTextAt?.minus(startedAt),
                    totalMs = now - startedAt,
                )
            }

            try {
                validateInput(profile, sourceText)
                ProfileNetworkPolicy.requireAllowed(profile)
                val protocolRequest = adapter.buildRequest(profile, sourceText, targetLanguage)
                val request = Request.Builder()
                    .url(profile.endpoint())
                    .post(protocolRequest.body.toRequestBody(JSON_MEDIA_TYPE))
                    .apply {
                        protocolRequest.headers.forEach { (name, value) -> header(name, value) }
                        profile.customHeaders.forEach { header(it.name, it.value) }
                    }
                    .build()
                val call = httpClient.newCall(request)
                val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) call.cancel()
                }
                sentAt = elapsedMillis()
                onRequestDispatched()
                try {
                    call.execute().use { response ->
                        statusCode = response.code
                        if (!response.isSuccessful) {
                            val rawError = response.body.string()
                            val detail = adapter.extractError(rawError)
                            val type = classifyHttpError(response.code, detail)
                            throw TranslationFailure(
                                type = type,
                                userMessage = userMessageFor(type, response.code),
                                diagnostics = diagnostics(type),
                            )
                        }

                        val isEventStream = response.header("Content-Type").orEmpty()
                            .contains("text/event-stream", ignoreCase = true)
                        if (protocolRequest.streaming && isEventStream) {
                            val source = response.body.source()
                            var completed = false
                            val dataLines = mutableListOf<String>()

                            suspend fun consumeEvent() {
                                if (dataLines.isEmpty()) return
                                val event = runCatching { adapter.parseStreamEvent(dataLines.joinToString("\n")) }
                                    .getOrElse {
                                        throw TranslationFailure(
                                            TranslationErrorType.PROTOCOL,
                                            "服务返回了无法解析的流事件",
                                            partial.toString(),
                                            diagnostics(TranslationErrorType.PROTOCOL),
                                            it,
                                        )
                                    }
                                dataLines.clear()
                                if (event.error.isNotBlank()) {
                                    throw TranslationFailure(
                                        TranslationErrorType.PROTOCOL,
                                        "服务在流式响应中报告错误",
                                        partial.toString(),
                                        diagnostics(TranslationErrorType.PROTOCOL),
                                    )
                                }
                                if (event.delta.isNotEmpty()) {
                                    if (firstTextAt == null) firstTextAt = elapsedMillis()
                                    partial.append(event.delta)
                                    onDelta(event.delta)
                                }
                                if (event.completed) completed = true
                            }

                            while (!source.exhausted()) {
                                coroutineContext.ensureActive()
                                val line = source.readUtf8Line() ?: break
                                when {
                                    line.isEmpty() -> consumeEvent()
                                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                                }
                                if (completed) break
                            }
                            consumeEvent()
                            if (!completed) {
                                throw TranslationFailure(
                                    TranslationErrorType.PARTIAL_STREAM,
                                    if (partial.isEmpty()) "流式响应意外结束" else "连接中断，已保留收到的部分译文",
                                    partial.toString(),
                                    diagnostics(TranslationErrorType.PARTIAL_STREAM),
                                )
                            }
                        } else {
                            val rawBody = response.body.string()
                            val result = runCatching { adapter.parseSynchronous(rawBody) }
                                .getOrElse {
                                    throw TranslationFailure(
                                        TranslationErrorType.PROTOCOL,
                                        "服务返回了无法解析的响应",
                                        diagnostics = diagnostics(TranslationErrorType.PROTOCOL),
                                        cause = it,
                                    )
                                }
                            if (result.isBlank()) {
                                throw TranslationFailure(
                                    TranslationErrorType.PROTOCOL,
                                    "服务未返回译文",
                                    diagnostics = diagnostics(TranslationErrorType.PROTOCOL),
                                )
                            }
                            firstTextAt = elapsedMillis()
                            partial.append(result)
                            onDelta(result)
                            if (adapter.isSynchronousIncomplete(rawBody)) {
                                throw TranslationFailure(
                                    TranslationErrorType.PARTIAL_STREAM,
                                    "输出达到上限，结果可能不完整",
                                    partial.toString(),
                                    diagnostics(TranslationErrorType.PARTIAL_STREAM),
                                )
                            }
                        }
                    }
                } finally {
                    cancellationHandle?.dispose()
                }

                if (partial.isEmpty()) {
                    throw TranslationFailure(
                        TranslationErrorType.PROTOCOL,
                        "服务未返回译文",
                        diagnostics = diagnostics(TranslationErrorType.PROTOCOL),
                    )
                }
                TranslationResult(
                    text = partial.toString(),
                    incomplete = false,
                    diagnostics = diagnostics(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationFailure) {
                throw error
            } catch (error: ProfileNetworkException) {
                val type = if (error.problem == ConnectionProblem.CLEARTEXT_BLOCKED) {
                    TranslationErrorType.CLEARTEXT_BLOCKED
                } else {
                    TranslationErrorType.URL
                }
                throw TranslationFailure(type, error.message ?: type.displayName, diagnostics = diagnostics(type), cause = error)
            } catch (error: IllegalArgumentException) {
                throw TranslationFailure(
                    TranslationErrorType.CONFIGURATION,
                    error.message ?: "供应商配置不兼容",
                    diagnostics = diagnostics(TranslationErrorType.CONFIGURATION),
                    cause = error,
                )
            } catch (error: SSLException) {
                throw TranslationFailure(
                    TranslationErrorType.CERTIFICATE,
                    "TLS 证书或安全连接失败",
                    partial.toString(),
                    diagnostics(TranslationErrorType.CERTIFICATE),
                    error,
                )
            } catch (error: SocketTimeoutException) {
                throw timeoutFailure(partial.toString(), diagnostics(TranslationErrorType.TIMEOUT), error)
            } catch (error: InterruptedIOException) {
                throw timeoutFailure(partial.toString(), diagnostics(TranslationErrorType.TIMEOUT), error)
            } catch (error: UnknownHostException) {
                throw TranslationFailure(
                    TranslationErrorType.URL,
                    "无法解析服务地址",
                    partial.toString(),
                    diagnostics(TranslationErrorType.URL),
                    error,
                )
            } catch (error: ConnectException) {
                throw TranslationFailure(
                    TranslationErrorType.NETWORK,
                    "无法连接到服务",
                    partial.toString(),
                    diagnostics(TranslationErrorType.NETWORK),
                    error,
                )
            } catch (error: IOException) {
                throw TranslationFailure(
                    TranslationErrorType.NETWORK,
                    "网络连接中断",
                    partial.toString(),
                    diagnostics(TranslationErrorType.NETWORK),
                    error,
                )
            }
    }

    private fun validateInput(profile: ProviderProfile, sourceText: String) {
        require(profile.inputLimit > 0) { "最大输入字符数必须大于 0" }
        if (sourceText.codePointCount(0, sourceText.length) > profile.inputLimit) {
            throw TranslationFailure(
                TranslationErrorType.INPUT_LIMIT,
                "原文超过 ${profile.inputLimit} 个字符，请缩短文本或调整供应商限制",
            )
        }
        require(profile.temperature == null || profile.temperature in 0.0..2.0) {
            "Temperature 必须在 0 到 2 之间"
        }
        require(profile.maxOutputTokens == null || profile.maxOutputTokens > 0) {
            "最大输出量必须大于 0"
        }
    }

    private fun classifyHttpError(status: Int, detail: String): TranslationErrorType {
        val normalized = detail.lowercase()
        return when {
            status == 401 || status == 403 -> TranslationErrorType.AUTHENTICATION
            status == 429 -> TranslationErrorType.RATE_LIMIT
            normalized.contains("model") || normalized.contains("模型") -> TranslationErrorType.MODEL
            status == 404 -> TranslationErrorType.URL
            status >= 500 -> TranslationErrorType.SERVER
            else -> TranslationErrorType.PROTOCOL
        }
    }

    private fun userMessageFor(type: TranslationErrorType, status: Int): String = when (type) {
        TranslationErrorType.AUTHENTICATION -> "API Key 或鉴权信息无效"
        TranslationErrorType.RATE_LIMIT -> "服务请求过多，请稍后重试"
        TranslationErrorType.MODEL -> "当前模型不可用，请检查模型名称"
        TranslationErrorType.URL -> "接口地址不存在，请检查 Base URL 和路径"
        TranslationErrorType.SERVER -> "服务端暂时不可用（HTTP $status）"
        else -> "服务拒绝了请求（HTTP $status）"
    }

    private fun timeoutFailure(
        partialText: String,
        diagnostics: TranslationDiagnostics,
        cause: Throwable,
    ) = TranslationFailure(
        TranslationErrorType.TIMEOUT,
        if (partialText.isBlank()) "请求超过 60 秒" else "请求超时，已保留收到的部分译文",
        partialText,
        diagnostics,
        cause,
    )

    private fun sanitizedEndpoint(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw.substringBefore('?')
        val port = if (url.port == url.defaultPort()) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port${url.encodedPath}"
    }

    private fun okhttp3.HttpUrl.defaultPort(): Int = if (scheme == "https") 443 else 80

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val TOTAL_TIMEOUT_SECONDS = 60L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
