package com.tediang.quicktranslate

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationPaneFlowTest {
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
        context.getSharedPreferences("quick_translate_preferences", 0).edit().clear().commit()
        ProviderProfileRepository(context).save(
            ProviderProfile(
                name = "界面测试服务",
                protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
                baseUrl = server.url("/").toString(),
                model = "test-model",
                allowCleartext = true,
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun longTranslationKeepsBothPanesVisibleAndSupportsFullscreenAndClear() {
        val longTranslation = (1..160).joinToString(" ") { "译文$it" }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"$longTranslation\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                .build(),
        )

        launchApp().use {
            findResource("source_text").text = "Long source ".repeat(80)
            findResource("expand_source").click()
            assertResourceVisible("expanded_source_text")
            device.pressBack()

            findResource("translate_button").click()
            assertVisible("翻译完成", prefix = true)
            assertInsideViewport(findResource("source_text"))
            assertInsideViewport(findResource("translation_result"))

            findResource("expand_translation").click()
            assertResourceVisible("expanded_translation_text")
            findResource("expanded_clear").click()
            assertVisible("暂无译文")
            device.pressBack()

            findResource("clear_source").click()
            assertTrue(findResource("source_text").text.isNullOrEmpty())
        }
    }

    @Test
    fun exposesFourLanguagesAndPersistsBuiltInTranslationPreference() {
        launchApp().use {
            findResource("target_selector").click()
            assertVisible("日文")
            assertVisible("韩文")
            clickText("日文")
            assertVisible("译为 · 日文")

            findResource("preference_selector").click()
            listOf("通用", "正式", "口语", "书信", "学术", "文学").forEach(::assertVisible)
            clickText("正式")
            assertVisible("偏好 · 正式")
        }

        launchApp().use {
            assertVisible("偏好 · 正式")
        }
    }

    private fun launchApp(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
    )

    private fun findResource(resource: String): UiObject2 = requireNotNull(
        device.wait(Until.findObject(By.res(resource)), TIMEOUT_MS),
    ) { "Expected resource: $resource" }

    private fun assertResourceVisible(resource: String) {
        assertNotNull(
            "Expected visible resource: $resource",
            device.wait(Until.findObject(By.res(resource)), TIMEOUT_MS),
        )
    }

    private fun clickText(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), TIMEOUT_MS)
        assertNotNull("Expected clickable text: $text", node)
        requireNotNull(node).click()
    }

    private fun assertVisible(text: String, prefix: Boolean = false) {
        val selector = if (prefix) By.textStartsWith(text) else By.text(text)
        assertNotNull(
            "Expected visible text: $text",
            device.wait(Until.findObject(selector), TIMEOUT_MS),
        )
    }

    private fun assertInsideViewport(node: UiObject2) {
        val bounds = node.visibleBounds
        assertTrue("Expected positive visible height for ${node.resourceName}", bounds.height() > 0)
        assertTrue("Expected node inside top edge", bounds.top >= 0)
        assertTrue("Expected node inside bottom edge", bounds.bottom <= device.displayHeight)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
