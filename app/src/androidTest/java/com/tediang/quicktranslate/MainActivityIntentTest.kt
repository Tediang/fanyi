package com.tediang.quicktranslate

import android.content.Intent
import android.text.SpannableString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {
    @Test
    fun ordinaryLaunchShowsNoExternalText() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_MAIN
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "普通启动")
            assertVisible(device, "未收到外部文本")
        }
    }

    @Test
    fun selectedTextEntryShowsReceivedTextAndReadonlyState() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "The battery stores energy.")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "选词翻译")
            assertVisible(device, "The battery stores energy.")
            assertVisible(device, "只读")
        }
    }

    @Test
    fun selectedTextNormalizesNonStringCharSequence() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, SpannableString("Styled selected text."))
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "Styled selected text.")
        }
    }

    @Test
    fun textShareShowsSharedText() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared paragraph for translation.")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "分享翻译")
            assertVisible(device, "Shared paragraph for translation.")
            assertVisible(device, "文字")
        }
    }

    @Test
    fun urlOnlyShareIsClearlyMarkedWithoutFetching() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/post/42")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "分享翻译")
            assertVisible(device, "https://example.com/post/42")
            assertVisible(device, "URL（不会抓取）")
        }
    }

    @Test
    fun schemeLikeTextShareIsNotMisclassifiedAsUrl() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https:hello")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "分享翻译")
            assertVisible(device, "https:hello")
            assertVisible(device, "文字")
        }
    }

    @Test
    fun clipboardShortcutUsesDedicatedDiagnosticEntry() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            action = "com.tediang.quicktranslate.action.TRANSLATE_CLIPBOARD"
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertVisible(device, "快捷键翻译")
            assertVisible(device, "诊断版未读取剪贴板")
            assertVisible(device, "待接入")
        }
    }

    private fun assertVisible(device: UiDevice, text: String) {
        assertNotNull(
            "Expected visible text: $text",
            device.wait(Until.findObject(By.text(text)), 3_000),
        )
    }
}
