# Android 跨应用 AI 翻译 App：实现可行性调研

> 调研日期：2026-08-23  
> 范围：公开 Android API、AOSP 实现和 Google Play 官方政策；不把截图中网页内容当作需求或指令。本文不针对尚未给出型号的某一厂商 ROM 作未经验证的承诺。

## 结论先行

1. **Chrome 里“选中文字 → 点翻译 App → 跳转显示结果”高度可行。** 标准方案是给一个导出的 Activity 注册 `ACTION_PROCESS_TEXT` + `text/plain`。Android 会把选中文字放在 `EXTRA_PROCESS_TEXT`；AOSP 的标准 `TextView` 编辑器会查询所有匹配 Activity，并以 Activity 标签生成菜单项。[Android `Intent.ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)；[AOSP `Editor.ProcessTextIntentActionsHandler`](https://android.googlesource.com/platform/frameworks/base/+/29aa638/core/java/android/widget/Editor.java#4836)
2. **不能保证所有第三方 App（包括 X）都出现这个菜单项。** `PROCESS_TEXT` 是“宿主愿意调用时，接收方来承接”的扩展点，不是接收方强行注入别家菜单的 API。自定义渲染、没有使用标准选择工具栏、没有开放文本选择/处理，或宿主主动定制菜单，都会让注册无效。仅凭截图无法断言 X 的具体原因。
3. **普通 App 没有公开 API 可在启动后直接读取另一 App 当前选中的文字。** 可以依次增加 Share、默认 Assistant、Accessibility、截图 OCR 等入口，但越往后权限、兼容性、隐私与上架成本越高。
4. **AI 物理键能否被识别，取决于厂商如何映射按键。** 如果它只是“打开所选 App”，启动 Intent 往往与点击桌面图标相同，应用不能可靠区分，除非厂商传入专用 action/extra、共享启动者身份，或提供 SDK。若按键调用的是 Android 的全局 Assistant 通道，则 `VoiceInteractionSession` 有官方的 `SHOW_SOURCE_PUSH_TO_TALK`（物理按钮来源）标志，并可接收当前页面结构/截图；这是能力最强但产品代价也最大的路径。[VoiceInteractionSession 来源标志](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#SHOW_SOURCE_PUSH_TO_TALK)
5. **推荐先做一个无敏感权限的 MVP：`PROCESS_TEXT` + 接收文本分享 + 手动粘贴 +“翻译剪贴板”App Shortcut。** 它覆盖 Chrome 等标准实现，并可利用 Moto X70 Air 的 AI 键快捷方式选择器，快、稳定且上架阻力最低。只有实机验证确认 X 的全局取词价值足够，才研究 Accessibility 增强版。

## 1. 文本选择菜单：`ACTION_PROCESS_TEXT`

### 1.1 接入方式

最小清单大致如下（Android 12+ 对带 intent filter 的外部入口需要明确 `exported`）：

```xml
<activity
    android:name=".ProcessTextActivity"
    android:exported="true"
    android:label="翻译"
    android:theme="@style/Theme.Translate.Compact">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

Activity 读取：

```kotlin
val source = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
```

`ACTION_PROCESS_TEXT` 从 API 23 起存在，输入文本在 `EXTRA_PROCESS_TEXT`，`EXTRA_PROCESS_TEXT_READONLY` 告知原文字段能否被修改；处理结果也可通过 `EXTRA_PROCESS_TEXT` 返回。[Android Intent API](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)

对本产品的只读网页场景，点击后直接打开一个极简翻译页即可；若以后支持可编辑文本框，还可提供“替换原文”，通过 Activity result 返回处理后的文字。

### 1.2 菜单显示位置无法由翻译 App 保证

AOSP 的标准实现会：

- 查询 `ACTION_PROCESS_TEXT` + `text/plain` 的 Activity；
- 使用目标 Activity 的 label 作为菜单文案；
- 用 `SHOW_AS_ACTION_IF_ROOM` 添加项目。

因此把 Activity label 设为“翻译”可以影响名称，但**能否在第一层、位于第几个、是否进入右侧更多菜单，最终由宿主菜单空间、宿主排序、系统版本和 ROM 决定**。接收方不能声明“永远第一个”。证据见 [AOSP `Editor.java` 4853–4864、4920–4935](https://android.googlesource.com/platform/frameworks/base/+/29aa638/core/java/android/widget/Editor.java#4853)。

截图中的“向 Grok 提问”很符合这类 `PROCESS_TEXT` Activity 的表现，但仅凭 UI 不能证明其内部实现。

### 1.3 为什么 X 里可能没有

标准 `TextView` 只有在 `canProcessText()` 且创建标准选择 ActionMode 时才填充这些外部活动；同时宿主能通过自定义 `ActionMode.Callback` 改造选择菜单。[AOSP 标准选择菜单生成流程](https://android.googlesource.com/platform/frameworks/base/+/29aa638/core/java/android/widget/Editor.java#2713)；[`TextView.setCustomSelectionActionModeCallback`](https://developer.android.com/reference/android/widget/TextView#setCustomSelectionActionModeCallback(android.view.ActionMode.Callback))

所以可验证的结论是：

- 接收 App 只能声明“我能处理选中文字”；
- 宿主必须使用/兼容 Android 的相应选择机制，菜单项才会出现；
- Compose、自绘控件、Web/Surface 内容、跨节点选择或宿主自定义菜单可能有不同能力；
- X 当前为什么不显示，必须用目标机 + 目标 X 版本做黑盒测试，或由 X 官方实现资料确认，不能从两张 Chrome 截图推出。

## 2. 第三方 App 的官方后备入口

### 2.1 接收系统分享：低权限、建议与 MVP 同时做

为 Activity 再注册 `ACTION_SEND` + `text/plain`，即可在发起方分享文字时出现在 Android Sharesheet；官方接收文档明确说明，匹配的接收 Activity 会成为分享目标。[接收其他 App 的简单数据](https://developer.android.com/develop/ui/compose/sharing/receive)

局限也很明确：

- 发起方必须提供“分享”并发送所选文字；
- 很多社交 App 分享的是帖子 URL，而不是用户当前选择片段；
- 接收方无法要求发起方改变它分享的内容。

这是 `PROCESS_TEXT` 最合适的互补，不需要 Accessibility 或悬浮窗。

### 2.2 剪贴板：可做显式入口，不应当作后台监听方案

用户先“复制”，再从 App、快捷设置 Tile 或通知入口打开翻译，可读取剪贴板。它比手输方便，但多一步；Android 12+ 在读取其他 App 剪贴板时会提示用户，Android 13+ 还会显示系统复制反馈。[Android 12 剪贴板访问提示](https://developer.android.com/about/versions/12/behavior-changes-all#clipboard-access-notifications)；[Android 13 剪贴板反馈](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste#feedback)

注意：**仅仅在别的 App 里选中而未复制，文本并不会自动进入剪贴板。**

### 2.3 AccessibilityService：覆盖面较大，但不保证、上架成本高

Accessibility 服务在用户主动启用后可以：

- 查询活动窗口的无障碍节点（需 `canRetrieveWindowContent=true`）；
- 对节点读取 `getText()`，并在控件确实上报选择时读取 `getTextSelectionStart()/End()`；
- 显示 accessibility overlay；
- Android 11（API 30）起在声明截图能力后截图，Android 14 起可截指定窗口；安全窗口会返回 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`。

官方依据：[创建 Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service)、[`AccessibilityNodeInfo` 选择位置](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getTextSelectionStart())、[`AccessibilityService.takeScreenshot`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))。

但这不是“全平台可靠读取选择”的保证：

- 目标 App 必须把文字、选择位置和节点树正确暴露给无障碍框架；自绘/Surface/部分 Web 内容可能缺失；
- 选区可能跨多个节点，start/end 只对单节点有清晰意义；
- 安全窗口不可截；
- 服务长期访问全局 UI 是高敏感能力，和“简洁、无杂项”的产品定位存在信任冲突。

合理交互应是**用户明确按一次翻译触发 → 只读取当前窗口/当前选区 → 立即停止处理**，而不是持续采集屏幕。

### 2.4 普通悬浮窗：只解决入口，不解决取词

`TYPE_APPLICATION_OVERLAY` 能把小按钮/翻译卡显示在其他 App 上方，但需要用户在系统设置单独授予 `SYSTEM_ALERT_WINDOW`；官方文档强调“极少数 App 应使用”，且系统可调整其位置/可见性。Android 12 起，目标 App 还能主动隐藏这类 overlay。[`SYSTEM_ALERT_WINDOW`](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW)；[`TYPE_APPLICATION_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)；[Android 12 隐藏 overlay](https://developer.android.com/about/versions/12/features#hide-overlay-windows)

因此悬浮窗本身拿不到选择文本，仍需 Share、剪贴板、Accessibility 节点或截图 OCR；不应把它描述成万能翻译方案。

### 2.5 MediaProjection + OCR：技术可行，交互不够“秒开”

普通 App 可用 MediaProjection 获取整屏或单 App 窗口图像，再用本地 OCR（例如 ML Kit Text Recognition v2 支持中文、日文、韩文、拉丁文字等）识别。[Android MediaProjection](https://developer.android.com/media/grow/media-projection)；[ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2)

主要约束：

- Android 14+ 每次 capture session 都必须重新征得用户同意，一个 token 只能用于一次 `createVirtualDisplay()`；
- 需要 `mediaProjection` 类型前台服务及相应权限/通知；
- 截出整屏后仍需让用户框选/点选要翻译的区域，OCR 也会引入错误；
- 必须尊重其他 App 的 `FLAG_SECURE`，Google Play 禁止绕过。[Android 14 每会话同意](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent)；[Play 的 FLAG_SECURE 要求](https://support.google.com/googleplay/android-developer/answer/16559646#flag_secure)

因此它适合“翻译整个画面/图片”的显式模式，不适合替代长按菜单的一点即译。

## 3. AI 物理键：启动来源和上下文

这里必须先区分两种完全不同的厂商实现。

### 情形 A：按键设置只是“打开某个 App”

如果 ROM 只是解析所选 App 的 launcher Activity 并以普通 `MAIN/LAUNCHER` Intent 打开，那么接收 App 看到的很可能与点击桌面图标一致。Android 没有承诺会附带“来自 AI 键”标志。

可用信号及局限：

- `intent.action/data/extras`：只有厂商主动约定不同 action 或 extra 才可靠；
- `Activity.getReferrer()`：只有启动方提供 referrer 才有值，不是物理键标准信号；[Intent `EXTRA_REFERRER`](https://developer.android.com/reference/android/content/Intent#EXTRA_REFERRER)
- `Activity.getLaunchedFromPackage()`（API 34+）：启动方通常要用 `ActivityOptions.setShareIdentityEnabled(true)` 主动共享身份，否则可能返回 null；即使知道是某个系统设置/快捷键进程，也仍需厂商文档确认含义。[`getLaunchedFromPackage`](https://developer.android.com/reference/android/app/Activity#getLaunchedFromPackage())
- `KEYCODE_ASSIST` 与 `KEYCODE_VOICE_ASSIST` 被系统处理，官方明确写着不会投递给普通应用，所以不能指望 Activity 的 `onKeyDown()` 收到原始按键。[Android KeyEvent](https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_ASSIST)

**结论：**若厂商没有专用 Intent/SDK/OEM 合作，不能可靠区分“按 AI 键打开”和“点桌面图标打开”。可以把所有无文本的冷启动都设计成同一个极简“粘贴/输入/截屏”页，但不要用时间差等脆弱启发式冒充确定识别。

### 情形 B：按键调用全局 Assistant

如果按键接入 Android 的 Assistant/VoiceInteraction 通道，翻译 App 可申请成为系统 Assistant（角色是否可用需先检查），并实现 `VoiceInteractionService`、`VoiceInteractionSessionService` 和 `VoiceInteractionSession`。[Assistant role 条件](https://developer.android.com/reference/androidx/core/role/RoleManagerCompat#ROLE_ASSISTANT)；[实现自己的 Assistant](https://developer.android.com/training/articles/assistant#implementing_your_own_assistant)

这个通道能提供：

- `SHOW_SOURCE_PUSH_TO_TALK`：明确表示由物理按钮唤起；
- `onShow()` 参数中的 `EXTRA_ASSIST_INPUT_DEVICE_ID` 和按键时间；
- `SHOW_WITH_ASSIST` 下的当前前台 Activity、`AssistStructure`/`AssistContent`；
- `SHOW_WITH_SCREENSHOT` 下的当前截图。

官方详见 [`VoiceInteractionSession.onShow`](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#onShow(android.os.Bundle,%20int)) 和 [Assistant 上下文内容](https://developer.android.com/training/articles/assistant)。

但仍有硬限制：

- 用户需要把本 App 设为默认 Assistant，可能替代现有语音/AI 助手；
- 用户或设备策略可禁用 Assistant 的文字/截图上下文；目标 App 的安全窗口会让 AssistStructure 变为空壳、内容/截图不可用；[`AssistState.getAssistStructure`](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession.AssistState#getAssistStructure())
- 厂商的“AI 键打开 App”不一定走这一标准通道。只有在目标实机上观察到相应 show flag 才算验证成功。

这一路径最符合“在任意 App 中按物理键 → 读取当前画面 → 翻译”，但产品不再只是一个小翻译工具，而是系统默认 Assistant，需要独立评估。

## 4. Google Play 政策影响

### 4.1 AccessibilityService

翻译工具通常**不能**仅因为使用了 Accessibility 就声明 `isAccessibilityTool=true`；该标志只适用于核心目的为帮助残障人士使用设备的应用。非 accessibility tool 仍可能使用该 API，但必须：

- 在 Play Console 完成 Accessibility 声明并通过审核；
- 在应用正常流程中单独、显著披露访问什么数据、如何使用/分享；
- 取得用户明确肯定同意；
- 商店页面记录用途，并提交展示授权、拒绝及核心功能的审核视频；
- 有更窄范围 API 可实现时应优先用更窄 API。

官方政策：[Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)；[Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241#accessibility)

对本项目，这意味着 Accessibility 应是可选增强层，不宜成为第一版唯一主入口。

### 4.2 截图、所选文字和第三方大模型

把网页文字、聊天、帖子或屏幕截图发到云端 LLM，属于把用户数据传出设备。Google Play 要求透明披露访问、收集、使用与分享方式，并明确写明：使用第三方 AI 集成时，开发者仍对其数据行为与政策合规负责。[Google Play User Data 政策](https://support.google.com/googleplay/android-developer/answer/10144311)

上架前至少要做：

- 应用内隐私政策 + 商店隐私政策链接；
- Data safety 表准确披露 App、SDK 和 LLM 服务商的数据流；即使只做实时的 ephemeral processing，传出设备仍需纳入表单判断；[Data safety 填报说明](https://support.google.com/googleplay/android-developer/answer/10787469)
- 在触发翻译前让用户合理预期“选中文字会发送到所选 AI 服务”；屏幕 OCR 模式要单独说明可能包含画面中的其他敏感内容；
- 默认不保存原文/截图/历史，服务端禁用于训练（需与所选供应商合同和 API 设置一致），传输加密，设置短保留期与删除机制；
- 严格尊重 `FLAG_SECURE`，不尝试规避系统安全限制。

### 4.3 MediaProjection

以 Android 14+ 为目标时，需要声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION`、运行相应前台服务，并在 Play Console 声明前台服务类型；系统还会要求每次会话授权。[MediaProjection 前台服务要求](https://developer.android.com/media/grow/media-projection#foreground-service-permission)；[Play 前台服务声明](https://support.google.com/googleplay/android-developer/answer/13392821)

这些要求让“按键后无打扰地自动截别的 App”不可作为普通 MediaProjection 方案的承诺。

## 5. 推荐架构与分层 MVP

### Tier 0：先做兼容性探针（1–2 天）

先不接 LLM，只做一个显示收到 action、MIME type、文本长度、readonly、showFlags 的诊断 APK，在目标手机上验证：

1. Chrome 顶层还是更多菜单能否出现“翻译”；
2. X 的正文、回复、编辑框各自是否出现；
3. 分享入口传来的是选择文字、整帖文字还是 URL；
4. 本 App 的“翻译剪贴板”App Shortcut 是否出现在 AI 键选择器中，触发时专用 Intent 是否保持原样；
5. 该机能否选择默认 Assistant，按键是否触发 `SHOW_SOURCE_PUSH_TO_TALK`。

这一步能把“Android 理论可行”变成“这台设备实测可行”。

### Tier 1：Play 友好的核心 MVP（推荐）

入口：

- `ACTION_PROCESS_TEXT`；
- `ACTION_SEND text/plain`；
- 静态 App Shortcut“翻译剪贴板”，供 Moto AI 键绑定；
- 启动页手动粘贴/输入；
- 可选桌面/快捷设置入口，但不请求 Accessibility、overlay、MediaProjection。

内部模块：

- `InputRouter`：统一解析 Process Text、Share、Launcher；
- `TranslationUseCase`：语言自动识别、目标语言、取消/重试；
- `TranslationProvider`：屏蔽具体 LLM API，可切云端服务或自建代理；
- `TranslationScreen`：原文折叠、流式结果、复制、返回上一 App；
- `PrivacyBoundary`：日志脱敏、默认无历史、请求结束即清理内存状态。

速度体验建议：Activity 先本地瞬时显示原文和加载骨架；复用网络连接；流式展示结果；短文本结果做有边界的内存缓存；错误时保留原文并一键重试。菜单 Activity label 直接用“翻译”，不要放品牌长名。

### Tier 2：设备增强版

二选一，不要一开始全做：

- **Assistant 版**：仅当目标机 AI 键确实走全局 Assistant，且用户接受更改默认 Assistant。优先解析 `AssistStructure` 文字，缺失时才对系统提供的 screenshot 做本地 OCR。
- **Accessibility 版**：仅当 X 等核心 App 覆盖率足以证明价值，做成设置中的可选“屏幕取词增强”。用户显式触发后先尝试节点选择区间，再尝试当前节点/窗口，最后才截图 OCR；完成即丢弃画面。

### Tier 3：显式全屏翻译

MediaProjection + 本地 OCR + 用户框选区域，定位为“翻译整个屏幕/图片”的独立模式。它不应与一触即译混为一谈，因为每次授权与区域选择是平台安全模型的一部分。

## 6. 关键风险矩阵

| 路径 | Chrome 选区 | X 等自定义 App | AI 键上下文 | 用户摩擦 | Play 风险 | 建议 |
|---|---:|---:|---:|---:|---:|---|
| `PROCESS_TEXT` | 高 | 不确定/可能无 | 无 | 低 | 低 | MVP 核心 |
| `ACTION_SEND` | 中 | 取决于发起方 | 无 | 中 | 低 | MVP 互补 |
| 剪贴板 | 高（先复制） | 中（先复制） | 可在打开后粘贴 | 中 | 低 | 保底 |
| App Shortcut + 剪贴板 | 高（先复制） | 中（先复制） | **高：专用入口可识别** | 低到中 | 低 | Moto MVP 核心 |
| 默认 Assistant | 中到高 | 中到高 | **高（若按键走该通道）** | 高：更换默认助手 | 中 | 先实机验证 |
| Accessibility + 节点 | 高 | 中，依节点质量 | 可配入口 | 高：敏感授权 | **高** | 可选增强 |
| Accessibility 截图 + OCR | 高 | 较高，安全窗除外 | 可配入口 | 高 | **高** | 最后兜底 |
| MediaProjection + OCR | 高 | 高，安全窗/授权除外 | 无法静默 | **很高：每会话授权** | 中到高 | 独立模式 |

## 7. 需求澄清清单

下面问题会真正改变技术选型，建议在写产品规格前确认：

1. **目标设备的品牌、型号、Android 版本、系统版本是什么？** 请补一张 AI 键配置页截图；选项写的是“打开应用”还是“数字助理/AI 助手”？
2. **是否只做这台手机，还是要面向 Play 商店的多品牌通用 App？** 单机工具可以接受 ROM 特化；通用产品不能把 OEM 行为当标准。
3. **在 X 中，长按正文后实际有哪些菜单项？** 有“复制/分享”吗？分享得到的是选中文字还是帖子链接？正文、回复、编辑框要分别测试。
4. **“按 AI 键直接提供翻译”的输入到底是什么？** 当前选区、当前屏幕全部文字、用户随后框选区域，还是先读取剪贴板？普通 App 无法凭空知道用户想翻译哪一段。
5. **用户是否愿意把本 App 设为默认 Assistant？** 如果不愿意，就不要把 VoiceInteraction 作为主路线。
6. **用户是否愿意开启 Accessibility？是否必须通过 Google Play 上架？** 这决定是否值得承担审核和信任成本。
7. **结果形态是什么？** 跳完整 App 后按返回回原 App、底部小面板、还是悬浮卡片？当前需求说“跳转 App”，建议 MVP 先用透明/紧凑 Activity，避免 overlay 权限。
8. **语言范围与默认规则是什么？** 自动识别源语言？中文用户默认“非中文 → 简体中文，中文 → 英文”是否合适？繁体、日文、混合文本如何处理？
9. **速度的验收标准是什么？** 建议分别定义“点击到界面出现”“首个译文字符”“完整短句”的 P50/P95，而不是只写“快”。
10. **大模型和隐私方案是什么？** 用户自带 API Key、开发者代理还是订阅服务？供应商是否保留/训练数据？是否允许历史记录？这会改变成本、密钥安全和 Play 披露。
11. **翻译质量要什么风格？** 直译、自然表达、术语一致、上下文解释是否分模式？“只有翻译，不闲聊”可作为固定 system prompt 和 UI 约束。
12. **离线/断网是否必须可用？** 若必须，需要另设本地翻译模型；大语言模型云 API 本身不能保证离线。

## 8. 建议的产品边界（一句话规格）

第一版可以定义为：

> 一个无广告、默认不保存历史的极简 Android 翻译器；在兼容的 App 中通过选词菜单一键接收文本，也接收系统文本分享和手动粘贴；Moto X70 Air 的 AI 键可绑定“翻译剪贴板”快捷方式；立即显示页面并流式返回纯翻译结果，按返回键回到原 App。X 的全局取词作为目标机验证后再决定的增强功能。

这个边界能兑现“简洁、快”，同时不虚假承诺 Android 平台并未提供的“所有 App 无条件取词”。

## 9. Moto X70 Air 实机信息补充（2026-08-23）

用户确认目标设备为 Moto X70 Air、Android 16；AI 键设置页允许为单击和双击分别选择动作，并提供“启动应用”入口。应用选择页除了普通 App，还列出了 `AlipayHK - 付款`、`AlipayHK - 扫一扫` 等具体子动作。这强烈表明 Moto 的选择器会枚举 Android App Shortcuts，而不只是枚举 launcher Activity。

Android 官方允许应用发布静态或动态快捷方式；每个快捷方式都能携带一个进入应用特定动作的独立 Intent。[App shortcuts overview](https://developer.android.com/develop/ui/compose/system/shortcuts)；[Create shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts)

因此，本设备新增一条优先验证路径：

1. 翻译 App 发布静态快捷方式“翻译剪贴板”；
2. 快捷方式使用独立 action 或 deep link 打开紧凑翻译 Activity；
3. 在 Moto AI 键的“启动应用”列表中选择该快捷方式；
4. App 根据该专用 Intent 可靠进入快捷键翻译流程，而不是尝试从普通 launcher 启动中猜测来源。

这比依赖 `getReferrer()` 或启动时间启发式可靠，也无需成为默认 Assistant。现有截图已证明该 ROM 会显示至少一部分第三方 App 快捷方式，但最终仍须用诊断 APK 验证本 App 发布的快捷方式是否出现，以及单击/双击触发时 Intent 是否保持原样。
