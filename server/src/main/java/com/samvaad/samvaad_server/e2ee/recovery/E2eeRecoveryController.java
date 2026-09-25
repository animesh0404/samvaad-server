package com.samvaad.samvaad_server.e2ee.recovery;

import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.RotateRecoveryCodesResponseDto;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account recovery for E2EE enrollment when the account has previously
 * enrolled but currently has zero ACTIVE devices. Recovery codes are
 * account-recovery credentials: plaintext is returned exactly once at
 * generation, only hashes are persisted, and there is no retrieval
 * endpoint.
 */
@RestController
@RequestMapping("/api/e2ee/recovery")
public class E2eeRecoveryController {

    private final E2eeDeviceService deviceService;

    public E2eeRecoveryController(E2eeDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto recoverEnroll(@Valid @RequestBody RecoveryEnrollRequestDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.recoverEnroll(caller.userId(), caller.sessionId(), request);
    }

    @PostMapping("/codes")
    public RotateRecoveryCodesResponseDto rotateRecoveryCodes() {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.rotateRecoveryCodes(caller.userId(), caller.sessionId());
    }
}
