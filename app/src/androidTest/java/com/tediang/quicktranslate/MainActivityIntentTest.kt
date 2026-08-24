package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.SpannableString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {
    private lateinit var server: MockWebServer
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("quick_translate_provider_profiles", 0).edit().clear().commit()
        context.getSharedPreferences("quick_translate_clipboard_shortcut", 0).edit().clear().commit()
        ProviderProfileRepository(context).save(
            ProviderProfile(
                name = "测试服务",
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
    fun ordinaryLaunchShowsManualSurfaceWithoutReadingClipboard() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("test", "clipboard must stay idle"))

        launch(Intent.ACTION_MAIN).use {
            assertVisible("快译")
            assertVisible("等待输入")
            assertVisible("暂无译文")
            assertNotVisible("测试服务 · test-model")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun selectedTextRunsTranslationAndKeepsHostReadonlyMeaning() {
        enqueueTranslation("电池储存能量。")
        val intent = baseIntent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, SpannableString("The battery stores energy."))
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            assertVisible("选词翻译")
            assertVisible("The battery stores energy.")
            assertVisible("原文来自只读选区；快译只翻译，不会修改原应用内容。")
            assertVisible("电池储存能量。")
            assertVisible("翻译完成", prefix = true)
        }
    }

    @Test
    fun textShareRunsTranslation() {
        enqueueTranslation("共享段落")
        val intent = baseIntent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared paragraph for translation.")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            assertVisible("分享翻译")
            assertVisible("Shared paragraph for translation.")
            assertVisible("共享段落")
        }
    }

    @Test
    fun urlOnlyShareTranslatesLiteralUrlWithoutFetchingIt() {
        enqueueTranslation("https://example.com/post/42")
        val intent = baseIntent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/post/42")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            assertVisible("收到的是 URL；快译不会抓取网页或帖子正文。")
            assertVisible("https://example.com/post/42")
        }

        assertEquals(1, server.requestCount)
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun clipboardShortcutConsumesFreshTextOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("test", "Fresh clipboard text"))
        enqueueTranslation("新剪贴板文字")

        ActivityScenario.launch<MainActivity>(baseIntent(ACTION_TRANSLATE_CLIPBOARD)).use {
            assertVisible("快捷键翻译")
            assertVisible("Fresh clipboard text")
            assertVisible("新剪贴板文字")
        }

        ActivityScenario.launch<MainActivity>(baseIntent(ACTION_TRANSLATE_CLIPBOARD)).use {
            assertVisible("快捷键翻译")
            assertVisible("等待输入")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun newerExternalIntentCancelsOldSessionAndRejectsLateResult() {
        server.enqueue(
            MockResponse.Builder()
                .headersDelay(2, TimeUnit.SECONDS)
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"旧结果\"}}]}\n\ndata: [DONE]\n\n")
                .build(),
        )
        enqueueTranslation("新结果")
        val first = baseIntent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "First source")
        }

        ActivityScenario.launch<MainActivity>(first).use {
            assertVisible("First source")
            context().startActivity(
                baseIntent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Second source")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )

            assertVisible("Second source")
            assertVisible("新结果")
            assertNotVisible("旧结果")

            // ActivityScenario matches lifecycle events by the launch Intent. MainActivity
            // correctly replaces its Intent in onNewIntent, so restore it only for cleanup.
            it.onActivity { activity -> activity.intent = first }
        }
    }

    private fun enqueueTranslation(text: String) {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n")
                .build(),
        )
    }

    private fun launch(action: String): ActivityScenario<MainActivity> =
        ActivityScenario.launch(baseIntent(action))

    private fun baseIntent(action: String) = Intent(
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java,
    ).apply { this.action = action }

    private fun context(): android.content.Context = ApplicationProvider.getApplicationContext()

    private fun assertVisible(text: String, prefix: Boolean = false) {
        val selector = if (prefix) By.textStartsWith(text) else By.text(text)
        assertNotNull(
            "Expected visible text: $text",
            device.wait(Until.findObject(selector), TIMEOUT_MS),
        )
    }

    private fun assertNotVisible(text: String) {
        assertNull(device.findObject(By.text(text)))
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
