package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Surface
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
    val diagnostics = when (val progress = state.progress) {
        is TranslationProgress.Completed -> progress.diagnostics
        is TranslationProgress.Failed -> progress.diagnostics
        else -> null
    }
    Scaffold(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(launch.entry.title) },
                navigationIcon = { TextButton(onClick = onClose) { Text("关闭") } },
                actions = { TextButton(onClick = onOpenProfiles, enabled = !running) { Text("供应商") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            TranslationActionBar(
                state = state,
                running = running,
                onPaste = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.text?.toString().orEmpty()
                    if (text.isNotBlank()) controller.updateSource(text)
                },
                onCopy = {
                    copyText(context, "快译译文", state.translatedText)
                    scope.launch { snackbarHostState.showSnackbar("译文已复制") }
                },
                onPrimaryAction = {
                    if (running) controller.cancel() else controller.start(profile)
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val compact = maxHeight < 640.dp
            val sourceMinHeight = if (compact) 128.dp else 168.dp
            val sourceMaxHeight = if (compact) 190.dp else 260.dp
            val resultMinHeight = if (compact) 144.dp else 184.dp
            val resultMaxHeight = if (compact) 220.dp else 320.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (launch.urlOnly) StatusBanner("收到的是 URL；快译不会抓取网页或帖子正文。")
                if (launch.readOnlyFromHost) {
                    Text(
                        "原文来自只读选区；快译只翻译，不会修改原应用内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = state.sourceText,
                    onValueChange = controller::updateSource,
                    label = { Text("原文") },
                    supportingText = {
                        CharacterCount(state.sourceText)
                    },
                    minLines = 5,
                    maxLines = 14,
                    enabled = !running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = sourceMinHeight, max = sourceMaxHeight)
                        .focusRequester(focusRequester)
                        .automationTag("source_text"),
                )

                Text(
                    "目标语言",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                TranslationResultCard(
                    state = state,
                    minHeight = resultMinHeight,
                    maxHeight = resultMaxHeight,
                    onCopyDiagnostics = diagnostics?.takeIf { state.progress is TranslationProgress.Failed }?.let {
                        {
                            copyText(context, "快译脱敏诊断", it.asSanitizedText())
                            scope.launch { snackbarHostState.showSnackbar("脱敏诊断已复制") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TranslationActionBar(
    state: TranslationSessionState,
    running: Boolean,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = if (state.translatedText.isNotBlank()) onCopy else onPaste,
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.translatedText.isNotBlank()) "复制译文" else "粘贴原文")
            }
            Button(
                onClick = onPrimaryAction,
                enabled = running || state.sourceText.isNotBlank(),
                modifier = Modifier
                    .weight(1.35f)
                    .automationTag("translate_button"),
            ) {
                Text(
                    when {
                        running -> "取消翻译"
                        state.translatedText.isNotBlank() -> "重新翻译"
                        else -> "翻译"
                    },
                )
            }
        }
    }
}

@Composable
private fun TranslationResultCard(
    state: TranslationSessionState,
    minHeight: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    onCopyDiagnostics: (() -> Unit)?,
) {
    val status = when (val progress = state.progress) {
        TranslationProgress.Idle -> "等待输入"
        TranslationProgress.Running -> if (state.translatedText.isEmpty()) "正在连接…" else "正在翻译…"
        is TranslationProgress.Completed -> "翻译完成 · ${progress.diagnostics.totalMs}ms"
        is TranslationProgress.Failed -> if (progress.incomplete) "结果不完整：${progress.message}" else progress.message
        TranslationProgress.Cancelled -> "已取消"
    }
    val error = state.progress is TranslationProgress.Failed
    val resultContentColor = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val resultMutedColor = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().automationTag("translation_result"),
        colors = if (error) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("译文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${unicodeCharacterCount(state.translatedText)} 字",
                    style = MaterialTheme.typography.labelMedium,
                    color = resultMutedColor,
                    modifier = Modifier.automationTag("translation_count"),
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = resultMutedColor,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight, max = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        state.translatedText.ifBlank { "暂无译文" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.translatedText.isBlank()) {
                            resultMutedColor
                        } else {
                            resultContentColor
                        },
                        modifier = Modifier.automationTag("translation_text"),
                    )
                }
            }
            onCopyDiagnostics?.let {
                TextButton(onClick = it) { Text("复制脱敏诊断") }
            }
        }
    }
}

@Composable
private fun CharacterCount(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            "${unicodeCharacterCount(text)} 字",
            modifier = Modifier.automationTag("source_count"),
        )
    }
}

private fun unicodeCharacterCount(text: String): Int = text.codePointCount(0, text.length)

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
