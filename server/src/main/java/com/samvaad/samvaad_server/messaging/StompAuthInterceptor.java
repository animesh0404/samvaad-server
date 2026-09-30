package com.samvaad.samvaad_server.messaging;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.common.logging.TraceIds;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;

/**
 * Authenticates STOMP {@code CONNECT} frames with the existing Samvaad JWT
 * access token plus persisted session validation (same rules as the HTTP
 * {@code JwtAuthenticationFilter}), extended with the session's E2EE device
 * binding: the resulting principal identifies exactly one authenticated
 * device.
 *
 * <p>The device identity is derived server-side from
 * {@code Session.deviceId} and is never taken from client-supplied data.
 * Each connection may subscribe only to its own device channel,
 * {@code /topic/devices/{deviceId}}; conversation IDs never determine
 * connection or subscription identity.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEVICE_TOPIC_PREFIX = "/topic/devices/";

    private final TokenService tokenService;
    private final SessionRepo sessionRepo;
    private final E2eeDeviceRepo deviceRepo;

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    public StompAuthInterceptor(
            TokenService tokenService,
            SessionRepo sessionRepo,
            E2eeDeviceRepo deviceRepo) {
        this.tokenService = tokenService;
        this.sessionRepo = sessionRepo;
        this.deviceRepo = deviceRepo;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        // Scoped to this inbound message; cleared in afterSendCompletion below.
        // MDC is not expected to propagate into asynchronous broker delivery.
        MDC.put(TraceIds.MDC_KEY, TraceIds.resolveOrGenerate(
                firstNativeHeader(accessor, TraceIds.REQUEST_ID_HEADER),
                firstNativeHeader(accessor, TraceIds.TRACE_ID_HEADER)));
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            try {
                accessor.setUser(authenticate(accessor));
            } catch (InvalidAccessTokenException e) {
                List<String> header = accessor.getNativeHeader(AUTHORIZATION_HEADER);
                log.warn("STOMP CONNECT authentication failed authHeaderPresent={}",
                        header != null && !header.isEmpty());
                throw e;
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            try {
                authorizeSubscription(accessor);
            } catch (ForbiddenOperationException e) {
                log.warn("STOMP SUBSCRIBE authorization denied destination={}",
                        accessor.getDestination());
                throw e;
            }
        }
        return message;
    }

    @Override
    public void afterSendCompletion(
            Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        MDC.remove(TraceIds.MDC_KEY);
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private UsernamePasswordAuthenticationToken authenticate(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor);
        AccessTokenClaims claims;
        try {
            claims = tokenService.parseAccessToken(token);
        } catch (InvalidAccessTokenException e) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        Session session = sessionRepo.findWithUserBySessionId(claims.sessionId())
                .orElseThrow(() -> new InvalidAccessTokenException("Invalid access token"));
        if (!isSessionValid(session, claims)) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        StompDevicePrincipal caller = resolveDevicePrincipal(session);
        return new UsernamePasswordAuthenticationToken(
                caller,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + caller.role().name())));
    }

    /**
     * Derives the authoritative device identity from the authenticated
     * session. Every failure mode reports the same generic error so a
     * caller cannot distinguish unbound sessions from missing, foreign,
     * or inactive devices.
     */
    private StompDevicePrincipal resolveDevicePrincipal(Session session) {
        if (session.getDeviceId() == null) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        E2eeDevice device = deviceRepo.findById(session.getDeviceId())
                .orElseThrow(() -> new InvalidAccessTokenException("Invalid access token"));
        if (!session.getUser().getUserId().equals(device.getUser().getUserId())
                || !device.isActive()) {
            throw new InvalidAccessTokenException("Invalid access token");
        }
        return new StompDevicePrincipal(
                session.getUser().getUserId(),
                session.getUser().getRole(),
                session.getSessionId(),
                device.getDeviceId());
    }

    private boolean isSessionValid(Session session, AccessTokenClaims claims) {
        if (session.getRevokedAt() != null) {
            return false;
        }
        if (!session.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        return session.getUser().getUserId().equals(claims.userId());
    }

    private String bearerToken(StompHeaderAccessor accessor) {
        List<String> values = Optional.ofNullable(accessor.getNativeHeader(AUTHORIZATION_HEADER))
                .orElse(List.of());
        return values.stream()
                .filter(value -> value.startsWith(BEARER_PREFIX))
                .map(value -> value.substring(BEARER_PREFIX.length()).trim())
                .filter(token -> !token.isEmpty())
                .findFirst()
                .orElseThrow(() -> new InvalidAccessTokenException("Invalid access token"));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        StompDevicePrincipal caller = currentDevicePrincipal(accessor);
        // Revalidate liveness at subscribe time: a device revoked after
        // CONNECT cannot open a new subscription on the old connection.
        // Every failure denies identically so a caller cannot tell whether
        // another device exists.
        StompDevicePrincipal fresh = revalidate(caller);
        String expected = DEVICE_TOPIC_PREFIX + fresh.deviceId();
        if (!expected.equals(accessor.getDestination())) {
            throw new ForbiddenOperationException();
        }
    }

    private StompDevicePrincipal currentDevicePrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken authentication
                && authentication.getPrincipal() instanceof StompDevicePrincipal caller) {
            return caller;
        }
        throw new ForbiddenOperationException();
    }

    private StompDevicePrincipal revalidate(StompDevicePrincipal caller) {
        Session session = sessionRepo.findWithUserBySessionId(caller.sessionId()).orElse(null);
        if (session == null
                || session.getRevokedAt() != null
                || !session.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())
                || !session.getUser().getUserId().equals(caller.userId())) {
            throw new ForbiddenOperationException();
        }
        try {
            StompDevicePrincipal fresh = resolveDevicePrincipal(session);
            if (!fresh.deviceId().equals(caller.deviceId())) {
                throw new ForbiddenOperationException();
            }
            return fresh;
        } catch (InvalidAccessTokenException e) {
            throw new ForbiddenOperationException();
        }
    }
}
