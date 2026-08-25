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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

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
            assertResourceVisible("translation_status")
            assertVisible("等待输入")
            findResource("expand_source").click()
            assertResourceVisible("expanded_source_text")
            device.pressBack()

            findResource("translate_button").click()
            assertResourceVisible("translation_status")
            assertVisible("翻译完成", prefix = true)
            assertInsideViewport(findResource("source_text"))
            assertInsideViewport(findResource("translation_result"))
            assertResourceVisible("translation_scrollbar")

            findResource("expand_translation").click()
            assertResourceVisible("expanded_translation_text")
            assertResourceVisible("expanded_translation_status")
            assertResourceVisible("expanded_collapse")
            findResource("expanded_clear").click()
            assertVisible("暂无译文")
            findResource("expanded_collapse").click()
            assertResourceVisible("translation_result")

            findResource("clear_source").click()
            assertTrue(findResource("source_text").text.isNullOrEmpty())
        }
    }

    @Test
    fun exposesFourLanguagesAndPersistsBuiltInTranslationPreference() {
        launchApp().use {
            assertBottomControlsClearGestureEdge()
            assertVisible("日文")
            assertVisible("韩文")
            val japaneseChoice = findResource("choice_target_JAPANESE")
            val minimumChoiceHeight = (48 * context.resources.displayMetrics.density).roundToInt()
            assertTrue(
                "Inline language choices should meet the Android touch target minimum",
                japaneseChoice.visibleBounds.height() >= minimumChoiceHeight,
            )
            japaneseChoice.click()
            assertVisible("目标 · 日文")

            findResource("preference_selector").click()
            listOf("通用", "正式", "口语", "书信", "学术", "文学").forEach(::assertVisible)
            clickText("正式")
            assertVisible("风格 · 正式")
        }

        launchApp().use {
            assertVisible("风格 · 正式")
        }
    }

    @Test
    fun sourceInputKeepsFocusAndBoundsWhileTyping() {
        launchApp().use {
            val before = findResource("source_text").visibleBounds
            findResource("source_text").click()
            assertNotNull(
                "Source input should keep focus after the keyboard opens",
                device.wait(Until.findObject(By.res("source_text").focused(true)), TIMEOUT_MS),
            )

            findResource("source_text").text = "输入稳定性"
            assertVisible("输入稳定性")
            val after = findResource("source_text").visibleBounds

            assertEquals("Source input must not jump horizontally", before.left, after.left)
            assertEquals("Source input must not jump vertically", before.top, after.top)
            device.pressBack()
        }
    }

    @Test
    fun sourceAutoFocusesOnlyUntilTheUserMovesFocusAway() {
        launchApp().use {
            val focusedSource = By.res("source_text").focused(true)
            assertNotNull(
                "Source should receive focus on initial entry",
                device.wait(Until.findObject(focusedSource), TIMEOUT_MS),
            )

            findResource("preference_selector").click()
            assertTrue(
                "Opening another control should release source focus",
                device.wait(Until.gone(focusedSource), TIMEOUT_MS),
            )
            device.pressBack()
            assertTrue("Recomposition must not reclaim source focus", device.findObject(focusedSource) == null)

            findResource("source_text").click()
            assertNotNull(
                "The user can focus the source again explicitly",
                device.wait(Until.findObject(focusedSource), TIMEOUT_MS),
            )
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

    private fun assertBottomControlsClearGestureEdge() {
        val bottomGap = device.displayHeight - findResource("translate_button").visibleBounds.bottom
        val minimumGap = (44 * context.resources.displayMetrics.density).roundToInt()
        assertTrue("Bottom controls should sit clear of the gesture edge", bottomGap >= minimumGap)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
