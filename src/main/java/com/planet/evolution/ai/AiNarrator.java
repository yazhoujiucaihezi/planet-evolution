package com.planet.evolution.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Component
public class AiNarrator {

    /**
     * AI 供应方：由环境变量 {@code AI_PROVIDER} 决定，默认 {@code siliconflow}（云端硅基流动）。
     * 设为 {@code ollama} 时切到本地 Ollama（OpenAI 兼容接口），无需 API Key。
     *
     * <p>两种供应方都走 OpenAI 格式的 {@code /v1/chat/completions}，请求体完全一致，
     * 因此 {@link #narrate(String)} 的逻辑无需区分。
     */
    private static final String PROVIDER = System.getenv().getOrDefault("AI_PROVIDER", "siliconflow");
    private static final String API_URL = "ollama".equals(PROVIDER)
            ? "http://localhost:11434/v1/chat/completions"
            : "https://api.siliconflow.cn/v1/chat/completions";
    private static final String API_KEY = "ollama".equals(PROVIDER)
            ? "ollama"
            : System.getenv("SILICONFLOW_API_KEY");
    private static final String MODEL = "ollama".equals(PROVIDER)
            ? "qwen2.5:3b"
            : "Qwen/Qwen2.5-7B-Instruct";

    private final RestClient client = RestClient.create();

    public String narrate(String eventSummary) {
        if (API_KEY == null || API_KEY.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", eventSummary
                )),
                "max_tokens", 1500
            );

            Map<?, ?> resp = client.post()
                .uri(API_URL)
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body)
                .retrieve()
                .body(Map.class);

            List<?> choices = (List<?>) resp.get("choices");
            Map<?, ?> first = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            return null;
        }
    }
}