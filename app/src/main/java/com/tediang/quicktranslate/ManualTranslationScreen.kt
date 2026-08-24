package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class AppSurface { TRANSLATE, PROFILES, EDIT_PROFILE }

@Composable
internal fun QuickTranslateApp(
    profileRepository: ProviderProfileRepository,
    gateway: TranslationGateway,
    connectionTester: ProviderConnectionTester,
    launch: TranslationLaunch,
    onClose: () -> Unit,
) {
    var catalog by remember { mutableStateOf(profileRepository.load()) }
    val sessionScope = rememberCoroutineScope()
    val controller = remember(launch.id) {
        TranslationSessionController(gateway, sessionScope, launch.sourceText, launch.id)
    }
    var autoStarted by remember(launch.id) { mutableStateOf(false) }
    var surface by rememberSaveable {
        mutableStateOf(if (catalog.currentProfile == null) AppSurface.PROFILES else AppSurface.TRANSLATE)
    }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeAfterTestProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(controller) { onDispose { controller.dispose() } }

    LaunchedEffect(launch.id) {
        withFrameNanos { }
        LaunchPerformance.markUiVisible(launch.id)
        surface = if (catalog.currentProfile == null) AppSurface.PROFILES else AppSurface.TRANSLATE
        if (catalog.currentProfile == null && launch.autoTranslate) resumeAfterTestProfileId = PENDING_PROFILE
    }
    LaunchedEffect(launch.id, catalog.currentProfileId, surface) {
        val current = catalog.currentProfile
        if (
            surface == AppSurface.TRANSLATE && launch.autoTranslate && !autoStarted &&
            current != null && controller.state.value.sourceText.isNotBlank()
        ) {
            autoStarted = true
            controller.start(current)
        }
    }

    when (surface) {
        AppSurface.TRANSLATE -> {
            val current = catalog.currentProfile
            if (current == null) {
                surface = AppSurface.PROFILES
            } else {
                TranslationSessionScreen(
                    launch = launch,
                    profile = current,
                    controller = controller,
                    onOpenProfiles = { surface = AppSurface.PROFILES },
                    onClose = onClose,
                )
            }
        }

        AppSurface.PROFILES -> ProviderProfilesScreen(
            catalog = catalog,
            connectionTester = connectionTester,
            resumeAfterTestProfileId = resumeAfterTestProfileId.takeUnless { it == PENDING_PROFILE },
            onBack = {
                if (resumeAfterTestProfileId != null) {
                    onClose()
                } else if (catalog.currentProfile != null) {
                    surface = AppSurface.TRANSLATE
                } else {
                    onClose()
                }
            },
            onAdd = {
                editingProfileId = null
                surface = AppSurface.EDIT_PROFILE
            },
            onEdit = {
                editingProfileId = it
                surface = AppSurface.EDIT_PROFILE
            },
            onSelect = {
                catalog = profileRepository.select(it)
                if (resumeAfterTestProfileId == null) surface = AppSurface.TRANSLATE
            },
            onDelete = { catalog = profileRepository.delete(it) },
            onTestSuccess = { profileId ->
                if (resumeAfterTestProfileId == profileId) {
                    catalog = profileRepository.select(profileId)
                    resumeAfterTestProfileId = null
                    surface = AppSurface.TRANSLATE
                }
            },
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
                    if (resumeAfterTestProfileId == PENDING_PROFILE) {
                        resumeAfterTestProfileId = profile.id
                        surface = AppSurface.PROFILES
                    } else {
                        surface = if (selectSaved) AppSurface.TRANSLATE else AppSurface.PROFILES
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationSessionScreen(
    launch: TranslationLaunch,
    profile: ProviderProfile,
    controller: TranslationSessionController,
    onOpenProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val state by controller.state.collectAsState()
    LaunchedEffect(launch.id, launch.focusInput) {
        if (launch.focusInput) focusRequester.requestFocus()
    }

    val running = state.progress is TranslationProgress.Running
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text(launch.entry.title) },
                navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
                actions = { TextButton(onClick = onOpenProfiles, enabled = !running) { Text("供应商") } },
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
            if (launch.urlOnly) StatusBanner("收到的是 URL；快译不会抓取网页或帖子正文。")
            if (launch.readOnlyFromHost) {
                Text(
                    "宿主标记为只读；译文不会替换原应用中的文字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.sourceText,
                onValueChange = controller::updateSource,
                label = { Text("原文") },
                minLines = 4,
                maxLines = 10,
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .automationTag("source_text"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TargetLanguage.entries.forEach { target ->
                    if (state.targetLanguage == target) {
                        Button(
                            onClick = { controller.selectTarget(target) },
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { Text(target.displayName) }
                    } else {
                        OutlinedButton(
                            onClick = { controller.selectTarget(target) },
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { Text(target.displayName) }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { if (running) controller.cancel() else controller.start(profile) },
                    modifier = Modifier.weight(1f).automationTag("translate_button"),
                ) { Text(if (running) "取消" else "翻译") }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString().orEmpty()
                        if (text.isNotBlank()) controller.updateSource(text)
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴") }
            }

            TranslationResultCard(state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        copyText(context, "快译译文", state.translatedText)
                        scope.launch { snackbarHostState.showSnackbar("译文已复制") }
                    },
                    enabled = state.translatedText.isNotBlank() && !running,
                    modifier = Modifier.weight(1f),
                ) { Text("复制译文") }
                OutlinedButton(
                    onClick = { controller.start(profile) },
                    enabled = state.sourceText.isNotBlank() && !running,
                    modifier = Modifier.weight(1f),
                ) { Text("重试") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val diagnostics = when (val progress = state.progress) {
                    is TranslationProgress.Completed -> progress.diagnostics
                    is TranslationProgress.Failed -> progress.diagnostics
                    else -> null
                }
                OutlinedButton(
                    onClick = {
                        diagnostics?.let { copyText(context, "快译脱敏诊断", it.asSanitizedText()) }
                        scope.launch { snackbarHostState.showSnackbar("脱敏诊断已复制") }
                    },
                    enabled = diagnostics != null && !running,
                    modifier = Modifier.weight(1f),
                ) { Text("复制诊断") }
                TextButton(
                    onClick = controller::clear,
                    enabled = !running && (state.sourceText.isNotEmpty() || state.translatedText.isNotEmpty()),
                    modifier = Modifier.weight(1f),
                ) { Text("清空") }
            }
        }
    }
}

@Composable
private fun TranslationResultCard(state: TranslationSessionState) {
    val status = when (val progress = state.progress) {
        TranslationProgress.Idle -> "等待输入"
        TranslationProgress.Running -> if (state.translatedText.isEmpty()) "正在连接…" else "正在翻译…"
        is TranslationProgress.Completed -> "翻译完成 · ${progress.diagnostics.totalMs}ms"
        is TranslationProgress.Failed -> if (progress.incomplete) "结果不完整：${progress.message}" else progress.message
        TranslationProgress.Cancelled -> "已取消"
    }
    val error = state.progress is TranslationProgress.Failed
    Card(
        modifier = Modifier.fillMaxWidth().automationTag("translation_result"),
        colors = if (error) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                state.translatedText.ifBlank { "译文会显示在这里" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.automationTag("translation_text"),
            )
        }
    }
}

@Composable
private fun StatusBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(text, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun Modifier.automationTag(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }

private const val PENDING_PROFILE = "__pending__"
