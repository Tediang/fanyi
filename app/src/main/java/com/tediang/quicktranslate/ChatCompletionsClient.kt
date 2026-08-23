package com.tediang.quicktranslate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal class ChatCompletionsClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun translate(
        config: ServiceConfig,
        sourceText: String,
        onDelta: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = ChatCompletionsProtocol.requestBody(config.model, sourceText)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(ChatCompletionsProtocol.endpoint(config.baseUrl))
            .post(body)
            .header("Accept", "text/event-stream, application/json")
            .apply {
                if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body
            if (!response.isSuccessful) {
                val rawError = responseBody.string()
                val detail = ChatCompletionsProtocol.errorMessage(rawError)
                throw TranslationException(if (detail.isBlank()) "服务返回 ${response.code}" else detail)
            }

            if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val result = StringBuilder()
                val source = responseBody.source()
                while (!source.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trimStart()
                    if (data == "[DONE]") break
                    val delta = ChatCompletionsProtocol.streamDelta(data)
                    if (delta.isNotEmpty()) {
                        result.append(delta)
                        withContext(Dispatchers.Main.immediate) { onDelta(delta) }
                    }
                }
                result.toString().ifBlank { throw TranslationException("服务未返回译文") }
            } else {
                val result = ChatCompletionsProtocol.synchronousContent(responseBody.string())
                if (result.isBlank()) throw TranslationException("服务未返回译文")
                withContext(Dispatchers.Main.immediate) { onDelta(result) }
                result
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal class TranslationException(message: String) : IOException(message)
