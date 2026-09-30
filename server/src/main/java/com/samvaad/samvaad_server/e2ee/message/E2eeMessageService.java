package com.samvaad.samvaad_server.e2ee.message;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.samvaad.samvaad_server.common.logging.OperationalLog;
import com.samvaad.samvaad_server.e2ee.E2eeMapper;
import com.samvaad.samvaad_server.e2ee.E2eePolicy;
import com.samvaad.samvaad_server.e2ee.device.E2eeDevice;
import com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo;
import com.samvaad.samvaad_server.e2ee.dto.AckMailboxResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.AdvanceCursorDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.SyncCursorDto;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotFoundException;
import com.samvaad.samvaad_server.e2ee.exception.E2eeMessageConflictException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.e2ee.realtime.E2eeRealtimeDelivery;
import com.samvaad.samvaad_server.e2ee.realtime.E2eeRealtimeNotifier;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.messaging.Conversation;
import com.samvaad.samvaad_server.messaging.ConversationNotFoundException;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.messaging.InvalidPaginationException;
import com.samvaad.samvaad_server.session.Session;
import com.samvaad.samvaad_server.session.SessionRepo;

/**
 * Ciphertext transport: batched submission, durable per-device history,
 * per-device mailbox delivery with acknowledgement, and per-device sync
 * cursors. Cryptographically blind throughout — ciphertext is decoded
 * from transport encoding and compared/stored as opaque bytes only;
 * envelope types are persisted exactly as supplied without interpretation.
 */
@Service
public class E2eeMessageService {

    static final int MAX_LIMIT = 100;
    static final int DEFAULT_MAILBOX_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(E2eeMessageService.class);

    private final E2eeMessageRepo messageRepo;
    private final E2eeEnvelopeRepo envelopeRepo;
    private final E2eeMailboxRepo mailboxRepo;
    private final E2eeSyncCursorRepo cursorRepo;
    private final E2eeDeviceRepo deviceRepo;
    private final ConversationRepo conversationRepo;
    private final SessionRepo sessionRepo;
    private final FriendRequestService friendRequestService;
    private final E2eeRealtimeNotifier realtimeNotifier;
    private final TransactionTemplate writeTx;

    public E2eeMessageService(
            E2eeMessageRepo messageRepo,
            E2eeEnvelopeRepo envelopeRepo,
            E2eeMailboxRepo mailboxRepo,
            E2eeSyncCursorRepo cursorRepo,
            E2eeDeviceRepo deviceRepo,
            ConversationRepo conversationRepo,
            SessionRepo sessionRepo,
            FriendRequestService friendRequestService,
            E2eeRealtimeNotifier realtimeNotifier,
            PlatformTransactionManager transactionManager) {
        this.messageRepo = messageRepo;
        this.envelopeRepo = envelopeRepo;
        this.mailboxRepo = mailboxRepo;
        this.cursorRepo = cursorRepo;
        this.deviceRepo = deviceRepo;
        this.conversationRepo = conversationRepo;
        this.sessionRepo = sessionRepo;
        this.friendRequestService = friendRequestService;
        this.realtimeNotifier = realtimeNotifier;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Submits one logical message. PostgreSQL aborts a transaction on the
     * first failed statement, so a lost insert race cannot be recovered
     * inside the same transaction — each attempt runs in a fresh
     * transaction and a raced insert is resolved by replaying the winner.
     * Content mismatches fail fast without retry.
     */
    @OperationalLog("e2ee.message.submit")
    public SubmitE2eeMessageResponseDto submitMessage(
            UUID callerUserId, UUID callerSessionId, SubmitE2eeMessageDto request) {
        DataIntegrityViolationException race = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            SubmitOutcome outcome;
            try {
                // TransactionTemplate commits synchronously before
                // returning, so anything derived from the returned outcome
                // below observes committed state only. A rolled-back
                // attempt throws instead of returning and never notifies.
                outcome = writeTx.execute(status -> doSubmit(callerUserId, callerSessionId, request));
            } catch (DataIntegrityViolationException e) {
                race = e;
                outcome = null;
            }
            if (outcome == null) {
                outcome = writeTx.execute(
                        status -> replayExisting(callerUserId, callerSessionId, request));
                if (outcome == null) {
                    // No committed winner: the failure was not a duplicate
                    // insert (e.g. a conversation-creation race whose winner
                    // rolled back), so the next attempt retries the full
                    // submit.
                    continue;
                }
            }
            if (!outcome.deliveries().isEmpty()) {
                realtimeNotifier.deliver(outcome.deliveries());
            }
            return outcome.response();
        }
        throw race;
    }

    /**
     * Result of one submit attempt: the HTTPS response plus, only when this
     * attempt created a new persisted message, the immutable per-device
     * deliveries to fan out after commit. Replays carry no deliveries, so
     * a duplicate request never generates a second realtime event.
     */
    private record SubmitOutcome(
            SubmitE2eeMessageResponseDto response, List<E2eeRealtimeDelivery> deliveries) {
    }

    private SubmitOutcome replayExisting(
            UUID callerUserId, UUID callerSessionId, SubmitE2eeMessageDto request) {
        UUID requestId = request.getMessageRequestId();
        Optional<E2eeMessage> winner = messageRepo.findByRequestId(requestId);
        if (winner.isEmpty()) {
            return null;
        }
        ValidatedSubmit validated = validateSubmit(callerUserId, callerSessionId, request);
        return new SubmitOutcome(
                replayResponse(winner.get(), validated.senderDevice(),
                        validated.conversation(), validated.decoded(), requestId),
                List.of());
    }

    private SubmitOutcome doSubmit(
            UUID callerUserId, UUID callerSessionId, SubmitE2eeMessageDto request) {
        UUID requestId = request.getMessageRequestId();
        ValidatedSubmit validated = validateSubmit(callerUserId, callerSessionId, request);
        E2eeDevice senderDevice = validated.senderDevice();
        List<DecodedEnvelope> decoded = validated.decoded();
        Conversation conversation = validated.conversation();

        Optional<E2eeMessage> replay = messageRepo.findByRequestId(requestId);
        if (replay.isPresent()) {
            return new SubmitOutcome(
                    replayResponse(replay.get(), senderDevice, conversation, decoded, requestId),
                    List.of());
        }

        conversation.setLastSequenceNumber(conversation.getLastSequenceNumber() + 1);
        E2eeMessage message = new E2eeMessage();
        message.setConversation(conversation);
        message.setSender(senderDevice.getUser());
        message.setSenderDeviceId(senderDevice.getDeviceId());
        message.setRequestId(requestId);
        message.setSequenceNumber(conversation.getLastSequenceNumber());
        message.setServerTimestamp(LocalDateTime.now());
        // Any integrity violation (lost insert race) propagates: the caller
        // retries in a fresh transaction because this one is aborted.
        messageRepo.saveAndFlush(message);
        List<E2eeEnvelope> envelopes = persistEnvelopesAndMailbox(message, decoded);
        log.info("Ciphertext submitted messageId={} conversationId={} senderDeviceId={} sequence={} requestId={} envelopes={} createdNew=true",
                message.getMessageId(), conversation.getConversationId(),
                senderDevice.getDeviceId(), message.getSequenceNumber(),
                requestId, decoded.size());
        return new SubmitOutcome(
                toResponse(message, conversation, decoded, true),
                toDeliveries(message, envelopes));
    }

    private ValidatedSubmit validateSubmit(
            UUID callerUserId, UUID callerSessionId, SubmitE2eeMessageDto request) {
        E2eeDevice senderDevice = requireActiveOwnedDevice(callerUserId, callerSessionId);
        List<DecodedEnvelope> decoded = decodeEnvelopes(request.getEnvelopes());
        for (DecodedEnvelope envelope : decoded) {
            if (!envelope.senderDeviceId.equals(senderDevice.getDeviceId())) {
                log.warn("Ciphertext submit denied: sender spoof userId={}", callerUserId);
                throw new ForbiddenOperationException();
            }
        }
        long distinctRecipients =
                decoded.stream().map(envelope -> envelope.recipientDeviceId).distinct().count();
        if (distinctRecipients != decoded.size()) {
            throw new InvalidKeyMaterialException("duplicate recipient device in batch");
        }

        Map<UUID, E2eeDevice> recipients = loadActiveRecipients(callerUserId, decoded);
        UUID recipientUserId = singleRecipientUser(decoded, recipients);
        if (recipientUserId.equals(callerUserId)) {
            log.warn("Ciphertext submit denied: self-message userId={}", callerUserId);
            throw new ForbiddenOperationException();
        }
        if (!friendRequestService.areFriends(callerUserId, recipientUserId)) {
            log.warn("Ciphertext submit denied: not friends senderId={} recipientId={}",
                    callerUserId, recipientUserId);
            throw new ForbiddenOperationException();
        }
        return new ValidatedSubmit(senderDevice, decoded,
                findOrCreateConversation(callerUserId, recipientUserId));
    }

    @OperationalLog("e2ee.mailbox.fetch")
    @Transactional(readOnly = true)
    public List<E2eeCiphertextItemDto> fetchMailbox(UUID callerUserId, UUID callerSessionId, int limit) {
        validateLimit(limit);
        E2eeDevice device = ownedDeviceOrNull(callerUserId, callerSessionId);
        if (device == null) {
            return List.of();
        }
        List<E2eeMailboxEntry> entries = mailboxRepo
                .findByRecipientDeviceIdOrderByMessageServerTimestampAscMessageMessageIdAsc(
                        device.getDeviceId(), PageRequest.of(0, limit));
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<UUID, E2eeEnvelope> envelopes = new LinkedHashMap<>();
        for (E2eeEnvelope envelope : envelopeRepo.findByRecipientDeviceIdAndMessageMessageIdIn(
                device.getDeviceId(),
                entries.stream().map(entry -> entry.getMessage().getMessageId()).toList())) {
            envelopes.put(envelope.getMessage().getMessageId(), envelope);
        }
        List<E2eeCiphertextItemDto> items = new ArrayList<>(entries.size());
        for (E2eeMailboxEntry entry : entries) {
            E2eeEnvelope envelope = envelopes.get(entry.getMessage().getMessageId());
            if (envelope == null) {
                throw new IllegalStateException("Mailbox entry without envelope");
            }
            items.add(toItem(entry.getMessage(), device.getDeviceId(), envelope));
        }
        return items;
    }

    @OperationalLog("e2ee.mailbox.ack")
    @Transactional
    public AckMailboxResponseDto acknowledge(
            UUID callerUserId, UUID callerSessionId, List<UUID> messageIds) {
        E2eeDevice device = requireActiveOwnedDevice(callerUserId, callerSessionId);
        if (messageIds == null || messageIds.isEmpty()) {
            return new AckMailboxResponseDto(0);
        }
        int acknowledged = mailboxRepo.deleteByDeviceAndMessageIds(device.getDeviceId(), messageIds);
        log.debug("Mailbox acknowledged deviceId={} requested={} acknowledged={}",
                device.getDeviceId(), messageIds.size(), acknowledged);
        return new AckMailboxResponseDto(acknowledged);
    }

    @OperationalLog("e2ee.message.history")
    @Transactional(readOnly = true)
    public List<E2eeCiphertextItemDto> fetchHistory(
            UUID callerUserId, UUID callerSessionId,
            UUID conversationId, long afterSequence, int limit) {
        validateLimit(limit);
        if (afterSequence < 0) {
            throw new InvalidPaginationException("afterSequence must be >= 0");
        }
        Conversation conversation = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        requireParticipant(callerUserId, conversation);
        E2eeDevice device = requireActiveOwnedDevice(callerUserId, callerSessionId);
        return envelopeRepo
                .findByMessageConversationConversationIdAndRecipientDeviceIdAndMessageSequenceNumberGreaterThanOrderByMessageSequenceNumberAsc(
                        conversationId, device.getDeviceId(), afterSequence, PageRequest.of(0, limit))
                .stream()
                .map(envelope -> toItem(envelope.getMessage(), device.getDeviceId(), envelope))
                .toList();
    }

    @OperationalLog("e2ee.sync.advance")
    @Transactional
    public SyncCursorDto advanceCursor(UUID callerUserId, UUID callerSessionId, AdvanceCursorDto request) {
        Conversation conversation = conversationRepo.findById(request.getConversationId())
                .orElseThrow(() -> new ConversationNotFoundException(request.getConversationId()));
        requireParticipant(callerUserId, conversation);
        E2eeDevice device = requireActiveOwnedDevice(callerUserId, callerSessionId);
        if (request.getThroughSequence() < 0) {
            throw new InvalidPaginationException("throughSequence must be >= 0");
        }
        if (request.getThroughSequence() > conversation.getLastSequenceNumber()) {
            log.warn("Sync cursor denied: beyond last sequence deviceId={} conversationId={}",
                    device.getDeviceId(), conversation.getConversationId());
            throw new E2eeMessageConflictException("Sync cursor cannot move beyond last sequence");
        }
        E2eeSyncCursor cursor = cursorRepo
                .findLockedByDeviceAndConversation(device.getDeviceId(), conversation.getConversationId())
                .orElse(null);
        if (cursor == null) {
            cursor = new E2eeSyncCursor();
            cursor.setRecipientDeviceId(device.getDeviceId());
            cursor.setConversation(conversation);
            cursor.setThroughSequence(request.getThroughSequence());
            cursorRepo.saveAndFlush(cursor);
        } else if (request.getThroughSequence() < cursor.getThroughSequence()) {
            log.warn("Sync cursor denied: backwards move deviceId={} conversationId={}",
                    device.getDeviceId(), conversation.getConversationId());
            throw new E2eeMessageConflictException("Sync cursor cannot move backwards");
        } else {
            cursor.setThroughSequence(request.getThroughSequence());
            cursorRepo.save(cursor);
        }
        return toCursorDto(cursor);
    }

    @OperationalLog("e2ee.sync.read")
    @Transactional(readOnly = true)
    public SyncCursorDto readCursor(UUID callerUserId, UUID callerSessionId, UUID conversationId) {
        Conversation conversation = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        requireParticipant(callerUserId, conversation);
        E2eeDevice device = requireActiveOwnedDevice(callerUserId, callerSessionId);
        long through = cursorRepo
                .findByRecipientDeviceIdAndConversationConversationId(
                        device.getDeviceId(), conversationId)
                .map(E2eeSyncCursor::getThroughSequence)
                .orElse(0L);
        SyncCursorDto dto = new SyncCursorDto();
        dto.setConversationId(conversationId);
        dto.setThroughSequence(through);
        return dto;
    }

    /**
     * Removes all ciphertext transport rows touching the user's
     * conversations. Called from user deletion before conversations are
     * removed; mailbox, envelopes, messages, then cursors.
     */
    @Transactional
    public void deleteUserData(UUID userId) {
        mailboxRepo.deleteByParticipantUserId(userId);
        envelopeRepo.deleteByParticipantUserId(userId);
        messageRepo.deleteByParticipantUserId(userId);
        cursorRepo.deleteByParticipantUserId(userId);
    }

    private SubmitE2eeMessageResponseDto replayResponse(
            E2eeMessage existing, E2eeDevice senderDevice, Conversation conversation,
            List<DecodedEnvelope> decoded, UUID requestId) {
        if (!existing.getSender().getUserId().equals(senderDevice.getUser().getUserId())
                || !existing.getSenderDeviceId().equals(senderDevice.getDeviceId())
                || !existing.getConversation().getConversationId()
                        .equals(conversation.getConversationId())
                || !envelopesMatch(existing, decoded)) {
            log.warn("Ciphertext submit conflict: requestId reused requestId={}", requestId);
            throw new E2eeMessageConflictException(requestId);
        }
        log.debug("Ciphertext submit replay requestId={} messageId={} createdNew=false",
                requestId, existing.getMessageId());
        return toResponse(existing, conversation, decoded, false);
    }

    private boolean envelopesMatch(E2eeMessage existing, List<DecodedEnvelope> decoded) {
        List<E2eeEnvelope> stored = envelopeRepo.findByMessageRequestId(existing.getRequestId());
        if (stored.size() != decoded.size()) {
            return false;
        }
        Map<UUID, E2eeEnvelope> byDevice = new LinkedHashMap<>();
        for (E2eeEnvelope envelope : stored) {
            byDevice.put(envelope.getRecipientDeviceId(), envelope);
        }
        for (DecodedEnvelope envelope : decoded) {
            E2eeEnvelope other = byDevice.get(envelope.recipientDeviceId);
            if (other == null
                    || other.getEnvelopeType() != envelope.envelopeType
                    || !Arrays.equals(other.getCiphertext(), envelope.ciphertext)) {
                return false;
            }
        }
        return true;
    }

    private List<E2eeEnvelope> persistEnvelopesAndMailbox(
            E2eeMessage message, List<DecodedEnvelope> decoded) {
        List<E2eeEnvelope> envelopes = new ArrayList<>(decoded.size());
        List<E2eeMailboxEntry> entries = new ArrayList<>(decoded.size());
        for (DecodedEnvelope decodedEnvelope : decoded) {
            E2eeEnvelope envelope = new E2eeEnvelope();
            envelope.setMessage(message);
            envelope.setRecipientDeviceId(decodedEnvelope.recipientDeviceId);
            envelope.setEnvelopeType(decodedEnvelope.envelopeType);
            envelope.setCiphertext(decodedEnvelope.ciphertext);
            envelopes.add(envelope);

            E2eeMailboxEntry entry = new E2eeMailboxEntry();
            entry.setRecipientDeviceId(decodedEnvelope.recipientDeviceId);
            entry.setMessage(message);
            entries.add(entry);
        }
        envelopeRepo.saveAll(envelopes);
        mailboxRepo.saveAll(entries);
        envelopeRepo.flush();
        mailboxRepo.flush();
        return envelopes;
    }

    /**
     * Builds one immutable delivery per persisted envelope, each carrying
     * only the ciphertext addressed to that exact recipient device.
     * Called inside the creating transaction while entities are attached;
     * the resulting DTOs hold copied values only and are delivered after
     * the transaction commits.
     */
    private List<E2eeRealtimeDelivery> toDeliveries(
            E2eeMessage message, List<E2eeEnvelope> envelopes) {
        List<E2eeRealtimeDelivery> deliveries = new ArrayList<>(envelopes.size());
        for (E2eeEnvelope envelope : envelopes) {
            deliveries.add(new E2eeRealtimeDelivery(
                    envelope.getRecipientDeviceId(),
                    toItem(message, envelope.getRecipientDeviceId(), envelope)));
        }
        return List.copyOf(deliveries);
    }

    private SubmitE2eeMessageResponseDto toResponse(
            E2eeMessage message, Conversation conversation,
            List<DecodedEnvelope> decoded, boolean createdNew) {
        SubmitE2eeMessageResponseDto response = new SubmitE2eeMessageResponseDto();
        response.setMessageId(message.getMessageId());
        response.setConversationId(conversation.getConversationId());
        response.setSequenceNumber(message.getSequenceNumber());
        response.setServerTimestamp(message.getServerTimestamp());
        response.setAcceptedRecipientDevices(
                decoded.stream().map(envelope -> envelope.recipientDeviceId).toList());
        response.setCreatedNew(createdNew);
        return response;
    }

    private E2eeCiphertextItemDto toItem(
            E2eeMessage message, UUID recipientDeviceId, E2eeEnvelope envelope) {
        E2eeCiphertextItemDto dto = new E2eeCiphertextItemDto();
        dto.setMessageId(message.getMessageId());
        dto.setConversationId(message.getConversation().getConversationId());
        dto.setSequenceNumber(message.getSequenceNumber());
        dto.setSenderUserId(message.getSender().getUserId());
        dto.setSenderDeviceId(message.getSenderDeviceId());
        dto.setEnvelopeType(envelope.getEnvelopeType().name());
        dto.setCiphertext(E2eeMapper.encodeBase64(envelope.getCiphertext()));
        dto.setServerTimestamp(message.getServerTimestamp());
        if (!envelope.getRecipientDeviceId().equals(recipientDeviceId)) {
            throw new IllegalStateException("Envelope addressed to another device");
        }
        return dto;
    }

    private SyncCursorDto toCursorDto(E2eeSyncCursor cursor) {
        SyncCursorDto dto = new SyncCursorDto();
        dto.setConversationId(cursor.getConversation().getConversationId());
        dto.setThroughSequence(cursor.getThroughSequence());
        return dto;
    }

    private List<DecodedEnvelope> decodeEnvelopes(
            List<com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto> envelopes) {
        if (envelopes.size() > E2eePolicy.MAX_ENVELOPES_PER_SUBMIT) {
            throw new InvalidKeyMaterialException("too many envelopes in one submission");
        }
        List<DecodedEnvelope> decoded = new ArrayList<>(envelopes.size());
        for (com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto envelope : envelopes) {
            E2eeEnvelopeType type;
            try {
                type = E2eeEnvelopeType.valueOf(envelope.getEnvelopeType().trim());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new InvalidKeyMaterialException("envelopeType must be PREKEY_INIT or RATCHET");
            }
            byte[] ciphertext = E2eeMapper.decodeBase64("ciphertext", envelope.getCiphertext());
            if (ciphertext.length > E2eePolicy.MAX_CIPHERTEXT_BYTES_PER_ENVELOPE) {
                throw new InvalidKeyMaterialException("ciphertext exceeds the transport bound");
            }
            decoded.add(new DecodedEnvelope(
                    envelope.getSenderDeviceId(),
                    envelope.getRecipientDeviceId(),
                    type,
                    ciphertext));
        }
        return decoded;
    }

    private Map<UUID, E2eeDevice> loadActiveRecipients(UUID callerUserId, List<DecodedEnvelope> decoded) {
        List<UUID> ids = decoded.stream().map(envelope -> envelope.recipientDeviceId).distinct().toList();
        Map<UUID, E2eeDevice> devices = new LinkedHashMap<>();
        for (E2eeDevice device : deviceRepo.findAllById(ids)) {
            devices.put(device.getDeviceId(), device);
        }
        for (UUID id : ids) {
            E2eeDevice device = devices.get(id);
            if (device == null || !device.isActive()) {
                throw new DeviceNotFoundException(id);
            }
            if (device.getUser().getUserId().equals(callerUserId)) {
                log.warn("Ciphertext submit denied: own device as recipient userId={}", callerUserId);
                throw new ForbiddenOperationException();
            }
        }
        return devices;
    }

    private UUID singleRecipientUser(List<DecodedEnvelope> decoded, Map<UUID, E2eeDevice> recipients) {
        UUID owner = null;
        for (DecodedEnvelope envelope : decoded) {
            UUID current = recipients.get(envelope.recipientDeviceId).getUser().getUserId();
            if (owner == null) {
                owner = current;
            } else if (!owner.equals(current)) {
                throw new E2eeMessageConflictException(
                        "One logical message addresses a single recipient user");
            }
        }
        return owner;
    }

    private Conversation findOrCreateConversation(UUID senderId, UUID recipientId) {
        UUID participantA = senderId.compareTo(recipientId) <= 0 ? senderId : recipientId;
        UUID participantB = senderId.compareTo(recipientId) <= 0 ? recipientId : senderId;

        Optional<Conversation> existing =
                conversationRepo.findLockedByParticipants(participantA, participantB);
        if (existing.isPresent()) {
            return existing.get();
        }
        // A lost creation race aborts this transaction (PostgreSQL); the
        // submit loop retries in a fresh transaction that finds the winner.
        return conversationRepo.saveAndFlush(Conversation.between(senderId, recipientId));
    }

    private E2eeDevice requireActiveOwnedDevice(UUID callerUserId, UUID callerSessionId) {
        Session session = sessionRepo.findWithUserBySessionId(callerSessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found for authenticated principal"));
        if (!callerUserId.equals(session.getUser().getUserId())) {
            throw new ForbiddenOperationException();
        }
        if (session.getDeviceId() == null) {
            log.warn("Ciphertext transport denied: session not bound to a device userId={}", callerUserId);
            throw new ForbiddenOperationException();
        }
        E2eeDevice device = deviceRepo.findById(session.getDeviceId())
                .orElseThrow(() -> new ForbiddenOperationException());
        if (!callerUserId.equals(device.getUser().getUserId()) || !device.isActive()) {
            log.warn("Ciphertext transport denied: device not active/owned userId={} deviceId={}",
                    callerUserId, session.getDeviceId());
            throw new ForbiddenOperationException();
        }
        return device;
    }

    private E2eeDevice ownedDeviceOrNull(UUID callerUserId, UUID callerSessionId) {
        Session session = sessionRepo.findWithUserBySessionId(callerSessionId).orElse(null);
        if (session == null
                || !callerUserId.equals(session.getUser().getUserId())
                || session.getDeviceId() == null) {
            return null;
        }
        E2eeDevice device = deviceRepo.findById(session.getDeviceId()).orElse(null);
        if (device == null || !callerUserId.equals(device.getUser().getUserId())) {
            return null;
        }
        return device;
    }

    private void requireParticipant(UUID callerUserId, Conversation conversation) {
        if (!conversation.getParticipantA().equals(callerUserId)
                && !conversation.getParticipantB().equals(callerUserId)) {
            throw new ForbiddenOperationException();
        }
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidPaginationException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private record DecodedEnvelope(
            UUID senderDeviceId, UUID recipientDeviceId, E2eeEnvelopeType envelopeType, byte[] ciphertext) {
    }

    private record ValidatedSubmit(
            E2eeDevice senderDevice, List<DecodedEnvelope> decoded, Conversation conversation) {
    }
}
