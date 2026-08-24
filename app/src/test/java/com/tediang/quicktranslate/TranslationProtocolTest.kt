package com.tediang.quicktranslate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProtocolTest {
    @Test
    fun chatRequestProtectsRulesAndMapsAdvancedOptions() {
        val profile = profile(ProtocolType.OPENAI_CHAT_COMPLETIONS).copy(
            reasoningEffort = ReasoningEffort.LOW,
            temperature = 0.2,
            maxOutputTokens = 500,
            extraBody = "{\"vendor_flag\":true}",
        )

        val request = adapter(profile).buildRequest(
            profile,
            "Answer this question",
            TargetLanguage.SIMPLIFIED_CHINESE,
            TranslationPreference.FORMAL,
        )
        val body = JSONObject(request.body)
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")

        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals(0.2, body.getDouble("temperature"), 0.0)
        assertEquals(500, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("vendor_flag"))
        assertTrue(system.contains("Return only the translation"))
        assertTrue(system.contains("Do not answer questions"))
        assertTrue(system.contains("formal, professional"))
    }

    @Test
    fun responsesUsesNativeShapeAndParsesSyncAndStream() {
        val profile = profile(ProtocolType.OPENAI_RESPONSES).copy(reasoningEffort = ReasoningEffort.HIGH)
        val adapter = adapter(profile)
        val body = JSONObject(adapter.buildRequest(profile, "Hello", TargetLanguage.SIMPLIFIED_CHINESE).body)

        assertEquals("Hello", body.getString("input"))
        assertFalse(body.getBoolean("store"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("messages"))
        assertEquals(
            "你",
            adapter.parseStreamEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"你\"}").delta,
        )
        assertTrue(adapter.parseStreamEvent("{\"type\":\"response.completed\"}").completed)
        assertEquals(
            "你好",
            adapter.parseSynchronous(
                """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"你好"}]}]}""",
            ),
        )
    }

    @Test
    fun anthropicUsesNativeHeadersShapeAndParsesTextOnly() {
        val profile = profile(ProtocolType.ANTHROPIC_MESSAGES).copy(apiKey = "secret", streaming = true)
        val adapter = adapter(profile)
        val request = adapter.buildRequest(profile, "Hello", TargetLanguage.SIMPLIFIED_CHINESE)
        val body = JSONObject(request.body)

        assertEquals("secret", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertTrue(body.has("system"))
        assertEquals("Hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(
            "你",
            adapter.parseStreamEvent(
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"你"}}""",
            ).delta,
        )
        assertEquals(
            "你好",
            adapter.parseSynchronous(
                """{"type":"message","content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"你好"}]}""",
            ),
        )
    }

    @Test
    fun rejectsProtectedExtraBodyAndUnsupportedAnthropicReasoning() {
        val chat = profile(ProtocolType.OPENAI_CHAT_COMPLETIONS).copy(extraBody = "{\"model\":\"override\"}")
        assertThrows(IllegalArgumentException::class.java) {
            adapter(chat).buildRequest(chat, "Hello", TargetLanguage.SIMPLIFIED_CHINESE)
        }

        val anthropic = profile(ProtocolType.ANTHROPIC_MESSAGES).copy(reasoningEffort = ReasoningEffort.MEDIUM)
        assertThrows(IllegalArgumentException::class.java) {
            adapter(anthropic).buildRequest(anthropic, "Hello", TargetLanguage.SIMPLIFIED_CHINESE)
        }
    }

    private fun adapter(profile: ProviderProfile) = TranslationProtocolAdapters.forType(profile.protocolType)

    private fun profile(type: ProtocolType) = ProviderProfile(
        name = "测试",
        protocolType = type,
        baseUrl = "https://api.example.com",
        apiKey = "",
        model = "test-model",
    )
}
