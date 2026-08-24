package com.tediang.quicktranslate

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionControllerTest {
    @Test
    fun exposesStreamingThenCompletedState() = runTest {
        val gateway = object : TranslationGateway {
            override suspend fun translate(
                profile: ProviderProfile,
                sourceText: String,
                targetLanguage: TargetLanguage,
                onRequestDispatched: () -> Unit,
                onDelta: suspend (String) -> Unit,
            ): TranslationResult {
                onDelta("你")
                onDelta("好")
                return result("你好")
            }
        }
        val controller = TranslationSessionController(gateway, this, "Hello")

        controller.start(profile())
        advanceUntilIdle()

        assertEquals("你好", controller.state.value.translatedText)
        assertTrue(controller.state.value.progress is TranslationProgress.Completed)
    }

    @Test
    fun newerRequestRejectsLateEventsFromCancelledRequest() = runTest {
        var invocation = 0
        val gateway = object : TranslationGateway {
            override suspend fun translate(
                profile: ProviderProfile,
                sourceText: String,
                targetLanguage: TargetLanguage,
                onRequestDispatched: () -> Unit,
                onDelta: suspend (String) -> Unit,
            ): TranslationResult {
                invocation += 1
                if (invocation == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { onDelta("旧") }
                    }
                }
                onDelta("新")
                return result("新")
            }
        }
        val controller = TranslationSessionController(gateway, this, "First")

        controller.start(profile())
        runCurrent()
        controller.updateSource("Second")
        controller.start(profile())
        advanceUntilIdle()

        assertEquals("新", controller.state.value.translatedText)
        assertTrue(controller.state.value.progress is TranslationProgress.Completed)
    }

    @Test
    fun cancellationKeepsVisiblePartialTranslation() = runTest {
        val gateway = object : TranslationGateway {
            override suspend fun translate(
                profile: ProviderProfile,
                sourceText: String,
                targetLanguage: TargetLanguage,
                onRequestDispatched: () -> Unit,
                onDelta: suspend (String) -> Unit,
            ): TranslationResult {
                onDelta("部分")
                awaitCancellation()
            }
        }
        val controller = TranslationSessionController(gateway, this, "Hello")

        controller.start(profile())
        runCurrent()
        controller.cancel()
        runCurrent()

        assertEquals("部分", controller.state.value.translatedText)
        assertEquals(TranslationProgress.Cancelled, controller.state.value.progress)
    }

    private fun profile() = ProviderProfile(
        name = "测试",
        protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
        baseUrl = "https://api.example.com",
        model = "test-model",
    )

    private fun result(text: String) = TranslationResult(
        text = text,
        incomplete = false,
        diagnostics = TranslationDiagnostics(
            protocol = "test",
            endpoint = "https://api.example.com/v1/chat/completions",
            model = "test-model",
            httpStatus = 200,
            errorType = null,
            requestDispatchMs = 1,
            firstTextMs = 2,
            totalMs = 3,
        ),
    )
}
