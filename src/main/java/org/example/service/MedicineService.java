package org.example.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.dto.ChatResponseDto;
import org.springframework.stereotype.Service;

@Service
public class MedicineService {

    private static final String PHARMACY_LINK = "#小程序://国药健康商城丨国大药房SPS/nqu65AEQVnMBdgj";

    private static final String SYSTEM_PROMPT = """
        你是一个医疗购药助手。分析用户的输入，判断用户是否表达了购药/买药/药品相关的需求。

        如果用户与购药/买药/药品/健康产品相关，返回 JSON：
        {"isMedicineRelated": true, "reply": "你的友好回复（建议用户通过国大药房小程序购药）"}

        如果用户的话题与购药/买药完全无关，返回 JSON：
        {"isMedicineRelated": false, "reply": "说明未检测到购药意图"}

        只返回 JSON，不要返回其他内容。
        """;

    private final ChatLanguageModel chatLanguageModel;

    public MedicineService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    public ChatResponseDto analyze(String userMessage) {
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(userMessage)
                )
                .build();

        try {
            ChatResponse response = chatLanguageModel.chat(chatRequest);
            String content = response.aiMessage().text();
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return parseResponse(content);
        } catch (Exception e) {
            return new ChatResponseDto(false, "服务暂不可用，请稍后再试", null);
        }
    }

    private ChatResponseDto parseResponse(String json) {
        try {
            boolean isMedicineRelated = json.contains("\"isMedicineRelated\": true");
            String reply = extractJsonValue(json, "reply");
            String link = isMedicineRelated ? PHARMACY_LINK : null;
            return new ChatResponseDto(isMedicineRelated, reply, link);
        } catch (Exception e) {
            return new ChatResponseDto(false, json, null);
        }
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\": \"";
        int start = json.indexOf(pattern);
        if (start == -1) return "";
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end);
    }
}
