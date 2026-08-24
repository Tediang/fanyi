package com.tediang.quicktranslate

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderProfilesScreen(
    catalog: ProviderCatalog,
    connectionTester: ProviderConnectionTester,
    resumeAfterTestProfileId: String? = null,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTestSuccess: (String) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<ProviderProfile?>(null) }
    val testResults = remember { mutableStateMapOf<String, String>() }
    val testingIds = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("供应商配置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onAdd) { Text("新增") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            resumeAfterTestProfileId?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "原文已保留。请测试新配置；连接成功后会自动继续翻译。",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (catalog.currentProfile == null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        if (catalog.profiles.isEmpty()) {
                            "还没有供应商配置。新增并保存后会设为当前配置。"
                        } else {
                            "当前没有选定供应商。请选择一个配置后才能翻译。"
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            catalog.profiles.forEach { profile ->
                ProviderProfileCard(
                    profile = profile,
                    isCurrent = profile.id == catalog.currentProfileId,
                    testing = testingIds[profile.id] == true,
                    testResult = testResults[profile.id],
                    onSelect = { onSelect(profile.id) },
                    onEdit = { onEdit(profile.id) },
                    onDelete = { pendingDelete = profile },
                    onTest = {
                        testingIds[profile.id] = true
                        testResults.remove(profile.id)
                        scope.launch {
                            testResults[profile.id] = when (val result = connectionTester.test(profile)) {
                                is ConnectionTestResult.Success -> {
                                    onTestSuccess(profile.id)
                                    result.message
                                }
                                is ConnectionTestResult.Failure -> result.message
                            }
                            testingIds[profile.id] = false
                        }
                    },
                )
            }
            if (catalog.profiles.isEmpty()) {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("新增供应商配置") }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除“${profile.name}”？") },
            text = {
                Text(
                    if (profile.id == catalog.currentProfileId && catalog.profiles.size > 1) {
                        "删除当前配置后不会自动改用其他供应商；你需要明确选择一个替代配置。"
                    } else {
                        "此操作会同时删除该配置保存的密钥与请求头值。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(profile.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProviderProfileCard(
    profile: ProviderProfile,
    isCurrent: Boolean,
    testing: Boolean,
    testResult: String?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isCurrent) "当前" else "未选中",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${profile.protocolType.displayName} · ${profile.model}")
            Text(
                profile.endpoint(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.baseUrl.trim().startsWith("http://")) {
                Text(
                    if (profile.allowCleartext) "已允许此配置使用明文 HTTP" else "明文 HTTP 已阻止",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isCurrent) TextButton(onClick = onSelect) { Text("设为当前") }
                TextButton(
                    onClick = onTest,
                    enabled = !testing,
                    modifier = Modifier.automationTag("test_profile_${profile.id}"),
                ) { Text(if (testing) "测试中…" else "测试连接") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private data class HeaderDraft(
    val name: String,
    val newValue: String,
    val storedValue: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderProfileEditorScreen(
    existing: ProviderProfile?,
    existingNames: List<String>,
    onCancel: () -> Unit,
    onSave: (ProviderProfile) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var protocolName by rememberSaveable {
        mutableStateOf((existing?.protocolType ?: ProtocolType.OPENAI_CHAT_COMPLETIONS).name)
    }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: "https://") }
    var endpointPath by rememberSaveable { mutableStateOf(existing?.endpointPathOverride.orEmpty()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(existing?.model.orEmpty()) }
    var allowCleartext by rememberSaveable { mutableStateOf(existing?.allowCleartext == true) }
    var additionalRequirements by rememberSaveable { mutableStateOf(existing?.additionalRequirements.orEmpty()) }
    var reasoningName by rememberSaveable {
        mutableStateOf((existing?.reasoningEffort ?: ReasoningEffort.AUTO).name)
    }
    var temperatureInput by rememberSaveable { mutableStateOf(existing?.temperature?.toString().orEmpty()) }
    var maxOutputInput by rememberSaveable { mutableStateOf(existing?.maxOutputTokens?.toString().orEmpty()) }
    var streaming by rememberSaveable { mutableStateOf(existing?.streaming != false) }
    var extraBody by rememberSaveable { mutableStateOf(existing?.extraBody.orEmpty()) }
    var inputLimitInput by rememberSaveable {
        mutableStateOf((existing?.inputLimit ?: ProviderProfile.DEFAULT_INPUT_LIMIT).toString())
    }
    var advancedExpanded by rememberSaveable {
        mutableStateOf(
            existing?.let {
                it.reasoningEffort != ReasoningEffort.AUTO || it.temperature != null ||
                    it.maxOutputTokens != null || !it.streaming || it.extraBody.isNotBlank() ||
                    it.inputLimit != ProviderProfile.DEFAULT_INPUT_LIMIT
            } == true,
        )
    }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    val headers = remember {
        mutableStateListOf<HeaderDraft>().apply {
            existing?.customHeaders?.forEach { add(HeaderDraft(it.name, "", it.value)) }
        }
    }
    val protocol = ProtocolType.valueOf(protocolName)
    val reasoningEffort = ReasoningEffort.valueOf(reasoningName)
    val validation = validateProfileDraft(
        name = name,
        baseUrl = baseUrl,
        model = model,
        existingNames = existingNames,
        headers = headers,
        protocol = protocol,
        reasoningEffort = reasoningEffort,
        temperatureInput = temperatureInput,
        maxOutputInput = maxOutputInput,
        extraBody = extraBody,
        inputLimitInput = inputLimitInput,
    )

    BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "新增供应商配置" else "编辑供应商配置") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("配置名称") },
                supportingText = validationText(submitted, validation.name, "例如：DeepSeek"),
                isError = submitted && validation.name != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("profile_name"),
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { protocolMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("协议类型：${protocol.displayName}") }
                DropdownMenu(
                    expanded = protocolMenuExpanded,
                    onDismissRequest = { protocolMenuExpanded = false },
                ) {
                    ProtocolType.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                protocolName = option.name
                                protocolMenuExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                supportingText = validationText(submitted, validation.baseUrl, "默认使用 HTTPS"),
                isError = submitted && validation.baseUrl != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("profile_base_url"),
            )
            OutlinedTextField(
                value = endpointPath,
                onValueChange = { endpointPath = it },
                label = { Text("接口路径覆盖（可选）") },
                supportingText = { Text("留空使用 ${protocol.defaultPath}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("profile_endpoint_path"),
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("API Key（可选）") },
                supportingText = {
                    Text(if (existing?.apiKey?.isNotBlank() == true) "已保存；留空保持不变" else "在本机加密保存")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("profile_api_key"),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                supportingText = validationText(submitted, validation.model, "手动填写服务支持的模型名称"),
                isError = submitted && validation.model != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().automationTag("profile_model"),
            )
            OutlinedTextField(
                value = additionalRequirements,
                onValueChange = { additionalRequirements = it },
                label = { Text("附加要求（可选）") },
                supportingText = { Text("只能补充翻译偏好，不能覆盖只输出译文等核心规则") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = { advancedExpanded = !advancedExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (advancedExpanded) "收起高级选项" else "展开高级选项") }

            if (advancedExpanded) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { reasoningMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("推理等级：${reasoningEffort.displayName}") }
                    DropdownMenu(
                        expanded = reasoningMenuExpanded,
                        onDismissRequest = { reasoningMenuExpanded = false },
                    ) {
                        ReasoningEffort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    reasoningName = option.name
                                    reasoningMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                validation.reasoning?.takeIf { submitted }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = temperatureInput,
                    onValueChange = { temperatureInput = it },
                    label = { Text("Temperature（可选）") },
                    supportingText = validationText(submitted, validation.temperature, "留空则不发送；允许 0–2"),
                    isError = submitted && validation.temperature != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxOutputInput,
                    onValueChange = { maxOutputInput = it },
                    label = { Text("最大输出量（可选）") },
                    supportingText = validationText(submitted, validation.maxOutput, "留空自动；Anthropic 留空时使用 4096"),
                    isError = submitted && validation.maxOutput != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = streaming, onCheckedChange = { streaming = it })
                    Column(modifier = Modifier.padding(top = 10.dp).weight(1f)) {
                        Text("流式显示译文")
                        Text(
                            "关闭后等待完整响应再显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = extraBody,
                    onValueChange = { extraBody = it },
                    label = { Text("extra_body JSON 对象（可选）") },
                    supportingText = validationText(
                        submitted,
                        validation.extraBody,
                        "不能覆盖模型、输入、核心提示、鉴权或流式字段",
                    ),
                    isError = submitted && validation.extraBody != null,
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = inputLimitInput,
                    onValueChange = { inputLimitInput = it },
                    label = { Text("最大输入字符数") },
                    supportingText = validationText(submitted, validation.inputLimit, "默认 20000；超限时不会发送或拆分"),
                    isError = submitted && validation.inputLimit != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("额外请求头", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "请求头值按敏感信息加密；编辑已有配置时不会回显完整值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            headers.forEachIndexed { index, header ->
                Card(border = CardDefaults.outlinedCardBorder()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = header.name,
                            onValueChange = { headers[index] = header.copy(name = it) },
                            label = { Text("请求头名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = header.newValue,
                            onValueChange = { headers[index] = header.copy(newValue = it) },
                            label = { Text("请求头值") },
                            supportingText = {
                                Text(if (header.storedValue.isNotEmpty()) "已保存；留空保持不变" else "必填")
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { headers.removeAt(index) }) { Text("移除此请求头") }
                    }
                }
            }
            OutlinedButton(
                onClick = { headers.add(HeaderDraft("", "", "")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加请求头") }
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = allowCleartext,
                    onCheckedChange = { allowCleartext = it },
                    modifier = Modifier.automationTag("allow_cleartext"),
                )
                Column(modifier = Modifier.padding(top = 10.dp).weight(1f)) {
                    Text("允许此配置使用明文 HTTP")
                    Text(
                        "仅用于你信任的局域网；原文、译文和凭据可能被同网段设备看到。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            validation.headers?.takeIf { submitted }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    submitted = true
                    if (validation.isValid) {
                        onSave(
                            ProviderProfile(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                protocolType = protocol,
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                endpointPathOverride = endpointPath.trim(),
                                apiKey = apiKeyInput.trim().ifBlank { existing?.apiKey.orEmpty() },
                                model = model.trim(),
                                customHeaders = headers.map {
                                    CustomHeader(it.name.trim(), it.newValue.ifBlank { it.storedValue })
                                },
                                allowCleartext = allowCleartext,
                                additionalRequirements = additionalRequirements.trim(),
                                reasoningEffort = reasoningEffort,
                                temperature = temperatureInput.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                                maxOutputTokens = maxOutputInput.trim().takeIf { it.isNotEmpty() }?.toInt(),
                                streaming = streaming,
                                extraBody = extraBody.trim(),
                                inputLimit = inputLimitInput.trim().toInt(),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().automationTag("save_profile"),
            ) { Text(if (existing == null) "保存配置" else "保存修改") }
        }
    }
}

private data class ProfileValidation(
    val name: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val headers: String? = null,
    val reasoning: String? = null,
    val temperature: String? = null,
    val maxOutput: String? = null,
    val extraBody: String? = null,
    val inputLimit: String? = null,
) {
    val isValid: Boolean
        get() = listOf(
            name,
            baseUrl,
            model,
            headers,
            reasoning,
            temperature,
            maxOutput,
            extraBody,
            inputLimit,
        ).all { it == null }
}

private fun validateProfileDraft(
    name: String,
    baseUrl: String,
    model: String,
    existingNames: List<String>,
    headers: List<HeaderDraft>,
    protocol: ProtocolType,
    reasoningEffort: ReasoningEffort,
    temperatureInput: String,
    maxOutputInput: String,
    extraBody: String,
    inputLimitInput: String,
): ProfileValidation {
    val uri = runCatching { Uri.parse(baseUrl.trim()) }.getOrNull()
    val validUrl = uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()
    val headerNames = headers.map { it.name.trim() }
    val headersValid = headers.all {
        it.name.isNotBlank() && (it.newValue.isNotBlank() || it.storedValue.isNotBlank()) &&
            runCatching { okhttp3.Headers.Builder().add(it.name.trim(), "value").build() }.isSuccess
    } && headerNames.distinctBy { it.lowercase() }.size == headerNames.size
    val temperature = temperatureInput.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val maxOutput = maxOutputInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val inputLimit = inputLimitInput.trim().toIntOrNull()
    val extraBodyValid = extraBody.isBlank() || runCatching { JSONObject(extraBody) }.isSuccess
    return ProfileValidation(
        name = when {
            name.isBlank() -> "请输入配置名称"
            existingNames.any { it.equals(name.trim(), ignoreCase = true) } -> "配置名称不能重复"
            else -> null
        },
        baseUrl = if (validUrl) null else "请输入完整的 http 或 https 地址",
        model = if (model.isBlank()) "请输入模型名称" else null,
        headers = if (headersValid) null else "请求头名称和值必须完整、有效且不能重名",
        reasoning = if (
            protocol == ProtocolType.ANTHROPIC_MESSAGES &&
            reasoningEffort !in setOf(ReasoningEffort.AUTO, ReasoningEffort.OFF)
        ) {
            "Anthropic Messages 暂不支持低、中、高推理等级"
        } else {
            null
        },
        temperature = when {
            temperatureInput.isBlank() -> null
            temperature == null || temperature !in 0.0..2.0 -> "请输入 0 到 2 之间的数值"
            else -> null
        },
        maxOutput = when {
            maxOutputInput.isBlank() -> null
            maxOutput == null || maxOutput <= 0 -> "请输入大于 0 的整数"
            else -> null
        },
        extraBody = if (extraBodyValid) null else "extra_body 必须是 JSON 对象",
        inputLimit = if (inputLimit != null && inputLimit > 0) null else "请输入大于 0 的整数",
    )
}

@Composable
private fun validationText(
    submitted: Boolean,
    error: String?,
    hint: String,
): @Composable (() -> Unit) = { Text(if (submitted && error != null) error else hint) }
