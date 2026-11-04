package com.example.ticket.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 接入配置，对应 application.yml 里的 app.llm。
 *
 * api-key 只从环境变量 DEEPSEEK_API_KEY 读取；
 * application.yml 里默认值为空（${DEEPSEEK_API_KEY:}），仓库不含任何真实 key。
 * 未配置时调用会明确报错「未配置 app.llm.api-key」，不影响其它功能。
 */
@Data
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /** 形如 https://api.deepseek.com，不含 /chat/completions */
    private String baseUrl = "https://api.deepseek.com";

    private String apiKey = "";

    private String model = "deepseek-chat";

    private int timeoutSeconds = 60;

    /** 是否开启 response_format=json_object */
    private boolean jsonMode = true;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
