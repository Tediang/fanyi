package com.tediang.quicktranslate

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualTranslationFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("quick_translate_provider_profiles", 0).edit().clear().commit()
        ProviderProfileRepository(context).save(
            ProviderProfile(
                name = "本机假服务",
                protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
                baseUrl = server.url("/").toString(),
                apiKey = "fake-key",
                model = "fake-model",
                allowCleartext = true,
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun sendsProtectedRequestAndShowsStreamedTranslation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("测试", "不要覆盖"))
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考中\",\"content\":null}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                .build(),
        )

        launchApp().use {
            inputSourceAndTranslate("Hello")
            assertVisible("翻译完成", prefix = true)
            assertVisible("你好")
            assertEquals("不要覆盖", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            device.findObject(By.text("复制译文")).click()
            assertEquals("你好", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer fake-key", request.headers["Authorization"])
        val body = JSONObject(requireNotNull(request.body).utf8())
        assertEquals("fake-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("Hello", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun publishesEveryStreamDeltaBeforeReturningCompletion() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                .build(),
        )
        val events = mutableListOf<String>()
        val config = ServiceConfig("假服务", server.url("/").toString(), "", "fake-model")

        ChatCompletionsClient().translate(config, "Hello") { events += "片段:$it" }
        events += "完成"

        assertEquals(listOf("片段:你", "片段:好", "完成"), events)
    }

    @Test
    fun showsBasicServiceError() {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":{\"message\":\"凭据无效\"}}")
                .build(),
        )

        launchApp().use {
            inputSourceAndTranslate("Hello")
            assertVisible("API Key 或鉴权信息无效")
        }
    }

    private fun launchApp(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch(
            Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
        )
    }

    private fun inputSourceAndTranslate(text: String) {
        val source = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")),
            TIMEOUT_MS,
        ) ?: error("Source field not found")
        source.text = text
        val button = device.wait(
            Until.findObject(By.text("翻译")),
            TIMEOUT_MS,
        ) ?: error("Translate button not found")
        button.click()
    }

    private fun assertVisible(text: String, prefix: Boolean = false) {
        val selector = if (prefix) By.textStartsWith(text) else By.text(text)
        val node = device.wait(Until.findObject(selector), TIMEOUT_MS)
        assertTrue("Expected visible text: $text", node != null)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
