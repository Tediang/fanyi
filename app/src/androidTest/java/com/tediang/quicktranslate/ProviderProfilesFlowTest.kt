package com.tediang.quicktranslate

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProfilesFlowTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("quick_translate_provider_profiles", 0).edit().clear().commit()
        val repository = ProviderProfileRepository(context)
        repository.save(
            ProviderProfile(
                id = "cloud",
                name = "云端服务",
                protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://api.example.com",
                apiKey = "must-never-appear",
                model = "cloud-model",
            ),
        )
        repository.save(
            ProviderProfile(
                id = "local",
                name = "本地服务",
                protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "http://192.168.1.8:8000",
                model = "local-model",
                allowCleartext = true,
            ),
        )
    }

    @Test
    fun listsProfilesWithoutSecretsAndExplicitlySwitchesCurrentProfile() {
        launchApp().use {
            clickText("供应商")
            assertVisible("云端服务")
            assertVisible("本地服务")
            assertResourceVisible("current_provider_summary")
            assertFalse(device.hasObject(By.textContains("must-never-appear")))
            assertProfilesKeepCreationOrder()

            clickText("设为当前供应商")

            clickText("供应商")
            assertResourceVisible("current_profile_local")
            assertVisible("当前使用")
            assertProfilesKeepCreationOrder()
        }
    }

    @Test
    fun keepsSaveActionReachableAndConfirmsDiscardingChangedDraft() {
        launchApp().use {
            clickText("供应商")
            clickText("新增")

            assertResourceVisible("save_profile")
            assertFalse(device.hasObject(By.text("附加要求（可选）")))
            findResource("profile_name").text = "尚未保存的服务"
            clickText("取消")

            assertVisible("放弃未保存的修改？")
            clickText("继续编辑")
            assertVisible("尚未保存的服务")

            clickText("取消")
            assertVisible("放弃未保存的修改？")
            clickText("放弃修改")
            assertVisible("供应商配置")
        }
    }

    private fun launchApp(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch(
            Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
        )
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

    private fun findResource(resource: String) = requireNotNull(
        device.wait(Until.findObject(By.res(resource)), TIMEOUT_MS),
    ) { "Expected resource: $resource" }

    private fun assertResourceVisible(resource: String) {
        assertNotNull(
            "Expected visible resource: $resource",
            device.wait(Until.findObject(By.res(resource)), TIMEOUT_MS),
        )
    }

    private fun assertProfilesKeepCreationOrder() {
        val cloud = findResource("provider_card_cloud").visibleBounds
        val local = findResource("provider_card_local").visibleBounds
        assertTrue("Provider cards must remain in creation order", cloud.top < local.top)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
