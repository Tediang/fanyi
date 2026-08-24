# 07 — 接入 OpenAI Responses 协议

**What to build:** 用户可以把供应商配置设为 OpenAI Responses，并通过与 Chat Completions 相同的输入入口和紧凑界面完成同步或流式翻译。

**Blocked by:** 03 — 完成多供应商配置与连接测试.

**Status:** resolved

- [x] 供应商配置可以选择 OpenAI Responses 协议并默认使用 `/v1/responses`，同时尊重可选路径覆盖。
- [x] 请求使用协议正确的输入和核心翻译规则表达，不伪装成 Chat Completions。
- [x] 同步响应可以提取最终译文。
- [x] 代表性的 Responses 流事件可以连续生成可见译文并正确结束。
- [x] 取消、网络失败、协议错误和供应商错误被规范化为应用可处理的结果。
- [x] 连接测试能够验证 Responses 端点、鉴权和模型，而不创建历史。
- [x] 协议契约测试覆盖请求、同步提取、流事件、畸形事件、错误和取消。
- [x] 从手动输入经过真实配置、假 Responses 服务到紧凑界面的应用级测试通过。

## Answer

Responses 使用原生 `instructions`、`input`、`store: false` 和 `/v1/responses`，支持同步输出与 `response.output_text.delta`/完成事件。连接测试和正式翻译共用同一适配器，协议契约及真实 App 流程均由本地假服务验证。
