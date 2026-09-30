package com.samvaad.samvaad_server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.auth.token.AccessTokenClaims;
import com.samvaad.samvaad_server.auth.token.TokenService;
import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRole;

/**
 * Device-level STOMP authentication and subscription authorization.
 *
 * <p>CONNECT cases prove the server derives the device identity from the
 * authenticated session and never trusts client-supplied device data.
 * SUBSCRIBE cases prove each connection can only open its own device
 * channel, without revealing whether other devices exist.
 */
@ExtendWith(MockitoExtension.class)
class StompDeviceAuthTest {

    private static final String SECRET_JWT = "device-test-access-token";

    @Mock
    private TokenService tokenService;

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private E2eeDeviceRepo deviceRepo;

    @Mock
    private StompConnectionRegistry connectionRegistry;

    private StompAuthInterceptor interceptor;

    private UUID userId;
    private UUID sessionId;
    private UUID deviceId;
    private User user;
    private Session session;
    private E2eeDevice device;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthInterceptor(tokenService, sessionRepo, deviceRepo, connectionRegistry);

        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        user = new User(userId);
        user.setRole(UserRole.USER);

        session = new Session();
        session.setSessionId(sessionId);
        session.setUser(user);
        session.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(1));
        session.setDeviceId(deviceId);

        device = new E2eeDevice();
        device.setDeviceId(deviceId);
        device.setUser(user);
        device.setStatus(DeviceStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private void givenValidBinding() {
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.findById(deviceId)).willReturn(Optional.of(device));
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return new GenericMessage<>(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, Object principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }
        return new GenericMessage<>(new byte[0], accessor.getMessageHeaders());
    }

    private StompDevicePrincipal devicePrincipal() {
        return new StompDevicePrincipal(userId, UserRole.USER, sessionId, deviceId);
    }

    // CONNECT authentication

    @Test
    void connectSucceedsForActiveBoundDevice() {
        givenValidBinding();

        Message<?> result = interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null);

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        UsernamePasswordAuthenticationToken authentication =
                (UsernamePasswordAuthenticationToken) accessor.getUser();
        StompDevicePrincipal caller = (StompDevicePrincipal) authentication.getPrincipal();
        assertEquals(userId, caller.userId());
        assertEquals(sessionId, caller.sessionId());
        assertEquals(deviceId, caller.deviceId());
        interceptor.afterSendCompletion(result, null, true, null);
    }

    @Test
    void connectFailsWithoutAuthorizationHeader() {
        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage(null), null));
    }

    @Test
    void connectFailsForInvalidJwt() {
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willThrow(new InvalidAccessTokenException("bad"));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForRevokedSession() {
        session.setRevokedAt(LocalDateTime.now());
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForExpiredSession() {
        session.setRefreshTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForJwtSessionUserMismatch() {
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(UUID.randomUUID(), sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForSessionWithoutDeviceBinding() {
        session.setDeviceId(null);
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForNonexistentDevice() {
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.findById(deviceId)).willReturn(Optional.empty());

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForDeviceOwnedByAnotherUser() {
        User other = new User(UUID.randomUUID());
        other.setRole(UserRole.USER);
        device.setUser(other);
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.findById(deviceId)).willReturn(Optional.of(device));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    @Test
    void connectFailsForInactiveDevice() {
        device.setStatus(DeviceStatus.REVOKED);
        given(tokenService.parseAccessToken(SECRET_JWT))
                .willReturn(new AccessTokenClaims(userId, sessionId));
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.findById(deviceId)).willReturn(Optional.of(device));

        assertThrows(InvalidAccessTokenException.class,
                () -> interceptor.preSend(connectMessage("Bearer " + SECRET_JWT), null));
    }

    // SUBSCRIBE authorization

    private void givenLiveRevalidation() {
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.findById(deviceId)).willReturn(Optional.of(device));
    }

    @Test
    void subscribeSucceedsForOwnDeviceChannel() {
        givenLiveRevalidation();

        interceptor.preSend(
                subscribeMessage("/topic/devices/" + deviceId, devicePrincipal()), null);
    }

    @Test
    void subscribeFailsWithoutAuthentication() {
        assertThrows(ForbiddenOperationException.class, () -> interceptor.preSend(
                subscribeMessage("/topic/devices/" + deviceId, null), null));
    }

    @Test
    void subscribeFailsForAnotherDevicesChannelWithoutOracle() {
        givenLiveRevalidation();
        UUID otherDeviceId = UUID.randomUUID();

        ForbiddenOperationException targeted = assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(
                        subscribeMessage("/topic/devices/" + otherDeviceId, devicePrincipal()),
                        null));
        ForbiddenOperationException unknown = assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(
                        subscribeMessage("/topic/devices/" + UUID.randomUUID(), devicePrincipal()),
                        null));
        ForbiddenOperationException garbage = assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(
                        subscribeMessage("/topic/conversations/" + UUID.randomUUID(),
                                devicePrincipal()),
                        null));

        // Identical denial regardless of whether the target device exists.
        assertEquals(unknown.getClass(), targeted.getClass());
        assertEquals(garbage.getClass(), targeted.getClass());
    }

    @Test
    void subscribeFailsWhenDeviceRevokedAfterConnect() {
        device.setStatus(DeviceStatus.REVOKED);
        givenLiveRevalidation();

        assertThrows(ForbiddenOperationException.class, () -> interceptor.preSend(
                subscribeMessage("/topic/devices/" + deviceId, devicePrincipal()), null));
    }

    @Test
    void subscribeFailsWhenSessionRevokedAfterConnect() {
        session.setRevokedAt(LocalDateTime.now());
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));

        assertThrows(ForbiddenOperationException.class, () -> interceptor.preSend(
                subscribeMessage("/topic/devices/" + deviceId, devicePrincipal()), null));
    }
}
