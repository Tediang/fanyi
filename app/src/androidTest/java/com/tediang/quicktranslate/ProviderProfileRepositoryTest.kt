package com.tediang.quicktranslate

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderProfileRepositoryTest {
    @Test
    fun reloadsMultipleProfilesWithSecretsWithoutPersistingPlaintext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferencesName = "provider-profile-repository-test"
        context.getSharedPreferences(preferencesName, 0).edit().clear().commit()
        val repository = ProviderProfileRepository(context, preferencesName)
        val cloud = ProviderProfile(
            id = "cloud",
            name = "云服务",
            protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.example.com",
            apiKey = "cloud-secret-key",
            model = "cloud-model",
            customHeaders = listOf(CustomHeader("X-Route", "private-route")),
            reasoningEffort = ReasoningEffort.LOW,
            temperature = 0.2,
            maxOutputTokens = 600,
            streaming = false,
            extraBody = "{\"vendor_flag\":true}",
            inputLimit = 12_345,
        )
        val local = ProviderProfile(
            id = "local",
            name = "本机服务",
            protocolType = ProtocolType.OPENAI_RESPONSES,
            baseUrl = "http://192.168.1.8:8000",
            model = "local-model",
            allowCleartext = true,
        )

        repository.save(cloud)
        repository.save(local)
        val selected = repository.select(local.id)
        val reloaded = ProviderProfileRepository(context, preferencesName).load()

        assertEquals(local.id, selected.currentProfileId)
        assertEquals(listOf(cloud.id, local.id), selected.profiles.map { it.id })
        assertEquals(selected, reloaded)
        assertEquals("cloud-secret-key", reloaded.profiles.first().apiKey)
        assertEquals("private-route", reloaded.profiles.first().customHeaders.single().value)
        val ordinaryStorage = context.getSharedPreferences(preferencesName, 0).all.values.joinToString()
        assertFalse(ordinaryStorage.contains("cloud-secret-key"))
        assertFalse(ordinaryStorage.contains("private-route"))
    }

    @Test
    fun deletingCurrentProfileDoesNotSilentlySelectAnother() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferencesName = "provider-profile-delete-test"
        context.getSharedPreferences(preferencesName, 0).edit().clear().commit()
        val repository = ProviderProfileRepository(context, preferencesName)
        val first = ProviderProfile(
            id = "first",
            name = "一号",
            protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://one.example.com",
            model = "one",
        )
        val second = first.copy(id = "second", name = "二号")
        repository.save(first)
        repository.save(second)

        val remaining = repository.delete(first.id)

        assertEquals(listOf(second), remaining.profiles)
        assertNull(remaining.currentProfileId)
    }
}
