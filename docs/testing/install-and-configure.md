# 安装与配置快译候选版

候选版本：`0.4.0-rc1`（versionCode 4）

当前 debug APK 的 SHA-256：`4D716EBAF0F3AE471171FFF75B27522AC6063B963FD60EFEDC0B40F8B3BDDC56`

## 安装 APK

APK 构建后位于：

`app/build/outputs/apk/debug/app-debug.apk`

手机保持“开发者选项 → USB 调试”开启，连接电脑并在手机上允许这台电脑调试，然后执行：

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`adb devices` 应显示手机序列号和 `device`。`-r` 会覆盖安装并保留已有供应商配置；如果系统提示签名不一致，只能先卸载旧签名版本，而卸载会清除配置。

也可以把 APK 复制到手机后，从文件管理器点开安装；此时需要临时允许该文件管理器“安装未知应用”。安装完成后可关闭该权限。

## 新增供应商配置

普通打开“快译”，选择“新增供应商配置”，填写：

- 配置名称：仅用于自己识别，例如 `DeepSeek`、`家中 vLLM`。
- 协议类型：OpenAI Chat Completions、OpenAI Responses 或 Anthropic Messages。
- Base URL：只填服务根地址，例如 `https://api.deepseek.com`。
- 路径覆盖：通常留空，由协议自动使用 `/v1/chat/completions`、`/v1/responses` 或 `/v1/messages`。
- API Key：云服务按供应商要求填写；本地无需鉴权的服务可留空。
- 模型：填写服务实际提供的模型名。

保存后先点“测试连接”。首次从选词、分享或快捷键带入原文时，只有连接测试成功，快译才会继续发送之前保留的原文。

“高级设置”可配置附加要求、推理等级、Temperature、最大输出量、流式开关、输入长度限制以及受控 `extra_body`。默认值适合大多数兼容服务；不支持的推理等级会在请求前提示，不会假装已经生效。

## 常见配置示例

| 服务 | 协议 | Base URL | 路径 | 备注 |
| --- | --- | --- | --- | --- |
| DeepSeek | OpenAI Chat Completions | `https://api.deepseek.com` | 留空 | 填写 DeepSeek Key 和实际模型名 |
| vLLM / llama.cpp | 其声明兼容的 OpenAI 协议 | 例如 `http://192.168.1.20:8000` | 通常留空 | HTTP 只允许 localhost、`.local` 或私有局域网地址，并须显式确认明文风险 |
| Anthropic | Anthropic Messages | `https://api.anthropic.com` | 留空 | App 自动使用 `x-api-key` 和 Anthropic 版本请求语义 |

快译不会因为 HTTPS 证书失败自动降级到 HTTP，也不会在失败后自动把原文发送给另一个供应商。

## 外部入口

- Chrome 等兼容应用：选中文字，在文字操作的“更多”菜单中点“快译”。菜单位置由宿主决定。
- X 等不提供标准选词入口的应用：可尝试“分享”；如果宿主只分享 URL，快译只翻译该 URL，不抓取帖子正文。
- Moto AI 键：绑定 App 子操作“翻译剪贴板”。复制文字后触发；只有两分钟内的新文本会自动翻译。
- 普通启动：不会自动读取剪贴板，需要输入或点“粘贴”。

Moto 当前 ROM 已观察到从 AI 键启动后按 Back 回到桌面。这个任务栈由 Moto 启动器创建，普通应用无法恢复触发前应用；它不影响专用快捷方式和剪贴板翻译本身。
