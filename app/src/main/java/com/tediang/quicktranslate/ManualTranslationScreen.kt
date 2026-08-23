package com.tediang.quicktranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ManualTranslationApp(
    configStore: EncryptedServiceConfigStore,
    client: ChatCompletionsClient,
    onClose: () -> Unit,
) {
    var config by remember { mutableStateOf(configStore.load()) }
    var editingConfig by rememberSaveable { mutableStateOf(config == null) }

    if (editingConfig || config == null) {
        ServiceConfigScreen(
            initialConfig = config,
            canCancel = config != null,
            onCancel = { editingConfig = false },
            onSave = {
                configStore.save(it)
                config = it
                editingConfig = false
            },
        )
    } else {
        TranslationScreen(
            config = requireNotNull(config),
            client = client,
            onEditConfig = { editingConfig = true },
            onClose = onClose,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceConfigScreen(
    initialConfig: ServiceConfig?,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (ServiceConfig) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialConfig?.name.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(initialConfig?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initialConfig?.apiKey.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(initialConfig?.model.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val errors = validateConfig(name, baseUrl, model)

    BackHandler(enabled = canCancel, onBack = onCancel)
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text(if (initialConfig == null) "配置翻译服务" else "编辑翻译服务") },
                navigationIcon = {
                    if (canCancel) TextButton(onClick = onCancel) { Text("返回") }
                },
            )
        },
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
                "先配置一个 OpenAI Chat Completions 兼容服务。API Key 会在本机加密保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("配置名称") },
                supportingText = errorText(submitted, errors.name, "例如：DeepSeek") ,
                isError = submitted && errors.name != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("config_name"),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                supportingText = errorText(submitted, errors.baseUrl, "例如：https://api.deepseek.com"),
                isError = submitted && errors.baseUrl != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("base_url"),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（可选）") },
                supportingText = { Text("不会显示在普通配置数据或日志中") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("api_key"),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                supportingText = errorText(submitted, errors.model, "例如：deepseek-chat"),
                isError = submitted && errors.model != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("model"),
            )
            Button(
                onClick = {
                    submitted = true
                    if (errors.isEmpty) {
                        onSave(
                            ServiceConfig(
                                name = name.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().automationTag("save_config"),
            ) {
                Text("保存并继续")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationScreen(
    config: ServiceConfig,
    client: ChatCompletionsClient,
    onEditConfig: () -> Unit,
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
                actions = { TextButton(onClick = onEditConfig, enabled = !loading) { Text("设置") } },
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
                "${config.name} · ${config.model}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
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
                    loading = true
                    result = ""
                    status = "正在连接…"
                    scope.launch {
                        runCatching {
                            client.translate(config, sourceText.trim()) { delta ->
                                result += delta
                                status = "正在翻译…"
                            }
                        }.onSuccess {
                            status = "翻译完成"
                        }.onFailure {
                            status = "翻译失败：${it.message ?: "未知错误"}"
                        }
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
                ) {
                    Text("复制译文")
                }
                OutlinedButton(
                    onClick = {
                        sourceText = ""
                        result = ""
                        status = "等待输入"
                    },
                    enabled = !loading && (sourceText.isNotEmpty() || result.isNotEmpty()),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空")
                }
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

@Composable
private fun errorText(showError: Boolean, error: String?, hint: String): @Composable (() -> Unit) = {
    Text(if (showError && error != null) error else hint)
}

private data class ConfigErrors(
    val name: String?,
    val baseUrl: String?,
    val model: String?,
) {
    val isEmpty: Boolean get() = name == null && baseUrl == null && model == null
}

private fun validateConfig(name: String, baseUrl: String, model: String): ConfigErrors {
    val uri = runCatching { Uri.parse(baseUrl.trim()) }.getOrNull()
    val validUrl = uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()
    return ConfigErrors(
        name = if (name.isBlank()) "请输入配置名称" else null,
        baseUrl = if (validUrl) null else "请输入完整的 http 或 https 地址",
        model = if (model.isBlank()) "请输入模型名称" else null,
    )
}

private fun copyTranslation(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("快译译文", text))
}

private fun Modifier.automationTag(tag: String): Modifier =
    testTag(tag).semantics { testTagsAsResourceId = true }
