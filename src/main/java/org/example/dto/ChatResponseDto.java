package org.example.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ChatResponseDto {
    private boolean isMedicineRelated;
    private String reply;
    private String pharmacyLink;
    private String searchKeyword;

    @JsonIgnore
    private String sunCodeUrl;

    public ChatResponseDto(boolean isMedicineRelated, String reply, String pharmacyLink, String searchKeyword, String sunCodeUrl) {
        this.isMedicineRelated = isMedicineRelated;
        this.reply = reply;
        this.pharmacyLink = pharmacyLink;
        this.searchKeyword = searchKeyword;
        this.sunCodeUrl = sunCodeUrl;
    }

    @JsonProperty("isMedicineRelated")
    public boolean isMedicineRelated() { return isMedicineRelated; }

    public String getReply() { return reply; }
    public String getPharmacyLink() { return pharmacyLink; }
    public String getSearchKeyword() { return searchKeyword; }

    @JsonIgnore
    public String getSunCodeUrl() { return sunCodeUrl; }
}
