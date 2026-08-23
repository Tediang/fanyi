# 01 — 搭建诊断 App，验证 Android 外部入口

**What to build:** 一个可安装到 Moto X70 Air 的最小“快译”诊断 App。它从选词翻译、分享翻译、专用 App Shortcut 和普通启动进入时，清楚显示入口类型、收到的文本、Intent action、MIME type、readonly 等非敏感诊断信息，使外部 Android/ROM 能力在接入模型前即可验证。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 工程可以在当前工作区重复构建，并产出可安装到 Android 16 的 APK。
- [ ] App 名称为“快译”，不申请无障碍、悬浮窗、屏幕捕获或默认 Assistant 权限。
- [ ] Chrome 等兼容宿主的选词菜单可发现名称为“翻译”的 `ACTION_PROCESS_TEXT` 入口。
- [ ] 系统文字分享可发现“快译”，诊断页能区分文本分享与 URL-only 分享。
- [ ] App 发布名称为“翻译剪贴板”的静态快捷方式，并以专用入口区别于普通启动。
- [ ] 普通启动、选词、分享和快捷方式均进入同一个可读的诊断表面，不会启动真实模型请求。
- [ ] 应用级测试覆盖四种 Intent 的入口识别和文本规范化，不测试私有 UI 实现细节。
- [ ] 提供目标手机实测清单，可记录 Chrome 菜单位置、X 各文本表面、Moto 快捷方式枚举和单击/双击结果。
