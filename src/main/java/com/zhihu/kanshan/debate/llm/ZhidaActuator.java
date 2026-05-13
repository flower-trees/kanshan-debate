package com.zhihu.kanshan.debate.llm;

import org.salt.jlangchain.ai.chat.openai.OpenAIActuator;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Zhida API actuator — OpenAI-compatible endpoint, extra X-Request-Timestamp header.
 */
@Service
public class ZhidaActuator extends OpenAIActuator {

    private static final String CHAT_URL = "https://developer.zhihu.com/v1/chat/completions";

    @Value("${zhihu.secret:}")
    private String chatKey;

    public ZhidaActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected Map<String, String> buildHeaders() {
        return Map.of(
            "Content-Type",        "application/json",
            "Authorization",       "Bearer " + chatKey,
            "X-Request-Timestamp", String.valueOf(System.currentTimeMillis() / 1000)
        );
    }

    @Override
    protected String getChatUrl() { return CHAT_URL; }

    @Override
    protected String getEmbeddingUrl() { return CHAT_URL; }

    @Override
    protected String getChatKey() { return chatKey; }
}
