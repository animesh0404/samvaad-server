package com.samvaad.samvaad_server.e2ee.device;

import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceListDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.UploadOneTimePrekeysDto;
import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * E2EE device and public prekey directory. Caller identity always comes from
 * the authenticated server principal; no caller-supplied identity is
 * trusted. There is intentionally no endpoint that binds a session to an
 * already-existing device: sessions become bound only at that device's own
 * enrollment.
 */
@RestController
@RequestMapping("/api/e2ee")
public class E2eeDeviceController {

    private final E2eeDeviceService deviceService;

    public E2eeDeviceController(E2eeDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollDeviceResponseDto enrollDevice(@Valid @RequestBody EnrollDeviceRequestDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.enrollDevice(caller.userId(), caller.sessionId(), request);
    }

    @GetMapping("/devices")
    public DeviceListDto listDevices() {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.listDevices(caller.userId());
    }

    @PostMapping("/devices/{deviceId}/approve")
    public DeviceDto approveDevice(@PathVariable UUID deviceId) {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.approveDevice(caller.userId(), caller.sessionId(), deviceId);
    }

    @PutMapping("/devices/{deviceId}/one-time-prekeys")
    public DeviceDto uploadOneTimePrekeys(
            @PathVariable UUID deviceId,
            @Valid @RequestBody UploadOneTimePrekeysDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.uploadOneTimePrekeys(caller.userId(), caller.sessionId(), deviceId, request);
    }

    @PostMapping("/devices/{deviceId}/one-time-prekeys/claim")
    public ClaimPrekeyResponseDto claimOneTimePrekey(
            @PathVariable UUID deviceId,
            @RequestBody(required = false) ClaimPrekeyRequestDto request) {
        AuthenticatedUser caller = CurrentUser.require();
        UUID requestId = request != null ? request.getRequestId() : null;
        return deviceService.claimOneTimePrekey(caller.userId(), deviceId, requestId);
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID deviceId) {
        AuthenticatedUser caller = CurrentUser.require();
        deviceService.revokeDevice(caller.userId(), deviceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{username}/devices")
    public List<RecipientDeviceDto> getRecipientDevices(@PathVariable String username) {
        AuthenticatedUser caller = CurrentUser.require();
        return deviceService.getRecipientDevices(caller.userId(), username);
    }
}
