# 使用固定的 AI 协议适配器

首版只实现 OpenAI Chat Completions、OpenAI Responses 和 Anthropic Messages 三种明确的协议适配器，DeepSeek、vLLM、llama.cpp 等服务通过其兼容协议接入，不提供任意 JSON 请求模板或 JSONPath 响应解析。这样牺牲少量非常规接口兼容性，换取可验证的流式解析、错误处理和参数映射，并防止供应商配置演变成一个难以维护的通用 API 客户端。
