package com.example.ticket.llm;

import java.util.List;

/**
 * 大模型客户端。这是设计里的第一个可替换点。
 *
 * 边界（design-modules.md §七）：
 *   做：一次 HTTP 往返，把消息发出去、把文本收回来
 *   不做：不认识 schema、不解析业务 JSON、不做重试、不做校验
 *
 * 换模型（Qwen / GLM / 本地模型）只需要换一个实现类。
 * 将来要加流式输出，也在这个接口上加方法——
 * 调用方不需要知道底层是流式还是整包。
 */
public interface LlmClient {

    LlmResponse chat(List<Message> messages, LlmOptions options);

    default LlmResponse chat(List<Message> messages) {
        return chat(messages, LlmOptions.defaults());
    }
}
