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
    fun everyProtocolRequestsDictionaryEntriesForSingleWords() {
        ProtocolType.entries.forEach { type ->
            val profile = profile(type)
            val body = JSONObject(
                adapter(profile).buildRequest(
                    profile,
                    "eligible",
                    TargetLanguage.SIMPLIFIED_CHINESE,
                ).body,
            )
            val instructions = when (type) {
                ProtocolType.OPENAI_CHAT_COMPLETIONS -> body
                    .getJSONArray("messages")
                    .getJSONObject(0)
                    .getString("content")
                ProtocolType.OPENAI_RESPONSES -> body.getString("instructions")
                ProtocolType.ANTHROPIC_MESSAGES -> body.getString("system")
            }

            assertTrue(instructions.contains("DICTIONARY ENTRY mode"))
            assertTrue(instructions.contains("Dictionary mode for one word"))
            assertTrue(instructions.contains("standard pronunciation"))
            assertTrue(instructions.contains("part of speech"))
            assertTrue(instructions.contains("2 natural example sentences"))
            assertTrue(instructions.contains("Ordinary translation mode for all other input"))
            assertTrue(instructions.contains("Return only the translation"))
        }
    }

    @Test
    fun dictionaryCandidatesPutTheTaskInUserContentButSentencesStayRaw() {
        ProtocolType.entries.forEach { type ->
            val profile = profile(type)

            val chineseEntryBody = JSONObject(
                adapter(profile).buildRequest(
                    profile,
                    "adjustment",
                    TargetLanguage.SIMPLIFIED_CHINESE,
                ).body,
            )
            val chineseEntryInput = userInput(type, chineseEntryBody)
            assertTrue(chineseEntryInput.contains("这是词典词条任务"))
            assertTrue(chineseEntryInput.contains("词条：adjustment"))
            assertTrue(chineseEntryInput.contains("读音："))
            assertTrue(chineseEntryInput.contains("词性："))
            assertTrue(chineseEntryInput.contains("释义："))
            assertTrue(chineseEntryInput.contains("例句："))
            assertTrue(chineseEntryInput.contains("原样包含“adjustment”"))

            val englishEntryBody = JSONObject(
                adapter(profile).buildRequest(
                    profile,
                    "调整",
                    TargetLanguage.ENGLISH,
                ).body,
            )
            val englishEntryInput = userInput(type, englishEntryBody)
            assertTrue(englishEntryInput.contains("dictionary entry task"))
            assertTrue(englishEntryInput.contains("Headword: 调整"))
            assertTrue(englishEntryInput.contains("Pronunciation:"))
            assertTrue(englishEntryInput.contains("Part of speech:"))
            assertTrue(englishEntryInput.contains("Meanings:"))
            assertTrue(englishEntryInput.contains("Examples:"))
            assertTrue(englishEntryInput.contains("exact text “调整”"))

            val sentence = "Please adjust this setting."
            val body = JSONObject(
                adapter(profile).buildRequest(
                    profile,
                    sentence,
                    TargetLanguage.SIMPLIFIED_CHINESE,
                ).body,
            )
            assertEquals(sentence, userInput(type, body))
        }
    }

    @Test
    fun classifierSeparatesRepresentativeWordsFromOrdinaryText() {
        listOf("adjustment", "don't", "调整", "美しい", "사랑").forEach { word ->
            assertEquals(
                "$word should use dictionary mode",
                TranslationTaskMode.DICTIONARY_ENTRY,
                TranslationTaskClassifier.classify(word),
            )
        }
        listOf(
            "Please adjust this setting.",
            "今天天气很好",
            "天气很好",
            "調整してください",
            "https://example.com",
            "123",
        ).forEach { text ->
            assertEquals(
                "$text should use ordinary translation mode",
                TranslationTaskMode.ORDINARY_TRANSLATION,
                TranslationTaskClassifier.classify(text),
            )
        }
    }

    @Test
    fun responsesUsesNativeShapeAndParsesSyncAndStream() {
        val profile = profile(ProtocolType.OPENAI_RESPONSES).copy(reasoningEffort = ReasoningEffort.HIGH)
        val adapter = adapter(profile)
        val source = "Hello there."
        val body = JSONObject(adapter.buildRequest(profile, source, TargetLanguage.SIMPLIFIED_CHINESE).body)

        assertEquals(source, body.getString("input"))
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
        val source = "Hello there."
        val request = adapter.buildRequest(profile, source, TargetLanguage.SIMPLIFIED_CHINESE)
        val body = JSONObject(request.body)

        assertEquals("secret", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertTrue(body.has("system"))
        assertEquals(source, body.getJSONArray("messages").getJSONObject(0).getString("content"))
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

    private fun userInput(type: ProtocolType, body: JSONObject): String = when (type) {
        ProtocolType.OPENAI_CHAT_COMPLETIONS -> body
            .getJSONArray("messages")
            .getJSONObject(1)
            .getString("content")
        ProtocolType.OPENAI_RESPONSES -> body.getString("input")
        ProtocolType.ANTHROPIC_MESSAGES -> body
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
    }

    private fun profile(type: ProtocolType) = ProviderProfile(
        name = "测试",
        protocolType = type,
        baseUrl = "https://api.example.com",
        apiKey = "",
        model = "test-model",
    )
}
