package org.example.service;

import org.example.config.WeChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

@Service
public class WeChatService {

    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String SUN_CODE_URL = "https://api.weixin.qq.com/wxa/getwxacodeunlimit";

    private final WeChatProperties wechatProps;

    public WeChatService(WeChatProperties wechatProps) {
        this.wechatProps = wechatProps;
    }

    public boolean isConfigured() {
        return wechatProps.getAppId() != null && !wechatProps.getAppId().isBlank()
                && wechatProps.getSecret() != null && !wechatProps.getSecret().isBlank();
    }

    /**
     * Generate sun code (小程序太阳码) as base64 PNG.
     * Returns data URI string ready for <img src="...">.
     */
    public String generateSunCodeBase64(String scene, String page, int width) {
        if (!isConfigured()) return null;

        try {
            String accessToken = getAccessToken();
            byte[] image = getUnlimitedSunCode(accessToken, scene, page, width);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(image);
        } catch (Exception e) {
            log.error("Failed to generate sun code", e);
            return null;
        }
    }

    private String getAccessToken() throws Exception {
        String url = TOKEN_URL + "?grant_type=client_credential&appid="
                + wechatProps.getAppId() + "&secret=" + wechatProps.getSecret();

        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // 简单提取 access_token（正式项目建议用 Jackson 解析）
            String body = response.body();
            int start = body.indexOf("\"access_token\":\"") + 16;
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        }
    }

    private byte[] getUnlimitedSunCode(String accessToken, String scene, String page, int width) throws Exception {
        String url = SUN_CODE_URL + "?access_token=" + accessToken;
        String json = String.format("{\"scene\":\"%s\",\"page\":\"%s\",\"width\":%d}", scene, page, width);

        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.body();
        }
    }
}
