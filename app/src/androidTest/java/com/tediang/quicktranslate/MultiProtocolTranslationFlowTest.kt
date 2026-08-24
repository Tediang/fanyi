package com.tediang.quicktranslate

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiProtocolTranslationFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var device: UiDevice
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("quick_translate_provider_profiles", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun responsesProfileStreamsThroughSharedSession() {
        saveProfile(ProtocolType.OPENAI_RESPONSES)
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你\"}\n\n" +
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"好\"}\n\n" +
                        "data: {\"type\":\"response.completed\"}\n\n",
                )
                .build(),
        )

        launchAndTranslate().use { assertVisible("你好") }

        val request = server.takeRequest()
        assertEquals("/v1/responses", request.url.encodedPath)
        val body = JSONObject(requireNotNull(request.body).utf8())
        assertEquals("Hello", body.getString("input"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getString("instructions").contains("Return only the translation"))
    }

    @Test
    fun anthropicProfileStreamsThroughSharedSessionWithNativeHeaders() {
        saveProfile(ProtocolType.ANTHROPIC_MESSAGES)
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你\"}}\n\n" +
                        "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                )
                .build(),
        )

        launchAndTranslate().use { assertVisible("你好") }

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals("fake-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        val body = JSONObject(requireNotNull(request.body).utf8())
        assertTrue(body.has("system"))
        assertEquals("Hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    private fun saveProfile(protocolType: ProtocolType) {
        ProviderProfileRepository(context).save(
            ProviderProfile(
                name = "假服务",
                protocolType = protocolType,
                baseUrl = server.url("/").toString(),
                apiKey = "fake-key",
                model = "fake-model",
                allowCleartext = true,
            ),
        )
    }

    private fun launchAndTranslate(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
        )
        val source = device.wait(Until.findObject(By.res("source_text")), TIMEOUT_MS)
            ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
            ?: error("Source field not found")
        source.text = "Hello"
        device.wait(Until.findObject(By.text("翻译")), TIMEOUT_MS)?.click()
            ?: error("Translate button not found")
        return scenario
    }

    private fun assertVisible(text: String) {
        assertNotNull(device.wait(Until.findObject(By.text(text)), TIMEOUT_MS))
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
