package com.samvaad.samvaad_server.e2ee.client.fake;

import com.samvaad.samvaad_server.e2ee.client.CryptoTypes;
import com.samvaad.samvaad_server.e2ee.client.DeviceKeyStore;
import com.samvaad.samvaad_server.e2ee.client.PrekeyManager;
import com.samvaad.samvaad_server.e2ee.client.SessionStore;
import com.samvaad.samvaad_server.e2ee.client.SignalAdapter;
import com.samvaad.samvaad_server.e2ee.client.TrustStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stores for the fake slice. Durable contract = synchronous map write. */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static final class DeviceKeys implements DeviceKeyStore {
        private final UUID ownDeviceId;
        private final int registrationId;
        private SignalAdapter.LocalIdentity identity;
        private SignalAdapter.SignedPrekeyPair signed;
        private final Map<Integer, SignalAdapter.SealedPrivateHandle> otpPrivs =
                new ConcurrentHashMap<>();

        public DeviceKeys(UUID ownDeviceId, int registrationId) {
            this.ownDeviceId = ownDeviceId;
            this.registrationId = registrationId;
        }

        @Override
        public void provision(SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey) {
            this.identity = identity;
            this.signed = signedPrekey;
        }

        @Override
        public boolean isProvisioned() {
            return identity != null && signed != null;
        }

        @Override
        public UUID ownDeviceId() {
            return ownDeviceId;
        }

        @Override
        public int registrationId() {
            return registrationId;
        }

        @Override
        public byte[] identityPublicKey() {
            return Arrays.copyOf(identity.identityPublicKey(), identity.identityPublicKey().length);
        }

        @Override
        public SignalAdapter.SealedPrivateHandle identityPrivate() {
            return identity.identityPrivate();
        }

        @Override
        public SignalAdapter.SignedPrekeyPair signedPrekey() {
            return signed;
        }

        @Override
        public void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle) {
            otpPrivs.put(prekeyId, privateHandle);
        }

        @Override
        public Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId) {
            return Optional.ofNullable(otpPrivs.get(prekeyId));
        }

        @Override
        public void forgetOneTimePrivate(int prekeyId) {
            otpPrivs.remove(prekeyId);
        }
    }

    public static final class Sessions implements SessionStore {
        private final Map<UUID, CryptoTypes.SessionRecord> sessions = new ConcurrentHashMap<>();
        private final Map<String, CryptoTypes.OutboundSlot> slots = new ConcurrentHashMap<>();

        private static String key(UUID messageRequestId, UUID recipient) {
            return messageRequestId + ":" + recipient;
        }

        @Override
        public void saveSession(CryptoTypes.SessionRecord record) {
            sessions.put(record.peerDeviceId(), record);
        }

        @Override
        public Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId) {
            return Optional.ofNullable(sessions.get(peerDeviceId));
        }

        @Override
        public void deleteSession(UUID peerDeviceId) {
            sessions.remove(peerDeviceId);
        }

        @Override
        public List<CryptoTypes.SessionRecord> allSessions() {
            return List.copyOf(sessions.values());
        }

        @Override
        public void saveSlot(CryptoTypes.OutboundSlot slot) {
            if (slot.state() == CryptoTypes.OutboundSlotState.COMMITTED) {
                // Structural enforcement: COMMITTED rows are written only by
                // commitOutboundCiphertext, together with the advanced session.
                throw new IllegalStateException(
                        "COMMITTED slots must be written via commitOutboundCiphertext");
            }
            String k = key(slot.messageRequestId(), slot.recipientDeviceId());
            CryptoTypes.OutboundSlot existing = slots.get(k);
            checkBundleContinuity(existing, slot);
            slots.put(k, slot);
        }

        private static void checkBundleContinuity(
                CryptoTypes.OutboundSlot existing, CryptoTypes.OutboundSlot slot) {
            if (existing != null && existing.claimedBundle() != null && slot.claimedBundle() != null
                    && !existing.claimRequestId().equals(slot.claimRequestId())) {
                throw new IllegalArgumentException("slot claimRequestId changed; OTPK confusion");
            }
            if (existing != null && existing.claimedBundle() != null && slot.claimedBundle() != null
                    && !Arrays.equals(
                            existing.claimedBundle().identityPublicKey(),
                            slot.claimedBundle().identityPublicKey())) {
                throw new IllegalArgumentException("slot bundle changed; OTPK confusion");
            }
            if (existing != null && existing.claimedBundle() != null && slot.claimedBundle() != null
                    && (!existing.claimedBundle().userId().equals(slot.claimedBundle().userId())
                            || existing.claimedBundle().signalDeviceId()
                                    != slot.claimedBundle().signalDeviceId())) {
                throw new IllegalArgumentException("slot address changed; routing confusion");
            }
        }

        @Override
        public Optional<CryptoTypes.OutboundSlot> loadSlot(UUID messageRequestId, UUID recipientDeviceId) {
            return Optional.ofNullable(slots.get(key(messageRequestId, recipientDeviceId)));
        }

        @Override
        public List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId) {
            List<CryptoTypes.OutboundSlot> out = new ArrayList<>();
            slots.forEach((k, v) -> {
                if (v.messageRequestId().equals(messageRequestId)) {
                    out.add(v);
                }
            });
            return out;
        }

        @Override
        public List<CryptoTypes.OutboundSlot> pendingSlots() {
            List<CryptoTypes.OutboundSlot> out = new ArrayList<>();
            slots.values().forEach(s -> {
                if (s.state() != CryptoTypes.OutboundSlotState.ACKED) {
                    out.add(s);
                }
            });
            return out;
        }

        /**
         * Atomic commit for the fake slice: both rows are installed under one
         * monitor, so a crash inside this method (modelled by throwing before
         * entry) can never leave a session-advanced/ciphertext-missing state
         * visible to recovery. Production backends must provide the same
         * all-or-nothing boundary (single transaction).
         */
        @Override
        public synchronized void commitOutboundCiphertext(
                CryptoTypes.SessionRecord advancedSession, CryptoTypes.OutboundSlot committedSlot) {
            if (advancedSession == null || committedSlot == null) {
                throw new IllegalArgumentException("atomic commit requires session and slot");
            }
            if (committedSlot.state() != CryptoTypes.OutboundSlotState.COMMITTED) {
                throw new IllegalArgumentException("atomic commit requires a COMMITTED slot");
            }
            if (committedSlot.envelopeCiphertext() == null) {
                throw new IllegalArgumentException("atomic commit requires committed ciphertext");
            }
            if (!advancedSession.peerDeviceId().equals(committedSlot.recipientDeviceId())) {
                throw new IllegalArgumentException("atomic commit peer mismatch");
            }
            CryptoTypes.SessionRecord previous = sessions.get(advancedSession.peerDeviceId());
            if (previous == null
                    || advancedSession.encryptCounter() != previous.encryptCounter() + 1
                    || advancedSession.decryptCounter() != previous.decryptCounter()
                    || !Arrays.equals(
                            advancedSession.peerIdentityPublicKey(), previous.peerIdentityPublicKey())) {
                throw new IllegalStateException(
                        "atomic commit must advance exactly the previously committed session");
            }
            sessions.put(advancedSession.peerDeviceId(), advancedSession);
            CryptoTypes.OutboundSlot existingSlot =
                    slots.get(key(committedSlot.messageRequestId(), committedSlot.recipientDeviceId()));
            checkBundleContinuity(existingSlot, committedSlot);
            slots.put(
                    key(committedSlot.messageRequestId(), committedSlot.recipientDeviceId()),
                    committedSlot);
        }
    }

    /**
     * Unified in-memory {@link com.samvaad.samvaad_server.e2ee.client.ClientCryptoStore}
     * composing one of each single-concern store. Single-writer test scope:
     * the two atomic boundaries are serialized on this facade, while direct
     * pokes at the composed views (as tests do) bypass that monitor and are
     * therefore only safe single-threaded.
     */
    public static final class Combined
            implements com.samvaad.samvaad_server.e2ee.client.ClientCryptoStore {
        private final DeviceKeys keys;
        private final Sessions sessions;
        private final Trust trust;

        public Combined(DeviceKeys keys, Sessions sessions, Trust trust) {
            this.keys = java.util.Objects.requireNonNull(keys, "keys");
            this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
            this.trust = java.util.Objects.requireNonNull(trust, "trust");
        }

        public DeviceKeys keys() {
            return keys;
        }

        public Sessions sessions() {
            return sessions;
        }

        public Trust trust() {
            return trust;
        }

        @Override
        public void saveSession(CryptoTypes.SessionRecord record) {
            sessions.saveSession(record);
        }

        @Override
        public Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId) {
            return sessions.loadSession(peerDeviceId);
        }

        @Override
        public void deleteSession(UUID peerDeviceId) {
            sessions.deleteSession(peerDeviceId);
        }

        @Override
        public List<CryptoTypes.SessionRecord> allSessions() {
            return sessions.allSessions();
        }

        @Override
        public void saveSlot(CryptoTypes.OutboundSlot slot) {
            sessions.saveSlot(slot);
        }

        @Override
        public Optional<CryptoTypes.OutboundSlot> loadSlot(
                UUID messageRequestId, UUID recipientDeviceId) {
            return sessions.loadSlot(messageRequestId, recipientDeviceId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId) {
            return sessions.slotsForMessage(messageRequestId);
        }

        @Override
        public List<CryptoTypes.OutboundSlot> pendingSlots() {
            return sessions.pendingSlots();
        }

        @Override
        public synchronized void commitOutboundCiphertext(
                CryptoTypes.SessionRecord advancedSession, CryptoTypes.OutboundSlot committedSlot) {
            sessions.commitOutboundCiphertext(advancedSession, committedSlot);
        }

        @Override
        public synchronized void commitInboundEstablishment(
                CryptoTypes.SessionRecord inboundSession, Integer consumedOneTimePrekeyIdOrNull) {
            if (inboundSession == null) {
                throw new IllegalArgumentException("inbound commit requires a session");
            }
            if (inboundSession.state() != CryptoTypes.LocalSessionState.READY
                    || inboundSession.sessionBlob() == null) {
                throw new IllegalArgumentException("inbound commit requires a READY session with a blob");
            }
            sessions.saveSession(inboundSession);
            if (consumedOneTimePrekeyIdOrNull != null) {
                keys.forgetOneTimePrivate(consumedOneTimePrekeyIdOrNull);
            }
        }

        @Override
        public void provision(
                SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey) {
            keys.provision(identity, signedPrekey);
        }

        @Override
        public boolean isProvisioned() {
            return keys.isProvisioned();
        }

        @Override
        public UUID ownDeviceId() {
            return keys.ownDeviceId();
        }

        @Override
        public int registrationId() {
            return keys.registrationId();
        }

        @Override
        public byte[] identityPublicKey() {
            return keys.identityPublicKey();
        }

        @Override
        public SignalAdapter.SealedPrivateHandle identityPrivate() {
            return keys.identityPrivate();
        }

        @Override
        public SignalAdapter.SignedPrekeyPair signedPrekey() {
            return keys.signedPrekey();
        }

        @Override
        public void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle) {
            keys.putOneTimePrivate(prekeyId, privateHandle);
        }

        @Override
        public Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId) {
            return keys.oneTimePrivate(prekeyId);
        }

        @Override
        public void forgetOneTimePrivate(int prekeyId) {
            keys.forgetOneTimePrivate(prekeyId);
        }

        @Override
        public CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey) {
            return trust.observe(peerDeviceId, identityPublicKey);
        }

        @Override
        public Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId) {
            return trust.load(peerDeviceId);
        }

        @Override
        public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
            trust.acceptKeyChange(peerDeviceId, newIdentityPublicKey);
        }

        @Override
        public void rejectKeyChange(UUID peerDeviceId) {
            trust.rejectKeyChange(peerDeviceId);
        }

        @Override
        public void markRevoked(UUID peerDeviceId) {
            trust.markRevoked(peerDeviceId);
        }
    }

    public static final class Trust implements TrustStore {
        private final Map<UUID, CryptoTypes.TrustRecord> records = new ConcurrentHashMap<>();

        @Override
        public CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey) {
            java.util.Objects.requireNonNull(peerDeviceId, "peerDeviceId");
            java.util.Objects.requireNonNull(identityPublicKey, "identityPublicKey");
            CryptoTypes.TrustRecord existing = records.get(peerDeviceId);
            if (existing == null) {
                CryptoTypes.TrustRecord fresh = new CryptoTypes.TrustRecord(
                        peerDeviceId,
                        Arrays.copyOf(identityPublicKey, identityPublicKey.length),
                        CryptoTypes.TrustState.TRUSTED);
                records.put(peerDeviceId, fresh);
                return fresh;
            }
            if (existing.state() == CryptoTypes.TrustState.REVOKED_EXPLICIT) {
                return existing;
            }
            if (existing.identityPublicKey() != null
                    && !Arrays.equals(existing.identityPublicKey(), identityPublicKey)) {
                CryptoTypes.TrustRecord paused = new CryptoTypes.TrustRecord(
                        peerDeviceId, existing.identityPublicKey(), CryptoTypes.TrustState.PAUSED_KEY_CHANGED);
                records.put(peerDeviceId, paused);
                return paused;
            }
            return existing;
        }

        @Override
        public Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId) {
            return Optional.ofNullable(records.get(peerDeviceId));
        }

        @Override
        public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
            java.util.Objects.requireNonNull(peerDeviceId, "peerDeviceId");
            java.util.Objects.requireNonNull(newIdentityPublicKey, "newIdentityPublicKey");
            records.put(peerDeviceId, new CryptoTypes.TrustRecord(peerDeviceId,
                    Arrays.copyOf(newIdentityPublicKey, newIdentityPublicKey.length),
                    CryptoTypes.TrustState.TRUSTED));
        }

        @Override
        public void rejectKeyChange(UUID peerDeviceId) {
            CryptoTypes.TrustRecord existing = records.get(peerDeviceId);
            byte[] key = existing == null ? null : existing.identityPublicKey();
            records.put(peerDeviceId,
                    new CryptoTypes.TrustRecord(peerDeviceId, key, CryptoTypes.TrustState.PAUSED_KEY_CHANGED));
        }

        @Override
        public void markRevoked(UUID peerDeviceId) {
            CryptoTypes.TrustRecord existing = records.get(peerDeviceId);
            byte[] key = existing == null ? null : existing.identityPublicKey();
            records.put(peerDeviceId,
                    new CryptoTypes.TrustRecord(peerDeviceId, key, CryptoTypes.TrustState.REVOKED_EXPLICIT));
        }
    }

    public static final class Prekeys implements PrekeyManager {
        private final DeviceKeyStore deviceKeys;
        private final SignalAdapter adapter;
        private int nextId;

        public Prekeys(DeviceKeyStore deviceKeys, SignalAdapter adapter, int firstId) {
            this.deviceKeys = deviceKeys;
            this.adapter = adapter;
            this.nextId = firstId;
        }

        @Override
        public boolean needsReplenishment(long availableCount) {
            return availableCount < REPLENISH_THRESHOLD;
        }

        @Override
        public List<SignalAdapter.OneTimePrekeyPair> nextBatch() {
            List<SignalAdapter.OneTimePrekeyPair> batch = new ArrayList<>(BATCH_SIZE);
            for (int i = 0; i < BATCH_SIZE; i++) {
                SignalAdapter.OneTimePrekeyPair pair = adapter.generateOneTimePrekey(nextId);
                deviceKeys.putOneTimePrivate(nextId, pair.privateHandle());
                batch.add(pair);
                nextId++;
            }
            return List.copyOf(batch);
        }

        @Override
        public int highWaterMark() {
            return nextId - 1;
        }
    }
}
