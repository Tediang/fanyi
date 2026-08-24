package com.tediang.quicktranslate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class ProviderProfileRepository(
    private val context: Context,
    private val preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val secretBox = KeystoreSecretBox("quick_translate_profiles_${preferencesName.hashCode()}")

    @Synchronized
    fun load(): ProviderCatalog {
        val stored = preferences.getString(KEY_CATALOG, null)
        if (stored == null) return migrateLegacyProfile() ?: ProviderCatalog(emptyList(), null)
        return decodeCatalog(stored)
    }

    @Synchronized
    fun save(profile: ProviderProfile, makeCurrent: Boolean = false): ProviderCatalog {
        val catalog = load()
        require(
            catalog.profiles.none {
                it.id != profile.id && it.name.equals(profile.name, ignoreCase = true)
            },
        ) { "供应商配置名称不能重复" }
        val existingIndex = catalog.profiles.indexOfFirst { it.id == profile.id }
        val updatedProfiles = catalog.profiles.toMutableList().apply {
            if (existingIndex >= 0) set(existingIndex, profile) else add(profile)
        }
        val currentId = when {
            makeCurrent -> profile.id
            catalog.currentProfileId != null -> catalog.currentProfileId
            catalog.profiles.isEmpty() -> profile.id
            else -> null
        }
        return persist(ProviderCatalog(updatedProfiles, currentId))
    }

    @Synchronized
    fun select(profileId: String): ProviderCatalog {
        val catalog = load()
        require(catalog.profiles.any { it.id == profileId }) { "供应商配置不存在" }
        return persist(catalog.copy(currentProfileId = profileId))
    }

    @Synchronized
    fun delete(profileId: String): ProviderCatalog {
        val catalog = load()
        val remaining = catalog.profiles.filterNot { it.id == profileId }
        val currentId = catalog.currentProfileId.takeUnless { it == profileId }
        return persist(ProviderCatalog(remaining, currentId))
    }

    private fun persist(catalog: ProviderCatalog): ProviderCatalog {
        preferences.edit().putString(KEY_CATALOG, encodeCatalog(catalog)).apply()
        return catalog
    }

    private fun encodeCatalog(catalog: ProviderCatalog): String {
        val profiles = JSONArray()
        catalog.profiles.forEach { profile ->
            val secrets = JSONObject()
                .put("api_key", profile.apiKey)
                .put(
                    "headers",
                    JSONArray().apply {
                        profile.customHeaders.forEach { put(JSONObject().put("value", it.value)) }
                    },
                )
            profiles.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("protocol", profile.protocolType.name)
                    .put("base_url", profile.baseUrl)
                    .put("endpoint_path", profile.endpointPathOverride)
                    .put("model", profile.model)
                    .put("allow_cleartext", profile.allowCleartext)
                    .put("additional_requirements", profile.additionalRequirements)
                    .put("reasoning_effort", profile.reasoningEffort.name)
                    .put("temperature", profile.temperature ?: JSONObject.NULL)
                    .put("max_output_tokens", profile.maxOutputTokens ?: JSONObject.NULL)
                    .put("streaming", profile.streaming)
                    .put("extra_body", profile.extraBody)
                    .put("input_limit", profile.inputLimit)
                    .put(
                        "header_names",
                        JSONArray().apply { profile.customHeaders.forEach { put(it.name) } },
                    )
                    .put("secrets", secretBox.seal(secrets.toString())),
            )
        }
        return JSONObject()
            .put("current_profile_id", catalog.currentProfileId ?: JSONObject.NULL)
            .put("profiles", profiles)
            .toString()
    }

    private fun decodeCatalog(raw: String): ProviderCatalog {
        val root = JSONObject(raw)
        val profiles = root.getJSONArray("profiles")
        val decoded = buildList {
            repeat(profiles.length()) { index ->
                val stored = profiles.getJSONObject(index)
                val headerNames = stored.optJSONArray("header_names") ?: JSONArray()
                val secrets = JSONObject(secretBox.open(stored.optString("secrets")))
                val headerValues = secrets.optJSONArray("headers") ?: JSONArray()
                add(
                    ProviderProfile(
                        id = stored.getString("id"),
                        name = stored.getString("name"),
                        protocolType = ProtocolType.valueOf(stored.getString("protocol")),
                        baseUrl = stored.getString("base_url"),
                        endpointPathOverride = stored.optString("endpoint_path"),
                        apiKey = secrets.optString("api_key"),
                        model = stored.getString("model"),
                        customHeaders = buildList {
                            repeat(headerNames.length()) { headerIndex ->
                                add(
                                    CustomHeader(
                                        name = headerNames.getString(headerIndex),
                                        value = headerValues.optJSONObject(headerIndex)?.optString("value").orEmpty(),
                                    ),
                                )
                            }
                        },
                        allowCleartext = stored.optBoolean("allow_cleartext"),
                        additionalRequirements = stored.opt("additional_requirements") as? String ?: "",
                        reasoningEffort = runCatching {
                            ReasoningEffort.valueOf(stored.opt("reasoning_effort") as? String ?: "AUTO")
                        }.getOrDefault(ReasoningEffort.AUTO),
                        temperature = (stored.opt("temperature").takeUnless { it == JSONObject.NULL } as? Number)
                            ?.toDouble(),
                        maxOutputTokens = (stored.opt("max_output_tokens").takeUnless { it == JSONObject.NULL } as? Number)
                            ?.toInt(),
                        streaming = if (stored.has("streaming")) stored.optBoolean("streaming") else true,
                        extraBody = stored.opt("extra_body") as? String ?: "",
                        inputLimit = stored.optInt("input_limit", ProviderProfile.DEFAULT_INPUT_LIMIT),
                    ),
                )
            }
        }
        return ProviderCatalog(
            profiles = decoded,
            currentProfileId = if (root.isNull("current_profile_id")) {
                null
            } else {
                root.optString("current_profile_id").takeIf { it.isNotBlank() }
            },
        )
    }

    private fun migrateLegacyProfile(): ProviderCatalog? {
        if (preferencesName != PREFERENCES_NAME) return null
        val legacyStore = EncryptedServiceConfigStore(context)
        val legacy = legacyStore.load() ?: return null
        val profile = ProviderProfile(
            name = legacy.name,
            protocolType = ProtocolType.OPENAI_CHAT_COMPLETIONS,
            baseUrl = legacy.baseUrl,
            apiKey = legacy.apiKey,
            model = legacy.model,
        )
        val migrated = persist(ProviderCatalog(listOf(profile), profile.id))
        legacyStore.clear()
        return migrated
    }

    private companion object {
        const val PREFERENCES_NAME = "quick_translate_provider_profiles"
        const val KEY_CATALOG = "catalog"
    }
}
