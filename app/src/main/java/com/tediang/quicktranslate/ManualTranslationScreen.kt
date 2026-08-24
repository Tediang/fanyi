package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class AppSurface { TRANSLATE, PROFILES, EDIT_PROFILE }
private enum class ExpandedPane { SOURCE, TRANSLATION }

@Composable
internal fun QuickTranslateApp(
    profileRepository: ProviderProfileRepository,
    gateway: TranslationGateway,
    connectionTester: ProviderConnectionTester,
    preferenceStore: TranslationPreferenceStore,
    launch: TranslationLaunch,
    onClose: () -> Unit,
) {
    var catalog by remember { mutableStateOf(profileRepository.load()) }
    val sessionScope = rememberCoroutineScope()
    val controller = remember(launch.id) {
        TranslationSessionController(
            gateway,
            sessionScope,
            launch.sourceText,
            launch.id,
            preferenceStore.load(),
        )
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
                    onSelectPreference = {
                        controller.selectPreference(it)
                        preferenceStore.save(it)
                    },
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
    onSelectPreference: (TranslationPreference) -> Unit,
    onOpenProfiles: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val state by controller.state.collectAsState()
    var expandedPane by rememberSaveable { mutableStateOf<ExpandedPane?>(null) }
    LaunchedEffect(launch.id, launch.focusInput) {
        if (launch.focusInput) focusRequester.requestFocus()
    }

    val running = state.progress is TranslationProgress.Running
    val diagnostics = when (val progress = state.progress) {
        is TranslationProgress.Completed -> progress.diagnostics
        is TranslationProgress.Failed -> progress.diagnostics
        else -> null
    }

    expandedPane?.let { pane ->
        BackHandler { expandedPane = null }
        ExpandedTextScreen(
            pane = pane,
            state = state,
            running = running,
            onSourceChange = controller::updateSource,
            onClear = {
                if (pane == ExpandedPane.SOURCE) controller.clearSource() else controller.clearTranslation()
            },
            onBack = { expandedPane = null },
        )
        return
    }

    Scaffold(
        modifier = Modifier
            .semantics { testTagsAsResourceId = true },
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
            val landscape = maxWidth > maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (launch.urlOnly) StatusBanner("收到的是 URL；快译不会抓取网页或帖子正文。")
                if (launch.readOnlyFromHost) {
                    Text(
                        "原文来自只读选区；快译只翻译，不会修改原应用内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TranslationSelectors(
                    targetLanguage = state.targetLanguage,
                    preference = state.preference,
                    enabled = !running,
                    onSelectTarget = controller::selectTarget,
                    onSelectPreference = onSelectPreference,
                )

                val copyDiagnostics: (() -> Unit)? = diagnostics
                    ?.takeIf { state.progress is TranslationProgress.Failed }
                    ?.let {
                        {
                            copyText(context, "快译脱敏诊断", it.asSanitizedText())
                            scope.launch { snackbarHostState.showSnackbar("脱敏诊断已复制") }
                            Unit
                        }
                    }
                if (landscape) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SourceTextPane(
                            state = state,
                            running = running,
                            focusRequester = focusRequester,
                            onSourceChange = controller::updateSource,
                            onClear = controller::clearSource,
                            onExpand = { expandedPane = ExpandedPane.SOURCE },
                            modifier = Modifier.weight(1f),
                        )
                        TranslationTextPane(
                            state = state,
                            onClear = controller::clearTranslation,
                            onExpand = { expandedPane = ExpandedPane.TRANSLATION },
                            onCopyDiagnostics = copyDiagnostics,
                            previewMaxLines = 5,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    SourceTextPane(
                        state = state,
                        running = running,
                        focusRequester = focusRequester,
                        onSourceChange = controller::updateSource,
                        onClear = controller::clearSource,
                        onExpand = { expandedPane = ExpandedPane.SOURCE },
                        modifier = Modifier.weight(0.9f),
                    )
                    TranslationTextPane(
                        state = state,
                        onClear = controller::clearTranslation,
                        onExpand = { expandedPane = ExpandedPane.TRANSLATION },
                        onCopyDiagnostics = copyDiagnostics,
                        previewMaxLines = 10,
                        modifier = Modifier.weight(1.1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationSelectors(
    targetLanguage: TargetLanguage,
    preference: TranslationPreference,
    enabled: Boolean,
    onSelectTarget: (TargetLanguage) -> Unit,
    onSelectPreference: (TranslationPreference) -> Unit,
) {
    var targetExpanded by remember { mutableStateOf(false) }
    var preferenceExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { targetExpanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().automationTag("target_selector"),
            ) {
                Text(
                    "译为 · ${targetLanguage.displayName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = targetExpanded,
                onDismissRequest = { targetExpanded = false },
            ) {
                TargetLanguage.entries.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.displayName) },
                        onClick = {
                            onSelectTarget(target)
                            targetExpanded = false
                        },
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { preferenceExpanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().automationTag("preference_selector"),
            ) {
                Text(
                    "偏好 · ${preference.displayName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = preferenceExpanded,
                onDismissRequest = { preferenceExpanded = false },
            ) {
                TranslationPreference.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            onSelectPreference(option)
                            preferenceExpanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTextPane(
    state: TranslationSessionState,
    running: Boolean,
    focusRequester: FocusRequester,
    onSourceChange: (String) -> Unit,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaneHeader(
                title = "原文",
                count = unicodeCharacterCount(state.sourceText),
                canClear = state.sourceText.isNotEmpty() && !running,
                onClear = onClear,
                onExpand = onExpand,
                clearDescription = "清空原文",
                expandDescription = "全屏编辑原文",
                tagPrefix = "source",
            )
            OutlinedTextField(
                value = state.sourceText,
                onValueChange = onSourceChange,
                placeholder = { Text("输入或粘贴要翻译的文字") },
                minLines = 1,
                maxLines = 12,
                enabled = !running,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .automationTag("source_text"),
            )
        }
    }
}

@Composable
private fun TranslationTextPane(
    state: TranslationSessionState,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    onCopyDiagnostics: (() -> Unit)?,
    previewMaxLines: Int,
    modifier: Modifier,
) {
    val error = state.progress is TranslationProgress.Failed
    val contentColor = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val mutedColor = contentColor
    Card(
        modifier = modifier.fillMaxWidth().automationTag("translation_result"),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaneHeader(
                title = "译文",
                count = unicodeCharacterCount(state.translatedText),
                canClear = state.translatedText.isNotEmpty() && state.progress !is TranslationProgress.Running,
                onClear = onClear,
                onExpand = onExpand,
                clearDescription = "清空译文",
                expandDescription = "全屏查看译文",
                tagPrefix = "translation",
                contentColor = contentColor,
            )
            Text(
                translationStatus(state.progress, state.translatedText),
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionContainer {
                Text(
                    state.translatedText.ifBlank { "暂无译文" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.translatedText.isBlank()) mutedColor else contentColor,
                    maxLines = previewMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.automationTag("translation_text"),
                )
            }
            onCopyDiagnostics?.let {
                TextButton(onClick = it) { Text("复制脱敏诊断") }
            }
        }
    }
}

@Composable
private fun PaneHeader(
    title: String,
    count: Int,
    canClear: Boolean,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    clearDescription: String,
    expandDescription: String,
    tagPrefix: String,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
        Text(
            " · $count 字",
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.automationTag("${tagPrefix}_count"),
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier.size(48.dp).automationTag("clear_$tagPrefix"),
        ) {
            Icon(painterResource(R.drawable.ic_clear_content), contentDescription = clearDescription)
        }
        IconButton(
            onClick = onExpand,
            modifier = Modifier.size(48.dp).automationTag("expand_$tagPrefix"),
        ) {
            Icon(painterResource(R.drawable.ic_expand_content), contentDescription = expandDescription)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedTextScreen(
    pane: ExpandedPane,
    state: TranslationSessionState,
    running: Boolean,
    onSourceChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val text = if (pane == ExpandedPane.SOURCE) state.sourceText else state.translatedText
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text("${if (pane == ExpandedPane.SOURCE) "原文" else "译文"} · ${unicodeCharacterCount(text)} 字")
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    IconButton(
                        onClick = onClear,
                        enabled = text.isNotEmpty() && !running,
                        modifier = Modifier.automationTag("expanded_clear"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_clear_content),
                            contentDescription = if (pane == ExpandedPane.SOURCE) "清空原文" else "清空译文",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        if (pane == ExpandedPane.SOURCE) {
            OutlinedTextField(
                value = state.sourceText,
                onValueChange = onSourceChange,
                enabled = !running,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(16.dp)
                    .automationTag("expanded_source_text"),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    translationStatus(state.progress, state.translatedText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        state.translatedText.ifBlank { "暂无译文" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.automationTag("expanded_translation_text"),
                    )
                }
            }
        }
    }
}

private fun translationStatus(progress: TranslationProgress, translatedText: String): String = when (progress) {
    TranslationProgress.Idle -> "等待输入"
    TranslationProgress.Running -> if (translatedText.isEmpty()) "正在连接…" else "正在翻译…"
    is TranslationProgress.Completed -> "翻译完成 · ${progress.diagnostics.totalMs}ms"
    is TranslationProgress.Failed -> if (progress.incomplete) "结果不完整：${progress.message}" else progress.message
    TranslationProgress.Cancelled -> "已取消"
}

@Composable
private fun TranslationActionBar(
    state: TranslationSessionState,
    running: Boolean,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    Surface(tonalElevation = 0.dp, shadowElevation = 4.dp) {
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
