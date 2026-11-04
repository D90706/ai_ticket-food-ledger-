package com.example.ledger.llm;

import com.example.ledger.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型调用（OpenAI 兼容协议）。
 *
 * 两个刻意的选择：
 *
 * 1 不用 Spring AI。
 *    我们只需要「发一段 prompt，拿一段 JSON」。为一个 HTTP 调用引一整套抽象，
 *    换来的只是更多的配置与更少的可控性。
 *
 * 2 强制 response_format = json_object。
 *    不强制的话，模型十次里有两次会加"好的，以下是抽取结果："这种前缀，
 *    解析就崩了。省下的不是 token，是整条链路的稳定性。
 *
 * 模型只回答「看到了什么」，不回答「够不够」——
 * 判定由代码做，见 ComplianceEvaluator。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.llm.model:deepseek-chat}")
    private String model;

    @Value("${app.llm.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${app.llm.api-key:}")
    private String apiKey;

    public String complete(String systemPrompt, String userContent) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(500, "未配置 app.llm.api-key，AI 录入不可用；手填录入不受影响");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0);
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));

        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                throw new BizException(502, "模型调用失败 HTTP " + response.statusCode()
                        + "：" + shorten(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new BizException(502, "模型返回为空");
            }
            log.debug("模型调用完成，耗时 {}ms，模型={}", cost,
                    root.path("model").asText(model));
            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "模型调用异常：" + e.getMessage());
        }
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}