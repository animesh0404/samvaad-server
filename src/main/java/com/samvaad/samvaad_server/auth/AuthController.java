package com.samvaad.samvaad_server.auth;

import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.auth.dto.RefreshTokenRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            AuthenticationService authenticationService,
            RefreshTokenService refreshTokenService) {
        this.authenticationService = authenticationService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/login")
    public LoginResponseDto login(
            @Valid @RequestBody LoginRequestDto loginRequest,
            HttpServletRequest request) {

        String ipAddress = resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        return authenticationService.login(loginRequest, ipAddress, userAgent);
    }

    @PostMapping("/refresh")
    public LoginResponseDto refresh(@Valid @RequestBody RefreshTokenRequestDto refreshRequest) {
        return refreshTokenService.refresh(refreshRequest.getRefreshToken());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
