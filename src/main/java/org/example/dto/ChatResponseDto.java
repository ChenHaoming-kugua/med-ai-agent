package org.example.dto;

public class ChatResponseDto {
    private boolean isMedicineRelated;
    private String reply;
    private String pharmacyLink;

    public ChatResponseDto(boolean isMedicineRelated, String reply, String pharmacyLink) {
        this.isMedicineRelated = isMedicineRelated;
        this.reply = reply;
        this.pharmacyLink = pharmacyLink;
    }

    public boolean isMedicineRelated() { return isMedicineRelated; }
    public String getReply() { return reply; }
    public String getPharmacyLink() { return pharmacyLink; }
}
