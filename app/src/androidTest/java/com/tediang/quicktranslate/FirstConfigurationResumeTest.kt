package com.tediang.quicktranslate

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstConfigurationResumeTest {
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
    fun externalTextSurvivesFirstConfigurationAndContinuesAfterConnectionTest() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"保留的原文\"}}]}\n\ndata: [DONE]\n\n")
                .build(),
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "Preserved source")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            clickText("新增供应商配置")
            findResource("profile_name").text = "Local test"
            findResource("profile_base_url").text = server.url("/").toString()
            findResource("profile_model").text = "fake-model"
            findResource("allow_cleartext").click()
            findResource("save_profile").click()

            assertVisible("原文已保留。请测试新配置；连接成功后会自动继续翻译。")
            clickText("测试连接")

            assertVisible("Preserved source")
            assertVisible("保留的原文")
        }
    }

    private fun findResource(resource: String): UiObject2 {
        repeat(8) {
            device.wait(Until.findObject(By.res(resource)), 700)?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.75f)
        }
        error("Resource not found: $resource")
    }

    private fun clickText(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), TIMEOUT_MS)
        assertNotNull("Expected clickable text: $text", node)
        requireNotNull(node).click()
    }

    private fun assertVisible(text: String) {
        assertNotNull(
            "Expected visible text: $text",
            device.wait(Until.findObject(By.text(text)), TIMEOUT_MS),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
