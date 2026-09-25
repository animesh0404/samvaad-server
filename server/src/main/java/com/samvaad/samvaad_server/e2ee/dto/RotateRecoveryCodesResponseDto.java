package com.samvaad.samvaad_server.e2ee.dto;

import java.util.List;
import java.util.UUID;

/**
 * Explicit rotation outcome: the fresh plaintext set, returned exactly once,
 * and the set identifier the server now treats as current. Rotation is never
 * silent: the prior set is superseded in the same operation.
 */
public class RotateRecoveryCodesResponseDto {

    private UUID setId;
    private List<String> recoveryCodes;

    public RotateRecoveryCodesResponseDto() {
    }

    public UUID getSetId() {
        return setId;
    }

    public void setSetId(UUID setId) {
        this.setId = setId;
    }

    public List<String> getRecoveryCodes() {
        return recoveryCodes;
    }

    public void setRecoveryCodes(List<String> recoveryCodes) {
        this.recoveryCodes = recoveryCodes;
    }
}
