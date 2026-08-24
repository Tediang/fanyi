package com.tediang.quicktranslate

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TranslationEngineTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun rejectsOverLimitBeforeNetwork() = runBlocking {
        val profile = profile().copy(inputLimit = 3)

        val failure = runCatching {
            TranslationEngine().translate(profile, "four", TargetLanguage.SIMPLIFIED_CHINESE) {}
        }.exceptionOrNull() as TranslationFailure

        assertEquals(TranslationErrorType.INPUT_LIMIT, failure.type)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keepsPartialTextWhenStreamEndsWithoutCompletionMarker() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n")
                .build(),
        )

        val failure = runCatching {
            TranslationEngine().translate(profile(), "Hello", TargetLanguage.SIMPLIFIED_CHINESE) {}
        }.exceptionOrNull() as TranslationFailure

        assertEquals(TranslationErrorType.PARTIAL_STREAM, failure.type)
        assertEquals("部分", failure.partialText)
    }

    @Test
    fun synchronousTranslationReturnsTextAndSanitizedDiagnostics() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}")
                .build(),
        )
        val configured = profile().copy(
            apiKey = "never-in-diagnostics",
            customHeaders = listOf(CustomHeader("X-Secret", "also-secret")),
            streaming = false,
        )
        var requestWasDispatched = false

        val result = TranslationEngine().translate(
            configured,
            "source-must-not-appear",
            TargetLanguage.SIMPLIFIED_CHINESE,
            onRequestDispatched = { requestWasDispatched = true },
        ) {}
        val diagnostics = result.diagnostics.asSanitizedText()

        assertEquals("你好", result.text)
        assertTrue(requestWasDispatched)
        assertFalse(diagnostics.contains("never-in-diagnostics"))
        assertFalse(diagnostics.contains("also-secret"))
        assertFalse(diagnostics.contains("source-must-not-appear"))
        assertFalse(diagnostics.contains("你好"))
        assertTrue(diagnostics.contains("OpenAI Chat Completions"))
    }

    @Test
    fun shortCallTimeoutIsClassifiedSeparately() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .body("{}")
                .build(),
        )
        val client = OkHttpClient.Builder().callTimeout(50, TimeUnit.MILLISECONDS).build()

        val failure = runCatching {
            TranslationEngine(client).translate(profile(), "Hello", TargetLanguage.SIMPLIFIED_CHINESE) {}
        }.exceptionOrNull() as TranslationFailure

        assertEquals(TranslationErrorType.TIMEOUT, failure.type)
    }

    private fun profile() = ProviderProfile(
        name = "测试",
        protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
        baseUrl = server.url("/").toString(),
        model = "test-model",
        allowCleartext = true,
    )
}
