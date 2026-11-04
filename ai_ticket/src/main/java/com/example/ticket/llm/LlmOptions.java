package com.example.ticket.llm;

/**
 * 调用参数。
 *
 * jsonMode 对应 DeepSeek 的 response_format={"type":"json_object"}。
 * 默认开着，理由是「格式稳定优先」——因为解析失败要付出重试成本。
 *
 * 但要注意一个实测现象：开启 json_mode 后，模型更倾向于把字段填满、
 * 而不是诚实留空（同一段对话，自然模式下 reason 为 null，
 * json_mode 下编成了具体值）。所以格式靠它保证，
 * 内容可靠性仍然必须由核验层兜底，不能指望它。
 */
public record LlmOptions(String model, double temperature, int maxTokens, boolean jsonMode) {

    public static LlmOptions defaults() {
        return new LlmOptions(null, 0.0, 900, true);
    }

    public static LlmOptions of(String model) {
        return new LlmOptions(model, 0.0, 900, true);
    }
}
