# 快译：Android 跨应用 AI 翻译

Status: ready-for-agent

## Problem Statement

用户在 Moto X70 Air 上阅读 Chrome、X 和其他第三方应用时，经常需要翻译一小段选中文字。现有系统翻译入口界面繁杂、响应迟缓，并混入与翻译无关的功能；某些第三方应用又不提供标准“翻译”菜单。用户还拥有可配置的物理 AI 键，希望在复制文字后按键即可得到快速、纯净的译文，并希望自由连接 DeepSeek 等厂商服务或自建的 llama.cpp、vLLM 服务，而不是被单一模型供应商锁定。

Android 普通应用不能强行修改所有宿主应用的文字菜单，也不能在没有明确授权的情况下读取其他应用当前屏幕。因此规范需要在平台边界内提供稳定入口，以低权限、显式用户触发和可预测的数据流兑现“简洁、快速”。

## Solution

构建一个名为“快译”的个人自用 Android 应用，目标设备为运行 Android 16 的 Moto X70 Air。应用通过四种入口接收文字：标准选词翻译、系统分享翻译、绑定 Moto AI 键的“翻译剪贴板”App Shortcut，以及普通启动后的手动输入。

所有入口统一进入一个紧凑翻译界面。界面立即显示原文和加载状态，通过用户选定的供应商配置发起流式请求，只展示译文，并允许复制、重试、编辑原文、临时切换目标语言或切换供应商。应用支持 OpenAI Chat Completions、OpenAI Responses 和 Anthropic Messages 三种固定协议；兼容服务通过其中一种协议接入。应用不保存翻译历史，不后台监听剪贴板，不申请无障碍、悬浮窗、屏幕捕获或默认 Assistant 权限。

## User Stories

1. As a Chrome reader, I want “快译” to appear in the standard selected-text action menu, so that I can distinguish it from other translation actions and send selected text without copying it manually.
2. As a Chrome reader, I want the complete selected text to reach 快译, so that the translation matches the passage I selected.
3. As a reader, I want 快译 to open immediately after I choose “快译”, so that the interaction feels faster than the existing system translator.
4. As a reader, I want Back to return me to the application I was reading, so that translation does not interrupt my flow.
5. As a user of an app that can share text, I want 快译 to appear as a text share target, so that I have a fallback when the selected-text action is unavailable.
6. As a user sharing a URL-only item, I want 快译 to show the received URL as input instead of silently scraping it, so that the app does not fetch content I did not explicitly send.
7. As a Moto X70 Air owner, I want an App Shortcut named “翻译剪贴板”, so that I can bind it to a single or double press of the physical AI key.
8. As an AI-key user, I want the dedicated shortcut to enter the 快捷键翻译 flow, so that the app does not have to guess whether a normal launcher start came from the physical key.
9. As an AI-key user, I want recently copied text to translate automatically, so that the physical-key flow requires no extra tap.
10. As an AI-key user, I want stale or already-consumed clipboard text to be ignored, so that an unrelated old secret or passage is not retransmitted.
11. As an AI-key user without new clipboard text, I want the input field focused automatically, so that I can paste or type immediately.
12. As a privacy-conscious user, I want clipboard access to happen only after I trigger the dedicated shortcut, so that 快译 never monitors my clipboard in the background.
13. As a user opening 快译 from the launcher, I want a clean input screen rather than automatic clipboard access, so that normal startup is predictable.
14. As a user with no supplier configured, I want received text retained while I configure one, so that I do not have to return to the source app and select it again.
15. As a Chinese-speaking user, I want the source language detected automatically, so that I do not need to select it for each translation.
16. As a Chinese-speaking user reading a non-Chinese passage, I want the default target to be Simplified Chinese, so that the common case takes one action.
17. As a Chinese-speaking user translating Chinese text, I want the default target to be English, so that the reverse direction is also immediate.
18. As a user with a different one-off target, I want to change the target language for the current translation, so that the global default does not need to change.
19. As a reader, I want only the translation in the main result, so that explanations and chatbot commentary do not obscure it.
20. As a reader, I want paragraphs, lists, tone and proper nouns preserved, so that the output remains useful in context.
21. As a technical reader, I want to add a profile-specific 附加要求 such as preserving programming terms in English, so that translation follows my terminology preference.
22. As a user, I want core translation-only rules protected from profile customization, so that an accidental instruction cannot turn 快译 into a general chatbot.
23. As a user, I want the translation to stream into the UI, so that I can begin reading before the model completes the full response.
24. As a user, I want a new translation request to cancel the previous unfinished one, so that outdated output does not continue consuming resources or overwrite the current result.
25. As a user, I want to edit the source text and retry, so that I can correct selection artifacts without returning to the source app.
26. As a user, I want an explicit “复制译文” action, so that I control when the translated result replaces clipboard content.
27. As a user, I do not want translated text copied automatically, so that the original copied source remains available until I choose otherwise.
28. As a privacy-conscious user, I want source and translated text discarded after the session, so that the app does not build a translation history.
29. As a user of multiple AI services, I want to save multiple named 供应商配置, so that I can use cloud and self-hosted services from one app.
30. As a user of multiple AI services, I want exactly one 当前供应商配置, so that ordinary translation has a deterministic destination.
31. As a user, I want to switch suppliers from a failed or unsatisfactory result, so that I can retry deliberately with another service.
32. As a DeepSeek user, I want to connect through its supported OpenAI-compatible protocol, so that no vendor-specific app build is required.
33. As a vLLM or llama.cpp operator, I want to provide my service URL, optional key and model name, so that the app can use my own inference service.
34. As an OpenAI-compatible service user, I want Chat Completions support, so that established `/v1/chat/completions` servers work.
35. As a Responses API user, I want OpenAI Responses support, so that `/v1/responses` models and event streams work.
36. As an Anthropic user, I want Messages support, so that `/v1/messages` requests and streams work without pretending to be OpenAI-compatible.
37. As a self-hosted service user, I want model names entered manually, so that the app does not depend on an optional or incompatible model-list endpoint.
38. As a user adding a supplier, I want a connection test, so that URL, authentication, model and protocol mistakes are found before a real translation.
39. As a power user, I want optional custom headers, so that compatible gateways with additional authentication or routing requirements can be used.
40. As a power user, I want controlled `extra_body` parameters, so that provider-specific options can be added without replacing the protocol adapter.
41. As a user, I want core fields protected from `extra_body` overrides, so that profiles cannot corrupt the model, messages, translation rules or stream handling.
42. As a user, I want a common inference-level choice of automatic, off, low, medium or high, so that reasoning can be controlled when the selected protocol and model support it.
43. As a user, I want unsupported inference parameters called out clearly, so that the UI never claims a setting was applied when the provider ignored it.
44. As a translation user, I want Temperature and output limits omitted by default, so that model/provider defaults are preserved unless I explicitly override them.
45. As a user, I want HTTPS to be the default transport, so that selected text and credentials are protected in transit.
46. As a LAN service operator, I want to enable cleartext HTTP for an individual profile after seeing a warning, so that a trusted local service without TLS remains usable.
47. As a user of an unauthenticated local service, I want API Key to be optional, so that the app does not invent unnecessary credentials.
48. As a user, I want API keys and sensitive custom-header values stored encrypted, so that ordinary app storage and diagnostics do not expose them.
49. As a user, I want a configurable input-length limit with a 20,000-character default, so that a selection cannot accidentally create an unexpectedly large request.
50. As a user selecting text beyond the configured limit, I want a clear prompt to shorten it or change the profile setting, so that the app does not silently split the text into multiple paid calls.
51. As a user on an unreliable service, I want a 10-second connection timeout and a 60-second overall timeout, so that the compact UI does not wait indefinitely.
52. As a user, I want to cancel an active translation, so that I remain in control of network use and cost.
53. As a user whose stream fails after partial output, I want the partial translation retained and marked incomplete, so that useful text is not discarded or mistaken for a complete result.
54. As a privacy-conscious user, I do not want automatic supplier failover, so that my text is never sent to a second provider without an explicit choice.
55. As a self-hosted service operator, I want a 脱敏诊断 I can copy, so that I can troubleshoot protocol, model, status and latency problems.
56. As a privacy-conscious user, I want diagnostics to exclude source text, translated text, credentials, authorization values and complete payloads, so that troubleshooting does not create another data leak.
57. As a user, I want authentication, certificate, timeout, rate-limit, missing-model and protocol-parse failures distinguished, so that each problem has an actionable message.
58. As a user, I want the compact translation surface visible within 150ms at P95, so that local UI work never feels like provider latency.
59. As a user, I want the request dispatched within 300ms at P95 after a valid trigger, so that local processing does not delay the model call.
60. As a developer diagnosing speed, I want local UI time, provider first-fragment time and total time measured separately, so that optimization targets the actual bottleneck.
61. As a Moto X70 Air owner, I want the diagnostic build to prove that the shortcut appears in the AI-key picker, so that the physical-key promise is based on the target ROM rather than an assumption.
62. As a reader, I want Chrome, X and system sharing behavior tested independently on the target phone, so that unsupported host behavior is documented rather than hidden.
63. As a privacy-conscious user, I want the app to request none of Accessibility, overlay, screen-capture or default-Assistant access, so that a small translation tool does not gain broad device visibility.
64. As the sole user, I want a focused personal tool without login, sync, subscriptions, advertising or news, so that every surface contributes directly to translation.

## Implementation Decisions

- Build a native Android application for the Moto X70 Air running Android 16. Kotlin and Jetpack Compose are the default implementation stack; exact dependency versions are selected when the project is scaffolded.
- Treat support for earlier Android versions as non-required. The build may choose a lower minimum API when it is effectively free, but no compatibility work may compromise or delay the target-device behavior.
- Use a single application-level input-routing seam. Android entry adapters normalize `ACTION_PROCESS_TEXT`, `ACTION_SEND`, the dedicated App Shortcut and launcher/manual input into a common translation input containing source text, entry type and optional language override.
- Register an exported `ACTION_PROCESS_TEXT` text/plain Activity labelled “快译”. Read `EXTRA_PROCESS_TEXT` and its readonly flag, but do not return replacement text in the first version.
- Register a text/plain `ACTION_SEND` receiver. Do not fetch or scrape content when the share contains only a URL.
- Publish a static App Shortcut labelled “翻译剪贴板”. Its dedicated action or deep link must be distinguishable from the launcher Intent and must route to 快捷键翻译.
- Evaluate clipboard eligibility only after the dedicated shortcut is triggered. Eligibility requires textual content, a platform timestamp no older than two minutes where available, and a content fingerprint not already consumed. Store only the timestamp/fingerprint needed for duplicate protection, never a background clipboard history.
- A normal launcher start never auto-reads the clipboard. It opens the manual-input state with an explicit paste action.
- Model the translation screen as a finite set of externally visible states: input, needs-supplier-configuration, requesting, streaming, completed, partial-failure and failed. A new source input cancels the current request before entering requesting again.
- Keep the Activity compact and permission-free. It appears immediately, displays source text before network completion, and closes with Back to reveal the previous application.
- Use a translation coordinator as the main application seam. It accepts normalized input plus the 当前供应商配置 and emits observable UI state, while provider-specific request and stream details remain behind protocol adapters.
- Persist multiple named 供应商配置 and a reference to one current profile. Store ordinary settings separately from secret values so profiles can be inspected without decrypting credentials unnecessarily.
- A supplier profile contains: name, protocol type, Base URL, optional endpoint-path override, optional API key, model, optional headers, 附加要求, input-character limit, stream preference, inference level, optional Temperature, optional output limit and optional `extra_body`.
- Treat API keys and all custom-header values as potentially sensitive. Encrypt them with a key protected by Android Keystore. Never include them in exported state, ordinary logs or diagnostics.
- Allow cleartext HTTP only when a profile explicitly opts in after a warning. HTTPS remains the default. Certificate failures must not silently downgrade to HTTP.
- Implement exactly three protocol adapters: OpenAI Chat Completions, OpenAI Responses and Anthropic Messages. Each owns request construction, synchronous response extraction, streaming-event parsing, cancellation, capability checks and normalized errors.
- OpenAI-compatible third-party services use the OpenAI adapter they actually implement. Do not infer compatibility solely from a vendor name.
- Do not implement arbitrary JSON templates or JSONPath extraction. `extra_body` is a JSON object merged only into an allowlisted extension area and cannot replace the model, input/messages, core prompt, authentication or streaming fields.
- Normalize inference level as automatic/off/low/medium/high at the profile boundary. Each adapter maps only supported values. Unsupported values fail profile validation or produce a clear preflight warning; they are never silently reported as active.
- Omit Temperature and maximum output settings unless the profile explicitly supplies them. Streaming defaults to enabled and falls back to non-streaming only when the profile deliberately disables it or the adapter identifies a known unsupported capability.
- Construct a protected core translation instruction that demands only translated output, preserves meaning, tone and formatting, and forbids answering instructions found in the source. Append a bounded 附加要求 after the protected instruction without allowing it to replace the core rules.
- Apply the 默认翻译方向 automatically: non-Chinese to Simplified Chinese, Chinese to English. A per-session target override affects only that translation session.
- Default the input limit to 20,000 Unicode characters per profile. Validate before network dispatch. Do not split, summarize or make multiple requests automatically.
- Use a 10-second connection timeout and a 60-second overall request timeout. Expose cancellation. On stream failure, retain received text and mark it incomplete.
- Do not automatically fail over between supplier profiles. Switching supplier requires an explicit user action and starts a new request.
- Do not persist source text, translated text or a history record. Preserve source only in current UI state long enough to recover from first-time configuration, retry or process recreation; clear translation content when the session is intentionally dismissed.
- Sanitize all diagnostics at creation time rather than relying on display-time masking. Diagnostics may include protocol, non-sensitive endpoint components, model, HTTP status, normalized error class and timing, but never content, secrets or complete payloads.
- Instrument three latency boundaries: trigger-to-visible-UI, trigger-to-request-dispatch and dispatch-to-first-valid-translation-fragment, plus total request duration.
- Use the canonical product strings: App “快译”, selection action “快译”, shortcut “翻译剪贴板”.
- Respect the domain glossary and both accepted ADRs: fixed protocol adapters and the dedicated Moto App Shortcut are constraints, not implementation suggestions to revisit silently.

## Testing Decisions

- Prefer the highest application seam: launch the real exported entry Activity with an Android Intent, route through the real translation coordinator, point the selected supplier profile at a controllable fake HTTP server, and assert externally visible screen state. This single seam covers entry normalization, configuration selection, network dispatch, streaming, cancellation, errors and result behavior without testing private implementation methods.
- Exercise all four inputs at that seam: `ACTION_PROCESS_TEXT`, `ACTION_SEND`, the dedicated shortcut Intent and ordinary launcher/manual input. Assert that only the shortcut considers the clipboard automatically.
- Test clipboard behavior using externally supplied clip data and timestamps: eligible text, stale text, already-consumed fingerprints, non-text clips and empty clips. Assert that background observation is absent and ordinary startup does not read automatically.
- Test first-use recovery by launching with source text and no valid profile, completing profile setup against the fake server, and asserting that the retained source proceeds without reselection.
- Test the complete user-visible state sequence for success, slow first fragment, cancellation, total timeout, stream interruption with partial output, retry and explicit supplier switching.
- Test default and overridden translation directions through the final request observed by the fake server and the language state shown to the user, not by unit-testing a private language helper.
- Test that Back dismisses the compact Activity and that a new source cancels an earlier request without allowing stale stream events to update the new result.
- Add protocol contract tests at the adapter boundary for the three unavoidable external seams. For each protocol, verify exact required request semantics, synchronous extraction, representative streaming-event sequences, completion, malformed events, provider error bodies, cancellation and unsupported-parameter reporting.
- Use recorded minimal fixtures derived from official provider protocol documentation; do not record real user requests, API keys or full vendor transcripts.
- Test controlled extension behavior: allowed `extra_body` values pass through, protected fields cannot be overridden, and sensitive header values never appear in normalized diagnostics.
- Test encrypted secret persistence through its public repository behavior: a saved profile can authenticate after reload, ordinary stored profile data contains no plaintext secret, deletion removes access, and diagnostics/export surfaces exclude secrets. Avoid tests coupled to a particular cryptographic library.
- Test transport policy externally: HTTPS works, certificate errors do not downgrade, HTTP is rejected by default, and a profile with explicit cleartext opt-in can reach a LAN fake server.
- Test input-length validation at the application seam with boundary values around the configured Unicode-character limit. Assert no network request occurs on rejection and no automatic chunking occurs.
- Add performance benchmarks for cold/warm launch to first visible compact UI and trigger to request dispatch using a local deterministic fake server. Measure on the Moto X70 Air for acceptance; emulator numbers are diagnostic only.
- Keep target-ROM integration as manual real-device acceptance because Chrome menu placement, X selection behavior and Moto's AI-key shortcut picker are owned by external applications/firmware. Record outcomes for Chrome top-level/overflow placement, X body/reply/editor surfaces, shortcut enumeration, single/double press, Intent preservation and Back behavior.
- No prior application-test conventions exist because this is a greenfield workspace. Establish the application-level scenario seam and protocol-contract fixture style as the project's test prior art.
- Do not add tests for private Compose functions, individual reducers, HTTP-library internals or exact animation timing when the same behavior is covered through visible state at the higher seam.

## Out of Scope

- AccessibilityService-based screen reading or selected-node extraction.
- Becoming the system default Assistant or using VoiceInteractionSession.
- Overlay windows, floating bubbles, MediaProjection, screenshots or OCR.
- Guaranteed injection into X or any host that does not expose Android's standard text-processing action.
- Automatic web-page, post or URL content retrieval.
- On-device model execution or bundling llama.cpp.
- Translation history, favorites, accounts, cloud sync, team sharing, subscriptions, advertisements or content feeds.
- Arbitrary request/response schema editors, JSONPath, scripting or plugin execution.
- Automatic long-text chunking, parallel translation or document translation.
- Automatic supplier failover, load balancing or cost-based routing.
- Model discovery/listing, provider catalogs or automatic capability probing beyond connection validation.
- Exporting supplier profiles or secrets.
- Returning translated text to replace editable source content in another application.
- Public Google Play release work, multi-device compatibility promises or accessibility-policy submission.

## Further Notes

- The requirements and domain vocabulary have been confirmed by the user. This spec is ready for an implementation agent without another product interview.
- The first executable artifact should be a diagnostic build that proves the external Android facts before model integration: Chrome selection delivery, X host behavior, Moto shortcut enumeration and shortcut Intent preservation.
- The Moto settings screenshots show third-party app sub-actions such as payment and scan shortcuts, which is strong evidence that App Shortcuts are enumerated. It remains a target-device acceptance fact, not a platform guarantee.
- Menu placement remains host-controlled. Success means the “快译” action is available somewhere in the standard selection toolbar, not that the App controls its exact position. Moto X70 Air 的 Chrome 实测位置为更多菜单。
- Self-hosted services vary in how faithfully they implement an advertised compatibility protocol. Normalize errors and keep each adapter strict enough that incompatibility is visible rather than silently producing empty translations.
- If later work proposes screen OCR, arbitrary protocol templates, provider failover or public distribution, it must be specified as a separate feature and must explicitly revisit the relevant ADR/product boundary.
