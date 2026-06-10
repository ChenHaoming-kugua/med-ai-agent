package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wechat")
public class WeChatProperties {
    private String appId;
    private String secret;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
}
