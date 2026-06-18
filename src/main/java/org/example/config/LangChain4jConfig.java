package org.example.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.model-name}")
    private String modelName;

    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                /** MiniMax M3 推理模型默认输出 `<think>`, 在 OpenAI-compatible API
                 *  中以 strictJsonSchema 强制 JSON 输出, 可抑制模型输出 think。*/
                .strictJsonSchema(true)
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(30))
                .maxTokens(512)
                .temperature(0.3)
                .build();
    }

    @Bean("posGitReviewChatLanguageModel")
    public ChatLanguageModel posGitReviewChatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .maxTokens(2048)
                .temperature(0.2)
                .build();
    }
}
