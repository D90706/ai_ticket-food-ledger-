package com.example.ticket.llm;

import com.example.ticket.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 实现。OpenAI 兼容协议，所以裸调 HTTP 就够。
 *
 * 刻意不引入 Spring AI / LangChain4j：
 * 它们封装掉的部分（校验、重试、缺失判定、动态 prompt）
 * 恰好是本项目要自己定的部分，绕开框架的成本比学习它更低。
 *
 * 用 java.net.http.HttpClient 而不是 RestTemplate/WebClient：
 * 无第三方依赖、无响应式编程模型、超时配置直接。
 */
@Slf4j
@Component
public class DeepSeekClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DeepSeekClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public LlmResponse chat(List<Message> messages, LlmOptions options) {
        if (!props.hasApiKey()) {
            throw new BizException(500,
                    "未配置 app.llm.api-key（请设置环境变量 DEEPSEEK_API_KEY）");
        }

        String body = buildBody(messages, options);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "调用大模型被中断");
        } catch (Exception e) {
            throw new BizException(502, "调用大模型失败: " + e.getMessage());
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() != 200) {
            throw new BizException(502, "大模型返回 " + response.statusCode() + ": "
                    + truncate(response.body()));
        }

        return parse(response.body(), latencyMs);
    }

    private String endpoint() {
        String base = props.getBaseUrl().endsWith("/")
                ? props.getBaseUrl().substring(0, props.getBaseUrl().length() - 1)
                : props.getBaseUrl();
        return base + "/chat/completions";
    }

    private String buildBody(List<Message> messages, LlmOptions options) {
        List<Map<String, String>> msgNodes = new ArrayList<>();
        for (Message m : messages) {
            msgNodes.add(Map.of("role", m.role(), "content", m.content()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model() != null ? options.model() : props.getModel());
        body.put("messages", msgNodes);
        body.put("temperature", options.temperature());
        body.put("max_tokens", options.maxTokens());
        if (options.jsonMode() && props.isJsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(500, "请求体序列化失败: " + e.getMessage());
        }
    }

    private LlmResponse parse(String raw, long latencyMs) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            JsonNode usage = root.path("usage");

            LlmResponse resp = new LlmResponse(
                    content,
                    root.path("model").asText(""),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    latencyMs,
                    choice.path("finish_reason").asText(""));

            log.debug("LLM 返回 model={} prompt={} completion={} 耗时={}ms",
                    resp.model(), resp.promptTokens(), resp.completionTokens(), latencyMs);
            return resp;
        } catch (Exception e) {
            throw new BizException(502, "大模型响应解析失败: " + truncate(raw));
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
