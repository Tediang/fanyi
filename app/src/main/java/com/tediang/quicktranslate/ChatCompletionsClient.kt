package com.tediang.quicktranslate

import okhttp3.OkHttpClient

/** Compatibility adapter retained for the ticket-02 tests; new callers use [TranslationGateway]. */
internal class ChatCompletionsClient(
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val engine = TranslationEngine(httpClient)

    suspend fun translate(
        config: ServiceConfig,
        sourceText: String,
        onDelta: suspend (String) -> Unit,
    ): String = translate(
        profile = ProviderProfile(
            name = config.name,
            protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            allowCleartext = config.baseUrl.trim().startsWith("http://"),
        ),
        sourceText = sourceText,
        onDelta = onDelta,
    )

    suspend fun translate(
        profile: ProviderProfile,
        sourceText: String,
        onDelta: suspend (String) -> Unit,
    ): String = engine.translate(
        profile = profile,
        sourceText = sourceText,
        targetLanguage = defaultTargetLanguage(sourceText),
        onDelta = onDelta,
    ).text
}

internal typealias TranslationException = TranslationFailure
