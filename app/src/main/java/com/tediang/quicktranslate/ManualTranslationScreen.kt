package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class AppSurface { TRANSLATE, PROFILES, EDIT_PROFILE }

@Composable
internal fun ManualTranslationApp(
    profileRepository: ProviderProfileRepository,
    client: ChatCompletionsClient,
    connectionTester: ProviderConnectionTester,
    onClose: () -> Unit,
) {
    var catalog by remember { mutableStateOf(profileRepository.load()) }
    var surface by rememberSaveable {
        mutableStateOf(if (catalog.currentProfile == null) AppSurface.PROFILES else AppSurface.TRANSLATE)
    }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    when (surface) {
        AppSurface.TRANSLATE -> {
            val current = catalog.currentProfile
            if (current == null) {
                surface = AppSurface.PROFILES
            } else {
                ProviderTranslationScreen(
                    profile = current,
                    client = client,
                    onOpenProfiles = { surface = AppSurface.PROFILES },
                    onClose = onClose,
                )
            }
        }

        AppSurface.PROFILES -> ProviderProfilesScreen(
            catalog = catalog,
            connectionTester = connectionTester,
            onBack = {
                if (catalog.currentProfile != null) surface = AppSurface.TRANSLATE else onClose()
            },
            onAdd = {
                editingProfileId = null
                surface = AppSurface.EDIT_PROFILE
            },
            onEdit = {
                editingProfileId = it
                surface = AppSurface.EDIT_PROFILE
            },
            onSelect = { catalog = profileRepository.select(it) },
            onDelete = { catalog = profileRepository.delete(it) },
        )

        AppSurface.EDIT_PROFILE -> {
            val existing = catalog.profiles.firstOrNull { it.id == editingProfileId }
            ProviderProfileEditorScreen(
                existing = existing,
                existingNames = catalog.profiles.filterNot { it.id == existing?.id }.map { it.name },
                onCancel = { surface = AppSurface.PROFILES },
                onSave = { profile ->
                    val selectSaved = catalog.currentProfile == null
                    catalog = profileRepository.save(profile, makeCurrent = selectSaved)
                    surface = if (selectSaved) AppSurface.TRANSLATE else AppSurface.PROFILES
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTranslationScreen(
    profile: ProviderProfile,
    client: ChatCompletionsClient,
    onOpenProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    var sourceText by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("等待输入") }
    var loading by rememberSaveable { mutableStateOf(false) }
    var sourceError by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    BackHandler(onBack = onClose)
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text("快译") },
                navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
                actions = { TextButton(onClick = onOpenProfiles, enabled = !loading) { Text("供应商") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${profile.name} · ${profile.model}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                profile.protocolType.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sourceText,
                onValueChange = {
                    sourceText = it
                    if (it.isNotBlank()) sourceError = false
                },
                label = { Text("原文") },
                placeholder = { Text("输入或粘贴要翻译的文字") },
                supportingText = if (sourceError) ({ Text("请输入要翻译的文字") }) else null,
                isError = sourceError,
                minLines = 4,
                modifier = Modifier.fillMaxWidth().automationTag("source_text"),
            )
            Button(
                onClick = {
                    if (sourceText.isBlank()) {
                        sourceError = true
                        return@Button
                    }
                    if (profile.protocolType != ProtocolType.OPENAI_CHAT_COMPLETIONS) {
                        status = "翻译失败：${profile.protocolType.displayName} 翻译适配器将在后续工单接入"
                        return@Button
                    }
                    loading = true
                    result = ""
                    status = "正在连接…"
                    scope.launch {
                        runCatching {
                            client.translate(profile, sourceText.trim()) { delta ->
                                result += delta
                                status = "正在翻译…"
                            }
                        }.onSuccess { status = "翻译完成" }
                            .onFailure { status = "翻译失败：${it.message ?: "未知错误"}" }
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().automationTag("translate_button"),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 10.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (loading) "翻译中" else "翻译")
            }
            TranslationResultCard(status = status, result = result)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        copyTranslation(context, result)
                        scope.launch { snackbarHostState.showSnackbar("已复制译文") }
                    },
                    enabled = result.isNotBlank() && !loading,
                    modifier = Modifier.weight(1f),
                ) { Text("复制译文") }
                OutlinedButton(
                    onClick = {
                        sourceText = ""
                        result = ""
                        status = "等待输入"
                    },
                    enabled = !loading && (sourceText.isNotEmpty() || result.isNotEmpty()),
                    modifier = Modifier.weight(1f),
                ) { Text("清空") }
            }
        }
    }
}

@Composable
private fun TranslationResultCard(status: String, result: String) {
    Card(
        modifier = Modifier.fillMaxWidth().automationTag("translation_result"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                result.ifBlank { "译文会显示在这里" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (result.isBlank()) FontWeight.Normal else FontWeight.Medium,
                color = if (result.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun copyTranslation(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("快译译文", text))
}

internal fun Modifier.automationTag(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }
