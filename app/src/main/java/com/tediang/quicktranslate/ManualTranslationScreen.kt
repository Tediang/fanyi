package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
                        surface = AppSurface.PROFILES
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
    val focusManager = LocalFocusManager.current
    val sourceFocusRequester = remember { FocusRequester() }
    val state by controller.state.collectAsState()
    var expandedPane by rememberSaveable { mutableStateOf<ExpandedPane?>(null) }
    var showPreferenceSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(launch.id) {
        withFrameNanos { }
        sourceFocusRequester.requestFocus()
    }

    val running = state.progress is TranslationProgress.Running

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
                modifier = Modifier.clearFocusOnUnconsumedTap { focusManager.clearFocus() },
                title = { Text(launch.entry.title) },
                navigationIcon = {
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        onClose()
                    }) { Text("关闭") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            showPreferenceSheet = true
                        },
                        enabled = !running,
                        modifier = Modifier.automationTag("preference_selector"),
                    ) {
                        Text("风格 · ${state.preference.displayName}")
                    }
                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            onOpenProfiles()
                        },
                        enabled = !running,
                    ) { Text("供应商") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            TranslationActionBar(
                state = state,
                running = running,
                onDismissFocus = { focusManager.clearFocus() },
                onSelectTarget = {
                    focusManager.clearFocus()
                    controller.selectTarget(it)
                },
                onCopy = {
                    focusManager.clearFocus()
                    copyText(context, "快译译文", state.translatedText)
                    scope.launch { snackbarHostState.showSnackbar("译文已复制") }
                },
                onPrimaryAction = {
                    focusManager.clearFocus()
                    if (running) controller.cancel() else controller.start(profile)
                },
            )
        },
    ) { contentPadding ->
        val pasteSource = {
            focusManager.clearFocus()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString().orEmpty()
            if (text.isNotBlank()) controller.updateSource(text)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnUnconsumedTap { focusManager.clearFocus() }
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
                if (landscape) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SourceTextPane(
                            state = state,
                            running = running,
                            focusRequester = sourceFocusRequester,
                            onSourceChange = controller::updateSource,
                            onPaste = pasteSource,
                            onClear = {
                                focusManager.clearFocus()
                                controller.clearSource()
                            },
                            onExpand = {
                                focusManager.clearFocus()
                                expandedPane = ExpandedPane.SOURCE
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TranslationTextPane(
                            state = state,
                            onClear = {
                                focusManager.clearFocus()
                                controller.clearTranslation()
                            },
                            onExpand = {
                                focusManager.clearFocus()
                                expandedPane = ExpandedPane.TRANSLATION
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    SourceTextPane(
                        state = state,
                        running = running,
                        focusRequester = sourceFocusRequester,
                        onSourceChange = controller::updateSource,
                        onPaste = pasteSource,
                        onClear = {
                            focusManager.clearFocus()
                            controller.clearSource()
                        },
                        onExpand = {
                            focusManager.clearFocus()
                            expandedPane = ExpandedPane.SOURCE
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TranslationTextPane(
                        state = state,
                        onClear = {
                            focusManager.clearFocus()
                            controller.clearTranslation()
                        },
                        onExpand = {
                            focusManager.clearFocus()
                            expandedPane = ExpandedPane.TRANSLATION
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showPreferenceSheet) {
        TranslationPreferenceSheet(
            preference = state.preference,
            onDismiss = { showPreferenceSheet = false },
            onSelectPreference = {
                onSelectPreference(it)
                showPreferenceSheet = false
            },
        )
    }
}

@Composable
private fun SourceTextPane(
    state: TranslationSessionState,
    running: Boolean,
    focusRequester: FocusRequester,
    onSourceChange: (String) -> Unit,
    onPaste: () -> Unit,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, top = 0.dp, end = 8.dp, bottom = 8.dp),
        ) {
            PaneHeader(
                title = "原文",
                count = unicodeCharacterCount(state.sourceText),
                canClear = state.sourceText.isNotEmpty() && !running,
                onClear = onClear,
                onExpand = onExpand,
                centerActionLabel = "粘贴原文",
                onCenterAction = onPaste,
                centerActionEnabled = !running,
                clearDescription = "清空原文",
                expandDescription = "全屏编辑原文",
                tagPrefix = "source",
            )
            BasicTextField(
                value = state.sourceText,
                onValueChange = onSourceChange,
                enabled = !running,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .automationTag("source_text"),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        if (state.sourceText.isEmpty()) {
                            Text(
                                "输入或粘贴要翻译的文字",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun TranslationTextPane(
    state: TranslationSessionState,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val elapsedMillis = rememberTranslationElapsedMillis(state.progress)
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
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, top = 0.dp, end = 8.dp, bottom = 8.dp),
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
                centerLabel = translationStatus(state.progress, state.translatedText, elapsedMillis),
            )
            TranslationPreview(
                text = state.translatedText.ifBlank { "暂无译文" },
                textColor = if (state.translatedText.isBlank()) mutedColor else contentColor,
                scrollBarColor = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TranslationPreview(
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    scrollBarColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.automationTag("translation_scroll"),
    ) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    .automationTag("translation_text"),
            )
        }

        if (scrollState.maxValue > 0 && scrollState.viewportSize > 0) {
            val trackHeightPx = with(density) { maxHeight.toPx() - 12.dp.toPx() }
            val minThumbHeightPx = with(density) { 24.dp.toPx() }.coerceAtMost(trackHeightPx)
            val contentHeightPx = scrollState.viewportSize + scrollState.maxValue
            val thumbHeightPx = (trackHeightPx * scrollState.viewportSize / contentHeightPx)
                .coerceIn(minThumbHeightPx, trackHeightPx)
            val thumbOffsetPx = (trackHeightPx - thumbHeightPx) *
                scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp, top = 6.dp, bottom = 6.dp)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(scrollBarColor.copy(alpha = 0.10f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .padding(end = 2.dp, top = 6.dp)
                    .width(4.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(scrollBarColor.copy(alpha = 0.55f))
                    .automationTag("translation_scrollbar"),
            )
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
    centerActionLabel: String? = null,
    onCenterAction: (() -> Unit)? = null,
    centerActionEnabled: Boolean = true,
    centerLabel: String? = null,
    clearDescription: String,
    expandDescription: String,
    tagPrefix: String,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        }

        if (centerActionLabel != null && onCenterAction != null) {
            TextButton(
                onClick = onCenterAction,
                enabled = centerActionEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = contentColor.copy(alpha = 0.78f),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .heightIn(min = 48.dp)
                    .automationTag("paste_source"),
            ) {
                Text(
                    centerActionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (centerActionLabel == null && centerLabel != null) {
            Text(
                centerLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.52f)
                    .automationTag("${tagPrefix}_status"),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
    val elapsedMillis = rememberTranslationElapsedMillis(state.progress)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Text(
                            if (pane == ExpandedPane.SOURCE) {
                                "原文 · ${unicodeCharacterCount(text)} 字"
                            } else {
                                "译文 · ${unicodeCharacterCount(text)} 字"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pane == ExpandedPane.TRANSLATION) {
                            Text(
                                translationStatus(state.progress, state.translatedText, elapsedMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.automationTag("expanded_translation_status"),
                            )
                        }
                    }
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
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.automationTag("expanded_collapse"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_collapse_content),
                            contentDescription = "收回窗口",
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

@Composable
private fun rememberTranslationElapsedMillis(progress: TranslationProgress): Long {
    val startedAt = (progress as? TranslationProgress.Running)?.startedAtElapsedRealtime
    var elapsedMillis by remember(startedAt) {
        mutableStateOf(startedAt?.let { (monotonicElapsedRealtime() - it).coerceAtLeast(0L) } ?: 0L)
    }
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        while (true) {
            elapsedMillis = (monotonicElapsedRealtime() - startedAt).coerceAtLeast(0L)
            delay(100L)
        }
    }
    return elapsedMillis
}

private fun monotonicElapsedRealtime(): Long = System.nanoTime() / 1_000_000L

private fun translationStatus(
    progress: TranslationProgress,
    translatedText: String,
    elapsedMillis: Long,
): String = when (progress) {
    TranslationProgress.Idle -> "等待输入"
    is TranslationProgress.Running -> {
        val phase = if (translatedText.isEmpty()) "正在连接…" else "正在翻译…"
        "$phase · 耗时 ${elapsedMillis}ms"
    }
    is TranslationProgress.Completed -> "翻译完成 · 耗时 ${progress.diagnostics.totalMs}ms"
    is TranslationProgress.Failed -> if (progress.incomplete) "结果不完整：${progress.message}" else progress.message
    TranslationProgress.Cancelled -> "已取消"
}

@Composable
private fun TranslationActionBar(
    state: TranslationSessionState,
    running: Boolean,
    onDismissFocus: () -> Unit,
    onSelectTarget: (TargetLanguage) -> Unit,
    onCopy: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val barModifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(
            start = 16.dp,
            top = 10.dp,
            end = 16.dp,
            bottom = if (landscape) 16.dp else 24.dp,
        )
    Surface(
        modifier = Modifier.clearFocusOnUnconsumedTap(onDismissFocus),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = barModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TargetLanguageRow(
                selectedLanguage = state.targetLanguage,
                enabled = !running,
                onSelect = onSelectTarget,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TranslationSecondaryButton(state, running, onCopy, Modifier.weight(1f))
                TranslationPrimaryButton(state, running, onPrimaryAction, Modifier.weight(1.35f))
            }
        }
    }
}

@Composable
private fun TargetLanguageRow(
    selectedLanguage: TargetLanguage,
    enabled: Boolean,
    onSelect: (TargetLanguage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .automationTag("target_selector"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetLanguage.entries.forEach { language ->
            val buttonModifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .semantics { selected = language == selectedLanguage }
                .automationTag("choice_target_${language.name}")
            val label = when (language) {
                TargetLanguage.SIMPLIFIED_CHINESE -> "中文"
                TargetLanguage.ENGLISH -> "英文"
                TargetLanguage.JAPANESE -> "日文"
                TargetLanguage.KOREAN -> "韩文"
            }
            if (language == selectedLanguage) {
                FilledTonalButton(
                    onClick = { onSelect(language) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = buttonModifier,
                ) {
                    Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(language) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = buttonModifier,
                ) {
                    Text(label, maxLines = 1, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun TranslationSecondaryButton(
    state: TranslationSessionState,
    running: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = onCopy,
        enabled = !running && state.translatedText.isNotBlank(),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Text("复制译文")
    }
}

@Composable
private fun TranslationPrimaryButton(
    state: TranslationSessionState,
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        enabled = running || state.sourceText.isNotBlank(),
        modifier = modifier
            .heightIn(min = 52.dp)
            .automationTag("translate_button"),
    ) {
        Text(
            when {
                running -> "取消翻译"
                state.translatedText.isNotBlank() -> "重新翻译"
                else -> "翻译"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationPreferenceSheet(
    preference: TranslationPreference,
    onDismiss: () -> Unit,
    onSelectPreference: (TranslationPreference) -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("翻译风格", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "随时切换表达方式，不影响供应商配置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = TranslationPreference.entries.map { option ->
                ChoiceOption(
                    label = option.displayName,
                    description = when (option) {
                        TranslationPreference.GENERAL -> "自然流畅"
                        TranslationPreference.FORMAL -> "专业严谨"
                        TranslationPreference.CONVERSATIONAL -> "日常自然"
                        TranslationPreference.CORRESPONDENCE -> "礼貌得体"
                        TranslationPreference.ACADEMIC -> "客观准确"
                        TranslationPreference.LITERARY -> "保留韵味"
                    },
                    selected = option == preference,
                    tag = "choice_preference_${option.name}",
                ) { onSelectPreference(option) }
            }
            val columnCount = if (landscape) 3 else 2
            val optionHeight = if (landscape) 68.dp else 76.dp
            options.chunked(columnCount).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowOptions.forEach { option ->
                        TranslationStyleButton(
                            option = option,
                            modifier = Modifier.weight(1f),
                            minHeight = optionHeight,
                        )
                    }
                    repeat(columnCount - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationStyleButton(
    option: ChoiceOption,
    modifier: Modifier,
    minHeight: androidx.compose.ui.unit.Dp,
) {
    val buttonModifier = modifier
        .heightIn(min = minHeight)
        .semantics { selected = option.selected }
        .automationTag(option.tag)
    if (option.selected) {
        FilledTonalButton(
            onClick = option.onClick,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = buttonModifier,
        ) {
            TranslationStyleButtonContent(option, selected = true)
        }
    } else {
        OutlinedButton(
            onClick = option.onClick,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = buttonModifier,
        ) {
            TranslationStyleButtonContent(option, selected = false)
        }
    }
}

@Composable
private fun TranslationStyleButtonContent(option: ChoiceOption, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            option.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        Text(
            option.description,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ChoiceOption(
    val label: String,
    val description: String,
    val selected: Boolean,
    val tag: String,
    val onClick: () -> Unit,
)

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

private fun Modifier.clearFocusOnUnconsumedTap(onTap: () -> Unit): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Final)
        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
        if (up != null && !down.isConsumed && !up.isConsumed) onTap()
    }
}

internal fun Modifier.automationTag(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }

private const val PENDING_PROFILE = "__pending__"
