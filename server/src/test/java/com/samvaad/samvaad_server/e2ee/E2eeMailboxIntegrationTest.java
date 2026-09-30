package com.samvaad.samvaad_server.e2ee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.samvaad.samvaad_server.TestcontainersConfiguration;
import com.samvaad.samvaad_server.auth.AuthenticationService;
import com.samvaad.samvaad_server.auth.dto.LoginRequestDto;
import com.samvaad.samvaad_server.auth.dto.LoginResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.AdvanceCursorDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeCiphertextItemDto;
import com.samvaad.samvaad_server.e2ee.dto.E2eeEnvelopeSubmitDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageDto;
import com.samvaad.samvaad_server.e2ee.dto.SubmitE2eeMessageResponseDto;
import com.samvaad.samvaad_server.e2ee.dto.SyncCursorDto;
import com.samvaad.samvaad_server.e2ee.exception.E2eeMessageConflictException;
import com.samvaad.samvaad_server.messaging.InvalidPaginationException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.e2ee.message.E2eeEnvelopeRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMailboxRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMessageRepo;
import com.samvaad.samvaad_server.e2ee.message.E2eeMessageService;
import com.samvaad.samvaad_server.e2ee.message.E2eeSyncCursorRepo;
import com.samvaad.samvaad_server.exception.ForbiddenOperationException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotFoundException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestDto;
import com.samvaad.samvaad_server.friendrequest.FriendRequestService;
import com.samvaad.samvaad_server.messaging.ConversationNotFoundException;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.CreateUserRequestDto;
import com.samvaad.samvaad_server.user.User;
import com.samvaad.samvaad_server.user.UserRepo;
import com.samvaad.samvaad_server.user.UserService;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;

/**
 * Ciphertext mailbox + history transport: batched submission, idempotent
 * retry, per-device delivery and acknowledgement, durable history
 * independent of mailbox state, monotonic processed-cursors, server
 * ordering, and the crypto-blind security boundary.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class E2eeMailboxIntegrationTest {

    @Autowired
    private E2eeMessageService messageService;

    @Autowired
    private E2eeMessageRepo messageRepo;

    @Autowired
    private E2eeEnvelopeRepo envelopeRepo;

    @Autowired
    private E2eeMailboxRepo mailboxRepo;

    @Autowired
    private E2eeSyncCursorRepo cursorRepo;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeDeviceService deviceService;


    @Autowired
    private com.samvaad.samvaad_server.messaging.ConversationRepo conversationRepo;

    @Autowired
    private com.samvaad.samvaad_server.friendrequest.FriendRequestRepo friendRequestRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeDeviceRepo deviceRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.device.E2eeOneTimePrekeyRepo prekeyRepo;

    @Autowired
    private com.samvaad.samvaad_server.e2ee.recovery.E2eeRecoveryCodeRepo recoveryCodeRepo;

    @Autowired
    private SessionRepo sessionRepo;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserProfileRepo userProfileRepo;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        mailboxRepo.deleteAll();
        envelopeRepo.deleteAll();
        messageRepo.deleteAll();
        cursorRepo.deleteAll();
        prekeyRepo.deleteAll();
        recoveryCodeRepo.deleteAll();
        conversationRepo.deleteAll();
        friendRequestRepo.deleteAll();
        sessionRepo.deleteAll();
        deviceRepo.deleteAll();
        userProfileRepo.deleteAll();
        userRepo.deleteAll();
    }

    private User createUser(String username) {
        return userRepo.findById(userService
                .createUser(new CreateUserRequestDto(username, "secret123", username + "@example.com"))
                .getUserId()).orElseThrow();
    }

    private LoginResponseDto login(String username) {
        return authenticationService.login(
                new LoginRequestDto(username, "secret123", null, ClientPlatform.WEB, "Test", "1.0.0"),
                "127.0.0.1",
                "UserAgent");
    }

    private record Device(UUID deviceId, UUID sessionId) {
    }

    private Device bootstrap(User user, int seed) {
        LoginResponseDto login = login(user.getUsername());
        UUID deviceId = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.WEB))
                .getDevice().getDeviceId();
        return new Device(deviceId, login.sessionId());
    }

    private Device secondDevice(User user, UUID trustedSessionId, int seed) {
        LoginResponseDto login = login(user.getUsername());
        UUID pending = deviceService.enrollDevice(
                user.getUserId(), login.sessionId(), E2eeTestKeys.enrollRequest(seed, ClientPlatform.TUI))
                .getDevice().getDeviceId();
        deviceService.approveDevice(user.getUserId(), trustedSessionId, pending);
        return new Device(pending, login.sessionId());
    }

    private void befriend(User first, User second) {
        FriendRequestDto request = friendRequestService.sendRequest(first.getUserId(), second.getUsername());
        friendRequestService.acceptRequest(second.getUserId(), request.getRequestId());
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] un64(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private E2eeEnvelopeSubmitDto envelope(UUID sender, UUID recipient, String type, byte[] ciphertext) {
        E2eeEnvelopeSubmitDto dto = new E2eeEnvelopeSubmitDto();
        dto.setSenderDeviceId(sender);
        dto.setRecipientDeviceId(recipient);
        dto.setEnvelopeType(type);
        dto.setCiphertext(b64(ciphertext));
        return dto;
    }

    private SubmitE2eeMessageDto submit(UUID requestId, E2eeEnvelopeSubmitDto... envelopes) {
        SubmitE2eeMessageDto dto = new SubmitE2eeMessageDto();
        dto.setMessageRequestId(requestId);
        dto.setEnvelopes(List.of(envelopes));
        return dto;
    }

    private record Pair(User alice, User bob, Device a1, Device b1) {
    }

    private Pair provisionPair(String suffix) {
        User alice = createUser("mb_alice_" + suffix);
        User bob = createUser("mb_bob_" + suffix);
        Device a1 = bootstrap(alice, 100);
        Device b1 = bootstrap(bob, 200);
        befriend(alice, bob);
        return new Pair(alice, bob, a1, b1);
    }

    @Test
    void submitAndFetchDeliversOpaqueBytes() {
        Pair pair = provisionPair("basic");
        UUID requestId = UUID.randomUUID();
        byte[] ciphertext = "opaque-bytes-1".getBytes();

        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", ciphertext)));
        assertTrue(response.isCreatedNew());
        assertEquals(1L, response.getSequenceNumber());

        List<E2eeCiphertextItemDto> mailbox = messageService.fetchMailbox(
                pair.bob().getUserId(), pair.b1().sessionId(), 50);
        assertEquals(1, mailbox.size());
        E2eeCiphertextItemDto item = mailbox.get(0);
        assertArrayEquals(ciphertext, un64(item.getCiphertext()));
        assertEquals("PREKEY_INIT", item.getEnvelopeType());
        assertEquals(pair.a1().deviceId(), item.getSenderDeviceId());
        assertEquals(pair.alice().getUserId(), item.getSenderUserId());
        assertEquals(response.getMessageId(), item.getMessageId());
        assertEquals(response.getConversationId(), item.getConversationId());
        assertEquals(1L, item.getSequenceNumber());
    }

    @Test
    void retryIdenticalIsIdempotent() {
        Pair pair = provisionPair("retry");
        UUID requestId = UUID.randomUUID();
        SubmitE2eeMessageDto first =
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()));
        SubmitE2eeMessageResponseDto created = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(), first);
        SubmitE2eeMessageResponseDto replay = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));

        assertTrue(created.isCreatedNew());
        assertTrue(!replay.isCreatedNew());
        assertEquals(created.getMessageId(), replay.getMessageId());
        assertEquals(created.getSequenceNumber(), replay.getSequenceNumber());
        assertEquals(1, messageRepo.findAll().size());
        assertEquals(1, mailboxRepo.countByRecipientDeviceId(pair.b1().deviceId()));

        List<E2eeCiphertextItemDto> fetched = messageService.fetchMailbox(
                pair.bob().getUserId(), pair.b1().sessionId(), 50);
        assertEquals(1, fetched.size());
        assertArrayEquals("ct".getBytes(), un64(fetched.get(0).getCiphertext()));
    }

    @Test
    void sameRequestDifferentCiphertextRejected() {
        Pair pair = provisionPair("conflict");
        UUID requestId = UUID.randomUUID();
        messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct-a".getBytes())));
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                        submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct-b".getBytes()))));
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                        submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "RATCHET", "ct-a".getBytes()))));
    }

    @Test
    void sameRequestDifferentRecipientsRejected() {
        Pair pair = provisionPair("recipients");
        Device b2 = secondDevice(pair.bob(), pair.b1().sessionId(), 201);
        UUID requestId = UUID.randomUUID();
        messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                        submit(requestId, envelope(pair.a1().deviceId(), b2.deviceId(), "PREKEY_INIT", "ct".getBytes()))));
    }

    @Test
    void partialFailurePersistsNothing() {
        Pair pair = provisionPair("atomic");
        UUID requestId = UUID.randomUUID();
        assertThrows(DeviceNotFoundException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId,
                        envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()),
                        envelope(pair.a1().deviceId(), UUID.randomUUID(), "PREKEY_INIT", "ct".getBytes()))));
        assertEquals(0, messageRepo.findAll().size());
        assertEquals(0, envelopeRepo.findAll().size());
        assertEquals(0, mailboxRepo.countByRecipientDeviceId(pair.b1().deviceId()));
    }

    @Test
    void repeatedFetchBeforeAckIdentical() {
        Pair pair = provisionPair("refetch");
        messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "RATCHET", "ct".getBytes())));
        List<E2eeCiphertextItemDto> first =
                messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50);
        List<E2eeCiphertextItemDto> second =
                messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50);
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(first.get(0).getMessageId(), second.get(0).getMessageId());
        assertArrayEquals(un64(first.get(0).getCiphertext()), un64(second.get(0).getCiphertext()));
    }

    @Test
    void ackIsIdempotentAndPerDevice() {
        Pair pair = provisionPair("ack");
        Device b2 = secondDevice(pair.bob(), pair.b1().sessionId(), 202);
        UUID requestId = UUID.randomUUID();
        SubmitE2eeMessageResponseDto submitted = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId,
                        envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct-1".getBytes()),
                        envelope(pair.a1().deviceId(), b2.deviceId(), "PREKEY_INIT", "ct-2".getBytes())));
        UUID messageId = submitted.getMessageId();

        assertEquals(1, messageService.acknowledge(
                pair.bob().getUserId(), pair.b1().sessionId(), List.of(messageId)).getAcknowledged());
        assertEquals(0, messageService.acknowledge(
                pair.bob().getUserId(), pair.b1().sessionId(), List.of(messageId)).getAcknowledged());
        assertEquals(0, messageService.acknowledge(
                pair.bob().getUserId(), pair.b1().sessionId(), List.of()).getAcknowledged());

        // Device B2 is unaffected by B1's acknowledgement.
        List<E2eeCiphertextItemDto> b2Mailbox =
                messageService.fetchMailbox(pair.bob().getUserId(), b2.sessionId(), 50);
        assertEquals(1, b2Mailbox.size());
        assertArrayEquals("ct-2".getBytes(), un64(b2Mailbox.get(0).getCiphertext()));
        assertTrue(messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50).isEmpty());
    }

    @Test
    void ackDeletesMailboxNotHistory() {
        Pair pair = provisionPair("history");
        UUID requestId = UUID.randomUUID();
        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));

        messageService.acknowledge(pair.bob().getUserId(), pair.b1().sessionId(), List.of(response.getMessageId()));
        assertTrue(messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50).isEmpty());

        List<E2eeCiphertextItemDto> history = messageService.fetchHistory(
                pair.bob().getUserId(), pair.b1().sessionId(), response.getConversationId(), 0, 20);
        assertEquals(1, history.size());
        assertArrayEquals("ct".getBytes(), un64(history.get(0).getCiphertext()));
        assertEquals(1L, history.get(0).getSequenceNumber());
    }

    @Test
    void historyPaginationUsesSequenceCursor() {
        Pair pair = provisionPair("page");
        UUID conversationId = null;
        for (int i = 1; i <= 3; i++) {
            SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                    pair.alice().getUserId(), pair.a1().sessionId(),
                    submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(),
                            "RATCHET", ("ct-" + i).getBytes())));
            conversationId = response.getConversationId();
            assertEquals(i, response.getSequenceNumber());
        }
        List<E2eeCiphertextItemDto> page = messageService.fetchHistory(
                pair.bob().getUserId(), pair.b1().sessionId(), conversationId, 1, 1);
        assertEquals(1, page.size());
        assertEquals(2L, page.get(0).getSequenceNumber());
        assertArrayEquals("ct-2".getBytes(), un64(page.get(0).getCiphertext()));
        List<E2eeCiphertextItemDto> rest = messageService.fetchHistory(
                pair.bob().getUserId(), pair.b1().sessionId(), conversationId, 2, 20);
        assertEquals(1, rest.size());
        assertEquals(3L, rest.get(0).getSequenceNumber());
    }

    @Test
    void cursorMeansProcessedNotFetchedOrAcked() {
        Pair pair = provisionPair("cursor");
        UUID requestId1 = UUID.randomUUID();
        UUID requestId2 = UUID.randomUUID();
        SubmitE2eeMessageResponseDto first = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId1, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct-1".getBytes())));
        SubmitE2eeMessageResponseDto secondMsg = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId2, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "RATCHET", "ct-2".getBytes())));
        UUID conversationId = first.getConversationId();

        SyncCursorDto initial =
                messageService.readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId);
        assertEquals(0L, initial.getThroughSequence());

        // Fetching and acknowledging do not move the cursor.
        messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50);
        messageService.acknowledge(pair.bob().getUserId(), pair.b1().sessionId(),
                List.of(first.getMessageId(), secondMsg.getMessageId()));
        assertEquals(0L, messageService
                .readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId).getThroughSequence());

        // Explicit advance works, repeats are safe, backwards moves rejected.
        AdvanceCursorDto advance = new AdvanceCursorDto();
        advance.setConversationId(conversationId);
        advance.setThroughSequence(2L);
        assertEquals(2L, messageService
                .advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), advance).getThroughSequence());
        assertEquals(2L, messageService
                .advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), advance).getThroughSequence());
        AdvanceCursorDto backwards = new AdvanceCursorDto();
        backwards.setConversationId(conversationId);
        backwards.setThroughSequence(1L);
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), backwards));
        assertEquals(2L, messageService
                .readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId).getThroughSequence());
    }

    @Test
    void cursorEnforcesSequenceRangeWithoutMutatingOnRejection() {
        Pair pair = provisionPair("cursorbounds");
        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(),
                        "PREKEY_INIT", "ct-1".getBytes())));
        UUID conversationId = response.getConversationId();
        assertEquals(1L, response.getSequenceNumber());

        // Exactly lastSequenceNumber is valid.
        AdvanceCursorDto atEnd = new AdvanceCursorDto();
        atEnd.setConversationId(conversationId);
        atEnd.setThroughSequence(1L);
        assertEquals(1L, messageService
                .advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), atEnd).getThroughSequence());

        // Beyond lastSequenceNumber is rejected and leaves the cursor unchanged.
        AdvanceCursorDto beyond = new AdvanceCursorDto();
        beyond.setConversationId(conversationId);
        beyond.setThroughSequence(2L);
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), beyond));
        assertEquals(1L, messageService
                .readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId).getThroughSequence());

        // Long.MAX_VALUE is rejected and leaves the cursor unchanged.
        AdvanceCursorDto extreme = new AdvanceCursorDto();
        extreme.setConversationId(conversationId);
        extreme.setThroughSequence(Long.MAX_VALUE);
        assertThrows(E2eeMessageConflictException.class,
                () -> messageService.advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), extreme));
        assertEquals(1L, messageService
                .readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId).getThroughSequence());

        // Negative values are rejected at the service layer itself.
        AdvanceCursorDto negative = new AdvanceCursorDto();
        negative.setConversationId(conversationId);
        negative.setThroughSequence(-1L);
        assertThrows(InvalidPaginationException.class,
                () -> messageService.advanceCursor(pair.bob().getUserId(), pair.b1().sessionId(), negative));
        assertEquals(1L, messageService
                .readCursor(pair.bob().getUserId(), pair.b1().sessionId(), conversationId).getThroughSequence());
    }

    @Test
    void concurrentSubmitSameRequestIdCreatesOneMessage() throws Exception {
        Pair pair = provisionPair("conc");
        UUID requestId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<SubmitE2eeMessageResponseDto> first = new AtomicReference<>();
        AtomicReference<SubmitE2eeMessageResponseDto> second = new AtomicReference<>();
        Future<?> a = executor.submit(() -> {
            barrier.await(30, TimeUnit.SECONDS);
            first.set(messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                    submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
            return null;
        });
        Future<?> b = executor.submit(() -> {
            barrier.await(30, TimeUnit.SECONDS);
            second.set(messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                    submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
            return null;
        });
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(first.get().getMessageId(), second.get().getMessageId());
        assertEquals(1, messageRepo.findAll().size());
        assertEquals(1, mailboxRepo.countByRecipientDeviceId(pair.b1().deviceId()));
        assertTrue(first.get().isCreatedNew() != second.get().isCreatedNew(),
                "exactly one submission must win creation");
    }

    @Test
    void orderingAcrossSenderDevices() {
        Pair pair = provisionPair("order");
        Device a2 = secondDevice(pair.alice(), pair.a1().sessionId(), 101);
        SubmitE2eeMessageResponseDto first = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct-1".getBytes())));
        SubmitE2eeMessageResponseDto second = messageService.submitMessage(
                pair.alice().getUserId(), a2.sessionId(),
                submit(UUID.randomUUID(), envelope(a2.deviceId(), pair.b1().deviceId(), "RATCHET", "ct-2".getBytes())));
        assertEquals(first.getConversationId(), second.getConversationId());
        assertEquals(1L, first.getSequenceNumber());
        assertEquals(2L, second.getSequenceNumber());

        List<E2eeCiphertextItemDto> mailbox =
                messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50);
        assertEquals(2, mailbox.size());
        assertEquals(1L, mailbox.get(0).getSequenceNumber());
        assertEquals(2L, mailbox.get(1).getSequenceNumber());
        assertEquals(pair.a1().deviceId(), mailbox.get(0).getSenderDeviceId());
        assertEquals(a2.deviceId(), mailbox.get(1).getSenderDeviceId());
    }

    @Test
    void invalidEnvelopeMetadataRejected() {
        Pair pair = provisionPair("meta");
        E2eeEnvelopeSubmitDto badType =
                envelope(pair.a1().deviceId(), pair.b1().deviceId(), "SIGNAL", "ct".getBytes());
        assertThrows(InvalidKeyMaterialException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(), submit(UUID.randomUUID(), badType)));
        E2eeEnvelopeSubmitDto badBase64 =
                envelope(pair.a1().deviceId(), pair.b1().deviceId(), "RATCHET", "ct".getBytes());
        badBase64.setCiphertext("!!!not-base64!!!");
        assertThrows(InvalidKeyMaterialException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(), submit(UUID.randomUUID(), badBase64)));
        assertEquals(0, messageRepo.findAll().size());
    }

    @Test
    void unauthorizedSubmitRejected() {
        Pair pair = provisionPair("unauth");
        User stranger = createUser("mb_stranger_unauth");
        Device s1 = bootstrap(stranger, 300);

        // Non-friend sender.
        assertThrows(ForbiddenOperationException.class, () -> messageService.submitMessage(
                stranger.getUserId(), s1.sessionId(),
                submit(UUID.randomUUID(), envelope(s1.deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
        // Unknown recipient device.
        assertThrows(DeviceNotFoundException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), UUID.randomUUID(), "PREKEY_INIT", "ct".getBytes()))));
        assertEquals(0, messageRepo.findAll().size());
    }

    @Test
    void revokedRecipientReceivesNothing() {
        Pair pair = provisionPair("revrecv");
        deviceService.revokeDevice(pair.bob().getUserId(), pair.b1().deviceId());
        assertThrows(DeviceNotFoundException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
        assertEquals(0, messageRepo.findAll().size());
    }

    @Test
    void revokedSenderCannotSubmit() {
        Pair pair = provisionPair("revsend");
        deviceService.revokeDevice(pair.alice().getUserId(), pair.a1().deviceId());
        assertThrows(ForbiddenOperationException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
        assertEquals(0, messageRepo.findAll().size());
    }

    @Test
    void senderSpoofRejected() {
        Pair pair = provisionPair("spoof");
        Device a2 = secondDevice(pair.alice(), pair.a1().sessionId(), 102);
        assertThrows(ForbiddenOperationException.class, () -> messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(a2.deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes()))));
        assertEquals(0, messageRepo.findAll().size());
    }

    @Test
    void ackScopedToOwnDevice() {
        Pair pair = provisionPair("ackscope");
        UUID requestId = UUID.randomUUID();
        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));

        // Alice's device acknowledges Bob's message id: nothing happens.
        assertEquals(0, messageService.acknowledge(
                pair.alice().getUserId(), pair.a1().sessionId(), List.of(response.getMessageId())).getAcknowledged());
        assertEquals(1, mailboxRepo.countByRecipientDeviceId(pair.b1().deviceId()));
    }

    @Test
    void mailboxIsolatedPerDeviceAndUser() {
        Pair pair = provisionPair("isolate");
        User stranger = createUser("mb_stranger_isolate");
        Device s1 = bootstrap(stranger, 301);

        messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));

        // Alice has nothing addressed to her own device; the stranger's
        // session is device-bound but owns no mailbox entries.
        assertTrue(messageService.fetchMailbox(pair.alice().getUserId(), pair.a1().sessionId(), 50).isEmpty());
        assertTrue(messageService.fetchMailbox(stranger.getUserId(), s1.sessionId(), 50).isEmpty());
        List<E2eeCiphertextItemDto> bobMailbox =
                messageService.fetchMailbox(pair.bob().getUserId(), pair.b1().sessionId(), 50);
        assertEquals(1, bobMailbox.size());
    }

    @Test
    void historyRequiresParticipation() {
        Pair pair = provisionPair("histsec");
        SubmitE2eeMessageResponseDto response = messageService.submitMessage(
                pair.alice().getUserId(), pair.a1().sessionId(),
                submit(UUID.randomUUID(), envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));
        User stranger = createUser("mb_stranger_histsec");
        Device s1 = bootstrap(stranger, 302);
        assertThrows(ForbiddenOperationException.class, () -> messageService.fetchHistory(
                stranger.getUserId(), s1.sessionId(), response.getConversationId(), 0, 20));
        assertThrows(ConversationNotFoundException.class, () -> messageService.fetchHistory(
                pair.bob().getUserId(), pair.b1().sessionId(), UUID.randomUUID(), 0, 20));
    }

    @Test
    void newTablesHoldNoPrivateMaterial() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_name IN ('e2ee_messages', 'e2ee_envelopes', 'e2ee_mailbox', 'e2ee_sync_cursors')",
                String.class);
        assertTrue(columns.size() >= 20, "ciphertext transport tables must exist");
        for (String column : columns) {
            String lower = column.toLowerCase();
            assertTrue(!lower.contains("secret") && !lower.contains("private") && !lower.contains("plaintext"),
                    "public-transport tables must not hold private material: " + column);
        }
    }

    @Test
    void userDeletionCleansCiphertextTransport() {
        Pair pair = provisionPair("delete");
        UUID requestId = UUID.randomUUID();
        messageService.submitMessage(pair.alice().getUserId(), pair.a1().sessionId(),
                submit(requestId, envelope(pair.a1().deviceId(), pair.b1().deviceId(), "PREKEY_INIT", "ct".getBytes())));
        assertEquals(1, messageRepo.findAll().size());

        userService.deleteUser(pair.alice().getUserId());

        assertEquals(0, messageRepo.findAll().size());
        assertEquals(0, envelopeRepo.findAll().size());
        assertEquals(0, mailboxRepo.countByRecipientDeviceId(pair.b1().deviceId()));
        assertEquals(0, cursorRepo.findAll().size());
    }
}
