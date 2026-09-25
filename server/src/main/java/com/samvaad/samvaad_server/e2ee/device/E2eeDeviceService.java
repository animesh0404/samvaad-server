package com.samvaad.samvaad_server.e2ee.device;

import com.samvaad.samvaad_server.common.logging.OperationalLog;
import com.samvaad.samvaad_server.e2ee.E2eeMapper;
import com.samvaad.samvaad_server.e2ee.E2eePolicy;
import com.samvaad.samvaad_server.e2ee.auth.DeviceApprovalAuthorizer;
import com.samvaad.samvaad_server.e2ee.crypto.KeyMaterialEnvelopeValidator;
import com.samvaad.samvaad_server.e2ee.dto.ClaimPrekeyResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.DeviceListDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollDeviceResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.EnrollmentState;
import com.samvaad.samvaad_server.e2ee.dto.OneTimePrekeyDto;
import com.samvaad.samvaad_server.e2ee.dto.RecipientDeviceDto;
import com.samvaad.samvaad_server.e2ee.dto.RecoveryEnrollRequestDto;
import com.samvaad.samvaad_server.e2ee.dto.RotateRecoveryCodesResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.UploadOneTimePrekeysDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotFoundException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidPrekeyBatchException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.e2ee.exception.PrekeyClaimConflictException;
import com.samvaad.samvaad_server.e2ee.exception.RecoveryRequiredException;
import com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryService;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.session.RevocationReason;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side E2EE device and public prekey directory (ADR 0018 foundation
 * slice). Every state-changing operation runs in a transaction with the
 * owning user row locked, following the session-capacity pattern, so
 * concurrent enrollments, approvals, and recovery attempts serialize per
 * account.
 *
 * <p>Session binding happens ONLY when a NEW device is created during
 * bootstrap, pending enrollment, or recovery enrollment. There is no API
 * that binds an existing session to an already-existing device, and owner
 * authorization alone never creates device trust. E2EE trust additionally
 * requires the bound device to be ACTIVE.
 */
@Service
public class E2eeDeviceService {

    private static final Logger log = LoggerFactory.getLogger(E2eeDeviceService.class);

    private static final Set<DeviceStatus> NON_REVOKED =
            EnumSet.of(DeviceStatus.PENDING, DeviceStatus.ACTIVE);

    private final E2eeDeviceRepo deviceRepo;
    private final E2eeOneTimePrekeyRepo prekeyRepo;
    private final SessionRepo sessionRepo;
    private final UserRepo userRepo;
    private final FriendRequestService friendRequestService;
    private final E2eeRecoveryService recoveryService;
    private final KeyMaterialEnvelopeValidator envelopeValidator;
    private final DeviceApprovalAuthorizer approvalAuthorizer;
    private final Duration pendingDeviceTtl;

    public E2eeDeviceService(
            E2eeDeviceRepo deviceRepo,
            E2eeOneTimePrekeyRepo prekeyRepo,
            SessionRepo sessionRepo,
            UserRepo userRepo,
            FriendRequestService friendRequestService,
            E2eeRecoveryService recoveryService,
            KeyMaterialEnvelopeValidator envelopeValidator,
            DeviceApprovalAuthorizer approvalAuthorizer,
            @Value("${samvaad.e2ee.pending-device-ttl:P7D}") Duration pendingDeviceTtl) {
        this.deviceRepo = deviceRepo;
        this.prekeyRepo = prekeyRepo;
        this.sessionRepo = sessionRepo;
        this.userRepo = userRepo;
        this.friendRequestService = friendRequestService;
        this.recoveryService = recoveryService;
        this.envelopeValidator = envelopeValidator;
        this.approvalAuthorizer = approvalAuthorizer;
        this.pendingDeviceTtl = pendingDeviceTtl;
    }

    @OperationalLog("e2ee.device.enroll")
    @Transactional
    public EnrollDeviceResponseDto enrollDevice(
            UUID callerUserId, UUID callerSessionId, EnrollDeviceRequestDto request) {
        DeviceMaterial material = decodeDeviceMaterial(request);

        User user = userRepo.findByIdWithLock(callerUserId)
                .orElseThrow(() -> new UserNotFoundException(callerUserId));
        expireStalePending(user);

        EnrollmentState state = enrollmentState(user);
        if (state == EnrollmentState.RECOVERY_REQUIRED) {
            log.warn("Device enrollment denied: recovery required userId={}", callerUserId);
            throw new RecoveryRequiredException();
        }

        long enrolled = deviceRepo.countByUserAndStatusIn(user, NON_REVOKED);
        if (enrolled >= E2eePolicy.MAX_ENROLLED_DEVICES) {
            log.warn("Device enrollment denied: device limit userId={}", callerUserId);
            throw new DeviceLimitExceededException();
        }

        DeviceStatus status = state == EnrollmentState.NEVER_ENROLLED
                ? DeviceStatus.ACTIVE
                : DeviceStatus.PENDING;
        E2eeDevice device = insertDevice(user, request, material, status);

        Session session = resolveOwnedSession(callerUserId, callerSessionId);
        session.setDeviceId(device.getDeviceId());
        sessionRepo.save(session);

        EnrollDeviceResponseDto response = new EnrollDeviceResponseDto();
        response.setDevice(E2eeMapper.toDeviceDto(device, 0L));
        response.setEnrollmentState(state);
        if (status == DeviceStatus.ACTIVE) {
            List<String> codes = recoveryService.createInitialSet(user);
            response.setRecoveryCodes(codes);
            log.info("First device enrolled userId={} deviceId={} sessionId={}",
                    callerUserId, device.getDeviceId(), callerSessionId);
        } else {
            log.info("Pending device enrolled userId={} deviceId={} sessionId={}",
                    callerUserId, device.getDeviceId(), callerSessionId);
        }
        return response;
    }

    @OperationalLog("e2ee.device.approve")
    @Transactional
    public DeviceDto approveDevice(UUID callerUserId, UUID callerSessionId, UUID deviceId) {
        E2eeDevice pending = deviceRepo.findByDeviceIdWithLock(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        requireOwner(callerUserId, pending);

        if (pending.isActive()) {
            return E2eeMapper.toDeviceDto(pending, prekeyRepo.countByDeviceAndConsumedAtIsNull(pending));
        }
        if (pending.isRevoked()) {
            throw new DeviceNotActiveException();
        }

        Session session = resolveOwnedSession(callerUserId, callerSessionId);
        E2eeDevice callerDevice = resolveBoundDevice(session);
        approvalAuthorizer.assertApprovalTrust(callerUserId, callerDevice, pending);

        pending.setStatus(DeviceStatus.ACTIVE);
        pending.setLastActiveAt(LocalDateTime.now());
        E2eeDevice approved = deviceRepo.save(pending);
        log.info("Device approved userId={} deviceId={} approverDeviceId={}",
                callerUserId, deviceId,
                callerDevice != null ? callerDevice.getDeviceId() : null);
        return E2eeMapper.toDeviceDto(approved, prekeyRepo.countByDeviceAndConsumedAtIsNull(approved));
    }

    @OperationalLog("e2ee.device.uploadPrekeys")
    @Transactional
    public DeviceDto uploadOneTimePrekeys(
            UUID callerUserId, UUID callerSessionId, UUID deviceId, UploadOneTimePrekeysDto request) {
        List<OneTimePrekeyDto> batch = request != null ? request.getPrekeys() : null;
        validateBatch(batch);

        E2eeDevice device = deviceRepo.findByDeviceIdWithLock(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        requireOwner(callerUserId, device);
        if (!device.isActive()) {
            throw new DeviceNotActiveException();
        }

        Session session = resolveOwnedSession(callerUserId, callerSessionId);
        if (session.getDeviceId() == null || !session.getDeviceId().equals(deviceId)) {
            log.warn("Prekey upload denied: session not bound to device userId={} deviceId={}",
                    callerUserId, deviceId);
            throw new ForbiddenOperationException();
        }

        List<E2eeOneTimePrekey> rows = new ArrayList<>(batch.size());
        Set<Integer> seen = new HashSet<>();
        for (OneTimePrekeyDto entry : batch) {
            if (entry.getPrekeyId() == null) {
                throw new InvalidPrekeyBatchException("prekey ID is required");
            }
            if (!seen.add(entry.getPrekeyId())) {
                throw new InvalidPrekeyBatchException("duplicate prekey ID in batch");
            }
            byte[] publicKey = E2eeMapper.decodeBase64("publicKey", entry.getPublicKey());
            envelopeValidator.validateOneTimePrekey(publicKey);
            E2eeOneTimePrekey row = new E2eeOneTimePrekey();
            row.setDevice(device);
            row.setPrekeyId(entry.getPrekeyId());
            row.setPublicKey(publicKey);
            rows.add(row);
        }
        try {
            prekeyRepo.saveAll(rows);
            prekeyRepo.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Prekey upload conflict: IDs already registered userId={} deviceId={}",
                    callerUserId, deviceId);
            throw new InvalidPrekeyBatchException("prekey IDs already registered for this device");
        }

        device.setLastActiveAt(LocalDateTime.now());
        E2eeDevice saved = deviceRepo.save(device);
        long available = prekeyRepo.countByDeviceAndConsumedAtIsNull(saved);
        log.info("One-time prekeys uploaded userId={} deviceId={} batchSize={} available={}",
                callerUserId, deviceId, batch.size(), available);
        return E2eeMapper.toDeviceDto(saved, available);
    }

    @OperationalLog("e2ee.device.claimPrekey")
    @Transactional
    public ClaimPrekeyResponseDto claimOneTimePrekey(
            UUID callerUserId, UUID deviceId, UUID requestId) {
        E2eeDevice device = deviceRepo.findByDeviceIdWithLock(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        if (!device.isActive()) {
            throw new DeviceNotFoundException(deviceId);
        }
        UUID ownerId = device.getUser().getUserId();
        if (!ownerId.equals(callerUserId)
                && !friendRequestService.areFriends(callerUserId, ownerId)) {
            log.warn("Prekey claim denied: not friends callerId={} deviceId={}", callerUserId, deviceId);
            throw new ForbiddenOperationException();
        }

        if (requestId != null) {
            E2eeOneTimePrekey replay = prekeyRepo
                    .findByDeviceAndConsumedByRequestId(device, requestId)
                    .orElse(null);
            if (replay != null) {
                log.debug("Prekey claim replay deviceId={} requestId={}", deviceId, requestId);
                return E2eeMapper.toClaimResponseDto(device, replay);
            }
        }

        E2eeOneTimePrekey consumed = prekeyRepo.findFirstAvailableForUpdate(device).orElse(null);
        if (consumed == null) {
            log.debug("Prekey claim fell back to signed prekey deviceId={}", deviceId);
            return E2eeMapper.toClaimResponseDto(device, null);
        }
        consumed.setConsumedAt(LocalDateTime.now());
        consumed.setConsumedByRequestId(requestId);
        try {
            prekeyRepo.saveAndFlush(consumed);
        } catch (DataIntegrityViolationException e) {
            log.warn("Prekey claim conflict deviceId={} requestId={}", deviceId, requestId);
            throw new PrekeyClaimConflictException();
        }
        device.setLastActiveAt(LocalDateTime.now());
        deviceRepo.save(device);
        log.debug("One-time prekey consumed deviceId={} prekeyId={}", deviceId, consumed.getPrekeyId());
        return E2eeMapper.toClaimResponseDto(device, consumed);
    }

    @OperationalLog("e2ee.device.list")
    @Transactional
    public DeviceListDto listDevices(UUID callerUserId) {
        User user = userRepo.findByIdWithLock(callerUserId)
                .orElseThrow(() -> new UserNotFoundException(callerUserId));
        expireStalePending(user);

        EnrollmentState state = enrollmentState(user);
        List<DeviceDto> devices = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(callerUserId).stream()
                .map(device -> E2eeMapper.toDeviceDto(
                        device, prekeyRepo.countByDeviceAndConsumedAtIsNull(device)))
                .toList();
        return new DeviceListDto(state, devices);
    }

    @Transactional(readOnly = true)
    public List<RecipientDeviceDto> getRecipientDevices(UUID callerUserId, String username) {
        User recipient = userRepo.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UserNotFoundException(username));
        if (!recipient.getUserId().equals(callerUserId)
                && !friendRequestService.areFriends(callerUserId, recipient.getUserId())) {
            log.warn("Device directory denied: not friends callerId={}", callerUserId);
            throw new ForbiddenOperationException();
        }
        return deviceRepo.findByUserUserIdOrderByCreatedAtAsc(recipient.getUserId()).stream()
                .filter(E2eeDevice::isActive)
                .map(device -> E2eeMapper.toRecipientDeviceDto(
                        device, prekeyRepo.countByDeviceAndConsumedAtIsNull(device) > 0))
                .toList();
    }

    @OperationalLog("e2ee.device.revoke")
    @Transactional
    public void revokeDevice(UUID callerUserId, UUID deviceId) {
        E2eeDevice device = deviceRepo.findByDeviceIdWithLock(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException(deviceId));
        requireOwner(callerUserId, device);
        if (device.isRevoked()) {
            log.debug("Device revoke no-op deviceId={}", deviceId);
            return;
        }
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(LocalDateTime.now());
        device.setRevocationReason(RevocationReason.DEVICE_REVOKED);
        deviceRepo.save(device);

        int revokedSessions = sessionRepo.revokeActiveSessionsByDeviceId(
                deviceId, LocalDateTime.now(), RevocationReason.DEVICE_REVOKED);
        log.info("Device revoked userId={} deviceId={} revokedSessions={}",
                callerUserId, deviceId, revokedSessions);
    }

    @OperationalLog("e2ee.recovery.enroll")
    @Transactional
    public DeviceDto recoverEnroll(
            UUID callerUserId, UUID callerSessionId, RecoveryEnrollRequestDto request) {
        if (request == null || request.getDevice() == null) {
            throw new InvalidRecoveryCodeException();
        }
        DeviceMaterial material = decodeDeviceMaterial(request.getDevice());

        User user = userRepo.findByIdWithLock(callerUserId)
                .orElseThrow(() -> new UserNotFoundException(callerUserId));
        expireStalePending(user);
        if (enrollmentState(user) != EnrollmentState.RECOVERY_REQUIRED) {
            log.warn("Recovery enrollment denied: account not in recovery state userId={}", callerUserId);
            throw new InvalidRecoveryCodeException();
        }

        // Atomic with device creation below: any failure rolls the consumption
        // back, and two concurrent uses resolve to a single winner.
        recoveryService.consumeCode(user, request.getRecoveryCode());

        E2eeDevice device = insertDevice(user, request.getDevice(), material, DeviceStatus.ACTIVE);
        Session session = resolveOwnedSession(callerUserId, callerSessionId);
        session.setDeviceId(device.getDeviceId());
        sessionRepo.save(session);

        log.info("Recovery enrollment userId={} deviceId={} sessionId={}",
                callerUserId, device.getDeviceId(), callerSessionId);
        return E2eeMapper.toDeviceDto(device, 0L);
    }

    @OperationalLog("e2ee.recovery.rotate")
    @Transactional
    public RotateRecoveryCodesResponseDto rotateRecoveryCodes(UUID callerUserId, UUID callerSessionId) {
        User user = userRepo.findByIdWithLock(callerUserId)
                .orElseThrow(() -> new UserNotFoundException(callerUserId));
        Session session = resolveOwnedSession(callerUserId, callerSessionId);
        E2eeDevice callerDevice = resolveBoundDevice(session);
        if (callerDevice == null || !callerDevice.isActive()
                || !callerUserId.equals(callerDevice.getUser().getUserId())) {
            log.warn("Recovery rotation denied: no trusted device session userId={}", callerUserId);
            throw new ForbiddenOperationException();
        }
        E2eeRecoveryService.RotateResult rotated = recoveryService.rotateSet(user);
        RotateRecoveryCodesResponseDto response = new RotateRecoveryCodesResponseDto();
        response.setSetId(rotated.setId());
        response.setRecoveryCodes(rotated.codes());
        return response;
    }

    private void validateBatch(List<OneTimePrekeyDto> batch) {
        if (batch == null || batch.size() != E2eePolicy.REPLENISH_BATCH_SIZE) {
            throw new InvalidPrekeyBatchException(
                    "batch must contain exactly " + E2eePolicy.REPLENISH_BATCH_SIZE + " prekeys");
        }
    }

    private DeviceMaterial decodeDeviceMaterial(EnrollDeviceRequestDto request) {
        if (request == null || request.getRegistrationId() == null || request.getSignedPrekeyId() == null) {
            throw new InvalidPrekeyBatchException("device material is required");
        }
        byte[] identityKey = E2eeMapper.decodeBase64(
                "deviceIdentityPublicKey", request.getDeviceIdentityPublicKey());
        byte[] signedPrekey = E2eeMapper.decodeBase64("signedPrekey", request.getSignedPrekey());
        byte[] signature = E2eeMapper.decodeBase64(
                "signedPrekeySignature", request.getSignedPrekeySignature());
        envelopeValidator.validateDeviceIdentityKey(identityKey);
        envelopeValidator.validateSignedPrekey(signedPrekey);
        envelopeValidator.validateSignedPrekeySignature(signature);
        return new DeviceMaterial(identityKey, signedPrekey, signature);
    }

    private E2eeDevice insertDevice(
            User user, EnrollDeviceRequestDto request, DeviceMaterial material, DeviceStatus status) {
        E2eeDevice device = new E2eeDevice();
        device.setUser(user);
        device.setRegistrationId(request.getRegistrationId());
        device.setDeviceIdentityPublicKey(material.identityKey());
        device.setSignedPrekeyId(request.getSignedPrekeyId());
        device.setSignedPrekey(material.signedPrekey());
        device.setSignedPrekeySignature(material.signature());
        device.setStatus(status);
        device.setClientPlatform(request.getClientPlatform());
        device.setClientName(request.getClientName());
        device.setClientVersion(request.getClientVersion());
        device.setLastActiveAt(LocalDateTime.now());
        try {
            return deviceRepo.saveAndFlush(device);
        } catch (DataIntegrityViolationException e) {
            log.warn("Device enrollment conflict: identity already enrolled userId={}", user.getUserId());
            throw new DeviceAlreadyExistsException();
        }
    }

    private Session resolveOwnedSession(UUID callerUserId, UUID callerSessionId) {
        Session session = sessionRepo.findWithUserBySessionId(callerSessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found for authenticated principal"));
        if (!callerUserId.equals(session.getUser().getUserId())) {
            throw new ForbiddenOperationException();
        }
        return session;
    }

    private E2eeDevice resolveBoundDevice(Session session) {
        if (session.getDeviceId() == null) {
            return null;
        }
        return deviceRepo.findById(session.getDeviceId()).orElse(null);
    }

    private void requireOwner(UUID callerUserId, E2eeDevice device) {
        if (!callerUserId.equals(device.getUser().getUserId())) {
            throw new ForbiddenOperationException();
        }
    }


    private EnrollmentState enrollmentState(User user) {
        List<E2eeDevice> devices = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId());
        if (devices.isEmpty()) {
            return EnrollmentState.NEVER_ENROLLED;
        }
        boolean hasActive = devices.stream().anyMatch(E2eeDevice::isActive);
        return hasActive ? EnrollmentState.ENROLLED_ACTIVE : EnrollmentState.RECOVERY_REQUIRED;
    }

    /**
     * Lazily expires abandoned PENDING enrollments past the configured TTL
     * so they do not permanently consume a device slot. Runs inside the
     * caller's transaction while the user row is locked.
     */
    private void expireStalePending(User user) {
        LocalDateTime cutoff = LocalDateTime.now().minus(pendingDeviceTtl);
        List<E2eeDevice> devices = deviceRepo.findByUserUserIdOrderByCreatedAtAsc(user.getUserId());
        for (E2eeDevice device : devices) {
            if (device.getStatus() != DeviceStatus.PENDING) {
                continue;
            }
            if (device.getCreatedAt() != null && !device.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            E2eeDevice locked = deviceRepo.findByDeviceIdWithLock(device.getDeviceId()).orElse(null);
            if (locked == null || locked.getStatus() != DeviceStatus.PENDING) {
                continue;
            }
            locked.setStatus(DeviceStatus.REVOKED);
            locked.setRevokedAt(LocalDateTime.now());
            locked.setRevocationReason(RevocationReason.PENDING_EXPIRED);
            deviceRepo.save(locked);
            sessionRepo.revokeActiveSessionsByDeviceId(
                    locked.getDeviceId(), LocalDateTime.now(), RevocationReason.PENDING_EXPIRED);
            log.info("Pending device expired userId={} deviceId={}",
                    user.getUserId(), locked.getDeviceId());
        }
    }

    private record DeviceMaterial(byte[] identityKey, byte[] signedPrekey, byte[] signature) {
    }
}
