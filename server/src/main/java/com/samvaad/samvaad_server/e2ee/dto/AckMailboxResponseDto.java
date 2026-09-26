package com.samvaad.samvaad_server.e2ee.dto;

public class AckMailboxResponseDto {

    private int acknowledged;

    public AckMailboxResponseDto() {
    }

    public AckMailboxResponseDto(int acknowledged) {
        this.acknowledged = acknowledged;
    }

    public int getAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(int acknowledged) {
        this.acknowledged = acknowledged;
    }
}
