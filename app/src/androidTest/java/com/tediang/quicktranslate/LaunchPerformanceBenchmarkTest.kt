package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchPerformanceBenchmarkTest {
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
        context.getSharedPreferences("quick_translate_clipboard_shortcut", 0).edit().clear().commit()
        ProviderProfileRepository(context).save(
            ProviderProfile(
                name = "基准假服务",
                protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
                baseUrl = server.url("/").toString(),
                model = "benchmark-model",
                allowCleartext = true,
            ),
        )
        LaunchPerformance.clear()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun recordsWarmLaunchTimingsForManualSelectionAndShortcutEntries() {
        repeat(SAMPLE_COUNT) {
            launch(Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN }, "快译")
        }
        repeat(SAMPLE_COUNT) {
            server.enqueue(streamResponse("译文"))
            launch(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_PROCESS_TEXT
                    type = "text/plain"
                    putExtra(Intent.EXTRA_PROCESS_TEXT, "Benchmark source $it")
                },
                "选词翻译",
            )
        }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("empty", ""))
        repeat(SAMPLE_COUNT) {
            launch(
                Intent(context, MainActivity::class.java).apply { action = ACTION_TRANSLATE_CLIPBOARD },
                "快捷键翻译",
            )
        }

        TranslationEntry.entries.forEach { entry ->
            if (entry == TranslationEntry.SHARE) return@forEach
            val samples = LaunchPerformance.uiVisibleSamples(entry)
            assertEquals("Missing samples for $entry", SAMPLE_COUNT, samples.size)
            val p95 = percentile95(samples)
            println("QUICK_TRANSLATE_BENCHMARK entry=$entry samples=$samples p95=${p95}ms")
            assertTrue("Diagnostic launch regression for $entry: ${p95}ms", p95 < 2_000)
        }
        printTranslationTimings(TranslationEntry.PROCESS_TEXT)
    }

    private fun launch(intent: Intent, visibleTitle: String) {
        val entry = TranslationLaunch.fromIntent(intent).entry
        val before = LaunchPerformance.uiVisibleSamples(entry).size
        ActivityScenario.launch<MainActivity>(intent).use {
            device.wait(Until.findObject(By.text(visibleTitle)), TIMEOUT_MS)
                ?: error("Surface not visible: $visibleTitle")
            if (entry == TranslationEntry.PROCESS_TEXT) {
                device.wait(Until.findObject(By.text("译文")), TIMEOUT_MS)
                    ?: error("Benchmark translation did not complete")
            }
            repeat(20) {
                if (LaunchPerformance.uiVisibleSamples(entry).size > before) return@use
                Thread.sleep(25)
            }
            error("Launch timing not recorded for $entry")
        }
    }

    private fun printTranslationTimings(entry: TranslationEntry) {
        val requestDispatch = LaunchPerformance.requestDispatchSamples(entry)
        val firstText = LaunchPerformance.firstTextSamples(entry)
        val total = LaunchPerformance.totalSamples(entry)
        assertEquals(SAMPLE_COUNT, requestDispatch.size)
        assertEquals(SAMPLE_COUNT, firstText.size)
        assertEquals(SAMPLE_COUNT, total.size)
        println(
            "QUICK_TRANSLATE_TRANSLATION_BENCHMARK entry=$entry " +
                "requestDispatch=$requestDispatch requestP95=${percentile95(requestDispatch)}ms " +
                "firstText=$firstText firstTextP95=${percentile95(firstText)}ms " +
                "total=$total totalP95=${percentile95(total)}ms",
        )
    }

    private fun streamResponse(text: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/event-stream")
        .body("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n")
        .build()

    private fun percentile95(samples: List<Long>): Long {
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(sorted.size * 0.95).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private companion object {
        const val SAMPLE_COUNT = 10
        const val TIMEOUT_MS = 5_000L
    }
}
