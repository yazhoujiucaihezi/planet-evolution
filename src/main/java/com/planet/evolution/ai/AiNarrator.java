package com.planet.evolution.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Component
public class AiNarrator {

    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String API_KEY = System.getenv("SILICONFLOW_API_KEY");
    private static final String MODEL = "Qwen/Qwen2.5-7B-Instruct";

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
                "max_tokens", 200
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