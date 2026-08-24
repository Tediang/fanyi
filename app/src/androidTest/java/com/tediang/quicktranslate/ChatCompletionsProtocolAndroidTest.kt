package com.tediang.quicktranslate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class ChatCompletionsProtocolAndroidTest {
    @Test
    fun nullDeepSeekContentIsNotTranslationText() {
        val event = """{"choices":[{"delta":{"role":"assistant","content":null}}]}"""
        val androidOptString = JSONObject(event)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("delta")
            .optString("content")

        assertEquals("Android optString coerces JSON null to text", "null", androidOptString)
        assertEquals(
            "",
            ChatCompletionsProtocol.streamDelta(event),
        )
    }
}
