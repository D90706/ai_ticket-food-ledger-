package com.example.ticket.llm;

/**
 * 一次调用的结果。
 *
 * 除了 content，还带上 model 与 token 用量：
 *   model —— 用于核对「请求的模型」和「实际返回的模型」是否一致
 *            （实测请求 deepseek-chat 会返回 deepseek-flash）
 *   usage —— 便于估算成本，也便于发现 prompt 膨胀
 */
public record LlmResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String finishReason) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
