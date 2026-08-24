# 08 — 接入 Anthropic Messages 协议

**What to build:** 用户可以把供应商配置设为 Anthropic Messages，并通过与其他协议相同的输入入口和紧凑界面完成同步或流式翻译。

**Blocked by:** 03 — 完成多供应商配置与连接测试.

**Status:** resolved

- [x] 供应商配置可以选择 Anthropic Messages 协议并默认使用 `/v1/messages`，同时尊重可选路径覆盖。
- [x] 请求使用 Anthropic 的消息、系统指令、鉴权和版本语义，不假设 OpenAI 格式。
- [x] 同步响应可以提取最终译文。
- [x] 代表性的 Messages 流事件可以连续生成可见译文并正确结束。
- [x] 取消、网络失败、协议错误和供应商错误被规范化为应用可处理的结果。
- [x] 连接测试能够验证 Messages 端点、鉴权和模型，而不创建历史。
- [x] 协议契约测试覆盖请求、同步提取、流事件、畸形事件、错误和取消。
- [x] 从手动输入经过真实配置、假 Messages 服务到紧凑界面的应用级测试通过。

## Answer

Messages 使用原生 `system`、`messages`、`max_tokens`、`x-api-key` 与 `anthropic-version` 语义，支持同步 content block 和流式 content delta。连接测试与正式翻译共享适配器，并由协议契约和真实 App 流程测试验证。
