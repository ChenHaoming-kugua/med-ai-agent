package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.example.dto.ChatResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MedicineService {

    private static final Logger log = LoggerFactory.getLogger(MedicineService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PHARMACY_LINK = "#小程序://国药健康商城丨国大药房SPS/nqu65AEQVnMBdgj";
    private static final String SEARCH_KEYWORD = "国大药房";
    private static final String SUN_CODE_SCENE = "pharmacy";
    private static final String SUN_CODE_PAGE = "pages/index/index";

    private static final String SYSTEM_PROMPT = """
        你是一个医疗购药助手。分析用户的输入，判断用户是否表达了购药/买药/药品相关的需求。

        如果用户与购药/买药/药品/健康产品相关，返回 JSON：
        {"isMedicineRelated": true, "reply": "你的友好回复"}

        如果用户的话题与购药/买药完全无关，返回 JSON：
        {"isMedicineRelated": false, "reply": "说明未检测到购药意图"}

        重要：reply 中的双引号必须转义为 \\"，不要使用中文引号。只返回 JSON，不要返回其他内容。
        """;

    private final ChatLanguageModel chatLanguageModel;
    private final WeChatService weChatService;

    public MedicineService(ChatLanguageModel chatLanguageModel, WeChatService weChatService) {
        this.chatLanguageModel = chatLanguageModel;
        this.weChatService = weChatService;
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
            // Bug 1: 去掉 MiniMax M3 的 <think>...</think> 推理过程（兼容单行/多行）
            content = content.replaceAll("(?s)<think>.*?</think>", "");
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return parseResponse(content);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            return failResponse("服务暂不可用，请稍后通过微信搜索「" + SEARCH_KEYWORD + "」进入国大药房小程序");
        }
    }

    @SuppressWarnings("unchecked")
    private ChatResponseDto parseResponse(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            boolean isMedicineRelated = Boolean.TRUE.equals(map.get("isMedicineRelated"));
            String reply = (String) map.getOrDefault("reply", "");
            String link = isMedicineRelated ? PHARMACY_LINK : null;
            String keyword = isMedicineRelated ? SEARCH_KEYWORD : null;
            String sunCode = isMedicineRelated ? tryGenerateSunCode() : null;
            return new ChatResponseDto(isMedicineRelated, reply, link, keyword, sunCode);
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", json, e);
            return failResponse(json);
        }
    }

    private String tryGenerateSunCode() {
        if (!weChatService.isConfigured()) return null;
        return weChatService.generateSunCodeBase64(SUN_CODE_SCENE, SUN_CODE_PAGE, 280);
    }

    private ChatResponseDto failResponse(String rawContent) {
        // 即使 JSON 解析失败，也尝试用关键词兜底判断
        String lower = rawContent.toLowerCase();
        boolean looksMedicine = lower.contains("药") || lower.contains("医") || lower.contains("健康");
        String reply = "您可以微信搜索「" + SEARCH_KEYWORD + "」进入国大药房小程序购药";
        String link = looksMedicine ? PHARMACY_LINK : null;
        String keyword = looksMedicine ? SEARCH_KEYWORD : null;
        return new ChatResponseDto(looksMedicine, reply, link, keyword, null);
    }
}
