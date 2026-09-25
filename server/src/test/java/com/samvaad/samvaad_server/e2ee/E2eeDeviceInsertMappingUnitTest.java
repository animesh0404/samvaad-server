package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.samvaad.samvaad_server.e2ee.auth.DeviceApprovalAuthorizer;
import com.samvaad.samvaad_server.e2ee.crypto.KeyMaterialEnvelopeValidator;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService;
import com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryService;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;

/**
 * Deterministic classification of device-insertion integrity failures.
 *
 * <p>A true concurrent duplicate-identity race (pre-check passes, rival
 * commits, flush fails) cannot be orchestrated deterministically with the
 * current test infrastructure without brittle timing. The database unique
 * constraint remains the race-safe enforcer for that interleaving — proven
 * by the sequential duplicate integration tests — while these unit tests
 * pin the catch-block contract: clear-then-recheck maps only genuine
 * duplicates to 409 and rethrows everything else unchanged.
 */
@ExtendWith(MockitoExtension.class)
class E2eeDeviceInsertMappingUnitTest {

    @Mock
    private E2eeDeviceRepo deviceRepo;

    @Mock
    private E2eeOneTimePrekeyRepo prekeyRepo;

    @Mock
    private SessionRepo sessionRepo;

    @Mock
    private UserRepo userRepo;

    @Mock
    private FriendRequestService friendRequestService;

    @Mock
    private E2eeRecoveryService recoveryService;

    @Mock
    private KeyMaterialEnvelopeValidator envelopeValidator;

    @Mock
    private DeviceApprovalAuthorizer approvalAuthorizer;

    @Mock
    private EntityManager entityManager;

    private E2eeDeviceService deviceService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceService = new E2eeDeviceService(
                deviceRepo,
                prekeyRepo,
                sessionRepo,
                userRepo,
                friendRequestService,
                recoveryService,
                envelopeValidator,
                approvalAuthorizer,
                Duration.ofDays(7));
        ReflectionTestUtils.setField(deviceService, "entityManager", entityManager);
    }

    @Test
    void duplicatePrecheckMapsTo409WithoutFlush() {
        enrollStubs();
        given(deviceRepo.existsByDeviceIdentityPublicKey(any(byte[].class))).willReturn(true);

        assertThrows(DeviceAlreadyExistsException.class,
                () -> deviceService.enrollDevice(userId, sessionId, enrollRequest()));

        verify(deviceRepo, never()).saveAndFlush(any());
        verify(entityManager, never()).clear();
    }

    @Test
    void racedDuplicateMapsTo409AfterClear() {
        enrollStubs();
        given(deviceRepo.existsByDeviceIdentityPublicKey(any(byte[].class)))
                .willReturn(false, true);
        given(deviceRepo.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("unique violation"));

        assertThrows(DeviceAlreadyExistsException.class,
                () -> deviceService.enrollDevice(userId, sessionId, enrollRequest()));

        verify(entityManager).clear();
    }

    @Test
    void nonDuplicateIntegrityFailureIsRethrownUnchanged() {
        enrollStubs();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("not-null violation");
        given(deviceRepo.existsByDeviceIdentityPublicKey(any(byte[].class))).willReturn(false);
        given(deviceRepo.saveAndFlush(any())).willThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> deviceService.enrollDevice(userId, sessionId, enrollRequest())));

        verify(entityManager).clear();
    }

    private void enrollStubs() {
        User user = new User(userId);
        given(userRepo.findByIdWithLock(userId)).willReturn(Optional.of(user));
        given(deviceRepo.findByUserUserIdOrderByCreatedAtAsc(userId))
                .willReturn(Collections.emptyList());
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setUser(user);
        given(sessionRepo.findWithUserBySessionId(sessionId)).willReturn(Optional.of(session));
        given(deviceRepo.countByUserAndStatusIn(any(), any())).willReturn(0L);
    }

    private EnrollDeviceRequestDto enrollRequest() {
        EnrollDeviceRequestDto request = new EnrollDeviceRequestDto();
        request.setRegistrationId(1);
        request.setDeviceIdentityPublicKey(E2eeTestKeys.key(1));
        request.setSignedPrekeyId(1);
        request.setSignedPrekey(E2eeTestKeys.key(2));
        request.setSignedPrekeySignature(E2eeTestKeys.key(3, 64));
        request.setClientPlatform(ClientPlatform.WEB);
        return request;
    }
}
