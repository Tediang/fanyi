package com.tediang.quicktranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointTest {
    @Test
    fun deepSeekUsesItsDocumentedProtocolRoots() {
        assertEquals(
            "https://api.deepseek.com/responses",
            profile(ProtocolType.OPENAI_RESPONSES, "https://api.deepseek.com").endpoint(),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            profile(ProtocolType.OPENAI_CHAT_COMPLETIONS, "https://api.deepseek.com").endpoint(),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            profile(ProtocolType.ANTHROPIC_MESSAGES, "https://api.deepseek.com/anthropic").endpoint(),
        )
    }

    @Test
    fun genericOpenAiCompatibleRootsKeepVersionedDefaults() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            profile(ProtocolType.OPENAI_RESPONSES, "https://api.openai.com").endpoint(),
        )
        assertEquals(
            "http://192.168.1.20:8000/v1/chat/completions",
            profile(ProtocolType.OPENAI_CHAT_COMPLETIONS, "http://192.168.1.20:8000/v1").endpoint(),
        )
    }

    private fun profile(protocol: ProtocolType, baseUrl: String) = ProviderProfile(
        name = "endpoint-test",
        protocolType = protocol,
        baseUrl = baseUrl,
        model = "test-model",
    )
}
