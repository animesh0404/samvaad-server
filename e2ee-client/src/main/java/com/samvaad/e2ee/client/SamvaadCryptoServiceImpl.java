package com.samvaad.e2ee.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic orchestration of the crash-safe send machine. No real
 * cryptography; all math delegates to {@link SignalAdapter}.
 *
 * <p>Refinement over the first draft: a COMMITTED envelope's bytes are
 * immutable for the slot — submit retries resubmit identical bytes (server
 * requestId idempotent). Re-encryption happens only when no envelope was
 * committed yet, and always on the currently committed session, never by
 * restoring an older blob. This is what prevents ratchet divergence.
 */
public final class SamvaadCryptoServiceImpl implements SamvaadCryptoService {

    private final SignalAdapter adapter;
    private final ClientCryptoStore stores;
    private final ClaimClient claims;
    private final SubmitClient submitter;

    public SamvaadCryptoServiceImpl(
            SignalAdapter adapter,
            ClientCryptoStore stores,
            ClaimClient claims,
            SubmitClient submitter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    @Override
    public FanoutResult sendToDevices(
            UUID messageRequestId,
            UUID senderDeviceId,
            byte[] plaintextAssoc,
            List<CryptoTypes.RecipientBundle> directoryActive,
            Set<UUID> explicitlyRevoked) {
        Objects.requireNonNull(messageRequestId, "messageRequestId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(plaintextAssoc, "plaintextAssoc");
        Objects.requireNonNull(directoryActive, "directoryActive");
        Objects.requireNonNull(explicitlyRevoked, "explicitlyRevoked");
        if (!stores.isProvisioned()) {
            throw new IllegalStateException("own device not provisioned");
        }

        Map<UUID, DeviceOutcome> outcomes = new LinkedHashMap<>();
        List<CryptoTypes.OutboundEnvelope> batch = new ArrayList<>();

        for (CryptoTypes.RecipientBundle listed : directoryActive) {
            UUID peer = listed.deviceId();
            if (explicitlyRevoked.contains(peer)) {
                if (replayIfCommitted(messageRequestId, senderDeviceId, peer, batch)) {
                    outcomes.put(peer, DeviceOutcome.SENT);
                    continue;
                }
                revokePeerDevice(peer);
                persistSlot(new CryptoTypes.OutboundSlot(
                        messageRequestId, senderDeviceId, peer,
                        CryptoTypes.deriveClaimRequestId(messageRequestId, senderDeviceId, peer),
                        CryptoTypes.OutboundSlotState.FAILED_REVOKED, null, null, null, null));
                outcomes.put(peer, DeviceOutcome.SKIPPED_REVOKED);
                continue;
            }
            DeviceOutcome outcome = sendToOne(messageRequestId, senderDeviceId, plaintextAssoc, listed, batch);
            outcomes.put(peer, outcome);
        }
        for (UUID peer : explicitlyRevoked) {
            if (outcomes.containsKey(peer)) {
                continue;
            }
            if (replayIfCommitted(messageRequestId, senderDeviceId, peer, batch)) {
                outcomes.put(peer, DeviceOutcome.SENT);
                continue;
            }
            revokePeerDevice(peer);
            outcomes.put(peer, DeviceOutcome.SKIPPED_REVOKED);
        }

        if (!batch.isEmpty()) {
            try {
                submitter.submit(messageRequestId, List.copyOf(batch));
                for (CryptoTypes.OutboundEnvelope env : batch) {
                    markAcked(messageRequestId, senderDeviceId, env.recipientDeviceId());
                }
            } catch (CryptoException.TransientException e) {
                // COMMITTED envelopes stay as-is; a later retry resubmits the
                // identical bytes. Outcomes below already SENT-candidate become
                // DEFERRED; slots remain COMMITTED (not rolled back).
                for (CryptoTypes.OutboundEnvelope env : batch) {
                    outcomes.put(env.recipientDeviceId(), DeviceOutcome.DEFERRED_TRANSIENT);
                }
            }
        }
        return new FanoutResult(messageRequestId, Map.copyOf(outcomes));
    }

    private DeviceOutcome sendToOne(
            UUID messageRequestId,
            UUID senderDeviceId,
            byte[] plaintextAssoc,
            CryptoTypes.RecipientBundle listed,
            List<CryptoTypes.OutboundEnvelope> batch) {
        UUID peer = listed.deviceId();
        UUID claimRequestId =
                CryptoTypes.deriveClaimRequestId(messageRequestId, senderDeviceId, peer);

        // 0. Committed-slot replay takes precedence over ALL current
        // directory/device/trust state: an already-committed envelope is
        // replayed byte-identically and never re-encrypted, even if the
        // recipient's identity or trust verdict changed after the commit.
        if (replayIfCommitted(messageRequestId, senderDeviceId, peer, batch)) {
            return DeviceOutcome.SENT;
        }
        CryptoTypes.OutboundSlot ackedCheck =
                stores.loadSlot(messageRequestId, peer).orElse(null);
        if (ackedCheck != null && ackedCheck.state() == CryptoTypes.OutboundSlotState.ACKED) {
            return DeviceOutcome.SENT;
        }

        // 1. Trust gate on the listed canonical identity key bytes.
        CryptoTypes.TrustRecord verdict = stores.observe(peer, listed.identityPublicKey());
        if (verdict.state() == CryptoTypes.TrustState.PAUSED_KEY_CHANGED) {
            persistSlot(new CryptoTypes.OutboundSlot(messageRequestId, senderDeviceId, peer,
                    claimRequestId, CryptoTypes.OutboundSlotState.FAILED_PAUSED, null, null, null, null));
            return DeviceOutcome.PAUSED_KEY_CHANGED;
        }
        if (verdict.state() == CryptoTypes.TrustState.REVOKED_EXPLICIT) {
            stores.deleteSession(peer);
            return DeviceOutcome.SKIPPED_REVOKED;
        }
        if (!adapter.verifySignedPrekey(
                listed.identityPublicKey(), listed.signedPrekey(), listed.signedPrekeySignature())) {
            persistSlot(new CryptoTypes.OutboundSlot(messageRequestId, senderDeviceId, peer,
                    claimRequestId, CryptoTypes.OutboundSlotState.FAILED_TRANSIENT, null, null, null, null));
            return DeviceOutcome.DEFERRED_TRANSIENT;
        }

        // 2. Resume or start the slot. Same slot key => same claimRequestId =>
        // same server-consumed OTPK. A changed bundle for an existing slot is
        // rejected by the store; resume reuses the persisted claimed bundle.
        // (COMMITTED/ACKED slots were already handled by the replay gate
        // above and never reach this point.)
        CryptoTypes.OutboundSlot slot = stores.loadSlot(messageRequestId, peer).orElse(null);
        if (slot == null) {
            slot = new CryptoTypes.OutboundSlot(messageRequestId, senderDeviceId, peer,
                    claimRequestId, CryptoTypes.OutboundSlotState.PENDING, null, null, null, null);
            persistSlot(slot);
        }

        // 3. Claim once per slot, and ONLY when no usable session exists.
        // A READY session with matching identity key bytes reuses directly: no claim,
        // no OTPK consumption. Resume replays the SAME claimRequestId.
        CryptoTypes.SessionRecord session = stores.loadSession(peer).orElse(null);
        boolean sessionUsable = session != null
                && session.state() == CryptoTypes.LocalSessionState.READY
                && session.peerIdentityPublicKey() != null
                && Arrays.equals(session.peerIdentityPublicKey(), listed.identityPublicKey());
        CryptoTypes.RecipientBundle bundle =
                slot != null ? slot.claimedBundle() : null;
        if (bundle == null && !sessionUsable) {
            try {
                bundle = claims.claim(peer, claimRequestId);
            } catch (CryptoException.TransientException | CryptoException.ClaimFailedException e) {
                persistSlot(withState(slot, CryptoTypes.OutboundSlotState.FAILED_TRANSIENT));
                return DeviceOutcome.DEFERRED_TRANSIENT;
            }
            // Trust-check the claimed bundle too; a claim race could return a
            // rotated identity. Comparison is on canonical key bytes.
            CryptoTypes.TrustRecord recheck = stores.observe(peer, bundle.identityPublicKey());
            if (recheck.state() == CryptoTypes.TrustState.PAUSED_KEY_CHANGED) {
                persistSlot(withState(slot, CryptoTypes.OutboundSlotState.FAILED_PAUSED));
                return DeviceOutcome.PAUSED_KEY_CHANGED;
            }
            slot = new CryptoTypes.OutboundSlot(messageRequestId, senderDeviceId, peer,
                    claimRequestId, CryptoTypes.OutboundSlotState.CLAIMED, null, null, bundle, null);
            persistSlot(slot);
        } else if (bundle == null) {
            // Session reuse: the listed bundle supplies header fields only;
            // no OTPK is consumed.
            bundle = listed;
        }

        // 4. Establish once per slot; reuse the committed session afterwards.
        // Envelope type rule (frozen): PREKEY_INIT iff this slot establishes
        // the session (first ciphertext ever on it); RATCHET iff a usable
        // session predates this slot. A virgin (0/0 counters) resumed session
        // can only come from this slot's crashed attempt, so it is PREKEY_INIT.
        boolean establishedJustNow = false;
        boolean alreadyCommitted =
                slot.state().ordinal() >= CryptoTypes.OutboundSlotState.SESSION_READY.ordinal();
        if (!sessionUsable || !alreadyCommitted) {
            if (!sessionUsable) {
                try {
                    SignalAdapter.EstablishedSession established =
                            adapter.establishOutbound(stores.identityPrivate(), bundle);
                    CryptoTypes.EstablishmentMode mode = bundle.hasOneTimePrekey()
                            ? CryptoTypes.EstablishmentMode.WITH_ONE_TIME_PREKEY
                            : CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK;
                    stores.saveSession(new CryptoTypes.SessionRecord(peer,
                            bundle.identityPublicKey(), bundle.registrationId(),
                            CryptoTypes.LocalSessionState.READY, mode,
                            established.sessionBlob(), 0, 0));
                    session = stores.loadSession(peer).orElseThrow();
                    establishedJustNow = true;
                } catch (CryptoException.SessionCorruptException | CryptoException.ClaimFailedException e) {
                    // Deterministic establishment failure (corrupt session or
                    // an invalid bundle the real adapter refused): quarantine
                    // this slot's outcome without aborting the fan-out.
                    // Retry re-establishes; it never skips the claim replay.
                    stores.saveSession(new CryptoTypes.SessionRecord(peer,
                            bundle.identityPublicKey(), bundle.registrationId(),
                            CryptoTypes.LocalSessionState.CORRUPT, null, null, 0, 0));
                    return DeviceOutcome.FAILED_CORRUPT;
                }
            }
            CryptoTypes.EnvelopeType envelopeType =
                    resolveEnvelopeType(slot.envelopeType(), establishedJustNow, session);
            slot = new CryptoTypes.OutboundSlot(messageRequestId, senderDeviceId, peer,
                    claimRequestId, CryptoTypes.OutboundSlotState.SESSION_READY,
                    session.establishedVia(), envelopeType, bundle, null);
            persistSlot(slot);
        } else {
            session = stores.loadSession(peer).orElseThrow();
        }

        // 5. Encrypt on the committed session; atomically commit the advanced
        // session blob together with the envelope carrying its ciphertext.
        // The two rows become durable together or not at all: recovery can
        // never observe a durable session-advanced/ciphertext-missing state,
        // so retry never re-encrypts on an advanced ratchet.
        //
        // Wire type: the adapter's report is authoritative when present. A
        // reused session can still yield PREKEY_INIT bytes (the library
        // repeats the prekey message until the peer's first reply), which no
        // Samvaad-side heuristic can classify; null defers to the heuristic.
        final CryptoTypes.RecipientBundle claimed = bundle;
        final CryptoTypes.SessionRecord committed = session;
        try {
            SignalAdapter.EncryptResult encrypted = adapter.encrypt(committed.sessionBlob(), plaintextAssoc);
            CryptoTypes.EnvelopeType wireType = encrypted.envelopeType() != null
                    ? encrypted.envelopeType()
                    : slot.envelopeType();
            CryptoTypes.SessionRecord advanced = new CryptoTypes.SessionRecord(peer,
                    committed.peerIdentityPublicKey(), committed.peerRegistrationId(),
                    CryptoTypes.LocalSessionState.READY, committed.establishedVia(),
                    encrypted.updatedSessionBlob(),
                    committed.encryptCounter() + 1, committed.decryptCounter());
            CryptoTypes.OutboundSlot committedSlot = new CryptoTypes.OutboundSlot(
                    messageRequestId, senderDeviceId, peer, claimRequestId,
                    CryptoTypes.OutboundSlotState.COMMITTED, committed.establishedVia(),
                    wireType, claimed, encrypted.envelopeCiphertext());
            stores.commitOutboundCiphertext(advanced, committedSlot);
            batch.add(toEnvelope(messageRequestId, senderDeviceId, committedSlot));
            return DeviceOutcome.SENT;
        } catch (CryptoException.SessionCorruptException e) {
            stores.saveSession(new CryptoTypes.SessionRecord(peer,
                    committed.peerIdentityPublicKey(), committed.peerRegistrationId(),
                    CryptoTypes.LocalSessionState.CORRUPT, committed.establishedVia(), null, 0, 0));
            return DeviceOutcome.FAILED_CORRUPT;
        }
    }

    @Override
    public byte[] decrypt(
            UUID senderDeviceId, UUID peerDeviceId, CryptoTypes.EnvelopeType kind, byte[] envelopeCiphertext) {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(envelopeCiphertext, "envelopeCiphertext");
        CryptoTypes.SessionRecord session = stores.loadSession(peerDeviceId).orElse(null);
        byte[] current = session == null ? null : session.sessionBlob();
        try {
            SignalAdapter.DecryptResult result;
            if (kind == CryptoTypes.EnvelopeType.PREKEY_INIT) {
                // The adapter parses the referenced OTPK ID from the envelope
                // and resolves exactly that sealed handle from our store; no
                // private key bytes cross this boundary. Resolution is a peek:
                // the reported consumed ID is forgotten exactly once below,
                // after the inbound session is durably committed.
                SignalAdapter.OtpkResolver otpks = stores::requireOneTimePrivate;
                result = adapter.decryptPrekeyInit(
                        stores.identityPrivate(),
                        stores.signedPrekey().privateHandle(),
                        otpks,
                        current,
                        envelopeCiphertext);
            } else if (kind == CryptoTypes.EnvelopeType.RATCHET) {
                if (current == null) {
                    throw new CryptoException.SessionCorruptException("no session for ratchet message");
                }
                result = adapter.decrypt(current, envelopeCiphertext);
            } else {
                throw new CryptoException.ClaimFailedException("unknown envelope type");
            }
            long enc = session == null ? 0 : session.encryptCounter();
            long dec = session == null ? 0 : session.decryptCounter() + 1;
            byte[] peerKey = session == null ? null : session.peerIdentityPublicKey();
            int regId = session == null ? 0 : session.peerRegistrationId();
            CryptoTypes.EstablishmentMode via =
                    session == null ? null : session.establishedVia();
            if (kind == CryptoTypes.EnvelopeType.PREKEY_INIT) {
                // Atomic inbound commit: the READY session and the OTPK
                // consumption become durable together, or neither does. A
                // crash leaves no half-state: retry either re-resolves the
                // same OTPK (commit never happened) or fails closed at
                // resolution time (commit happened) without consuming
                // another OTPK, while the converged session stays usable.
                stores.commitInboundEstablishment(
                        new CryptoTypes.SessionRecord(peerDeviceId, peerKey, regId,
                                CryptoTypes.LocalSessionState.READY, via,
                                result.updatedSessionBlob(), enc, dec),
                        result.consumedOneTimePrekeyIdOrNull());
            } else {
                stores.saveSession(new CryptoTypes.SessionRecord(peerDeviceId, peerKey, regId,
                        CryptoTypes.LocalSessionState.READY, via, result.updatedSessionBlob(), enc, dec));
            }
            return result.plaintextAssoc();
        } catch (CryptoException.SessionCorruptException e) {
            byte[] peerKey = session == null ? null : session.peerIdentityPublicKey();
            int regId = session == null ? 0 : session.peerRegistrationId();
            stores.saveSession(new CryptoTypes.SessionRecord(peerDeviceId, peerKey, regId,
                    CryptoTypes.LocalSessionState.CORRUPT, null, null, 0, 0));
            throw e;
        }
    }

    @Override
    public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(newIdentityPublicKey, "newIdentityPublicKey");
        stores.acceptKeyChange(peerDeviceId, newIdentityPublicKey);
        stores.deleteSession(peerDeviceId);
    }

    @Override
    public void rejectKeyChange(UUID peerDeviceId) {
        stores.rejectKeyChange(peerDeviceId);
    }

    @Override
    public void revokePeerDevice(UUID peerDeviceId) {
        stores.markRevoked(peerDeviceId);
        stores.deleteSession(peerDeviceId);
    }

    private void persistSlot(CryptoTypes.OutboundSlot slot) {
        stores.saveSlot(slot);
    }

    /**
     * Committed-slot replay gate. If the slot for this message already holds
     * committed ciphertext, the exact envelope is queued for resubmission and
     * this returns true — the caller must then skip all directory, device,
     * and trust evaluation for this peer. ACKED slots report success without
     * resubmission. Returns false when no committed result exists.
     */
    private boolean replayIfCommitted(
            UUID messageRequestId,
            UUID senderDeviceId,
            UUID peer,
            List<CryptoTypes.OutboundEnvelope> batch) {
        CryptoTypes.OutboundSlot slot = stores.loadSlot(messageRequestId, peer).orElse(null);
        if (slot == null) {
            return false;
        }
        if (slot.state() == CryptoTypes.OutboundSlotState.ACKED) {
            return true;
        }
        if (slot.envelopeCiphertext() != null
                && (slot.state() == CryptoTypes.OutboundSlotState.COMMITTED
                        || slot.state() == CryptoTypes.OutboundSlotState.SUBMITTED)) {
            batch.add(toEnvelope(messageRequestId, senderDeviceId, slot));
            return true;
        }
        return false;
    }

    private static CryptoTypes.OutboundSlot withState(
            CryptoTypes.OutboundSlot slot, CryptoTypes.OutboundSlotState state) {
        return new CryptoTypes.OutboundSlot(slot.messageRequestId(), slot.senderDeviceId(),
                slot.recipientDeviceId(), slot.claimRequestId(), state,
                slot.establishmentMode(), slot.envelopeType(),
                slot.claimedBundle(), slot.envelopeCiphertext());
    }

    /**
     * Frozen envelope-type rule: a persisted type never changes; a slot that
     * established its session produces PREKEY_INIT; otherwise a virgin
     * (never-used) resumed session also means this slot established it
     * before crashing, so PREKEY_INIT; any previously-used session means
     * RATCHET.
     */
    private static CryptoTypes.EnvelopeType resolveEnvelopeType(
            CryptoTypes.EnvelopeType persisted,
            boolean establishedJustNow,
            CryptoTypes.SessionRecord session) {
        if (persisted != null) {
            return persisted;
        }
        if (establishedJustNow) {
            return CryptoTypes.EnvelopeType.PREKEY_INIT;
        }
        if (session.encryptCounter() == 0 && session.decryptCounter() == 0) {
            return CryptoTypes.EnvelopeType.PREKEY_INIT;
        }
        return CryptoTypes.EnvelopeType.RATCHET;
    }

    private void markAcked(UUID messageRequestId, UUID senderDeviceId, UUID peer) {
        CryptoTypes.OutboundSlot slot = stores.loadSlot(messageRequestId, peer).orElse(null);
        if (slot == null) {
            return;
        }
        persistSlot(withState(slot, CryptoTypes.OutboundSlotState.ACKED));
    }

    private CryptoTypes.OutboundEnvelope toEnvelope(
            UUID messageRequestId, UUID senderDeviceId, CryptoTypes.OutboundSlot slot) {
        CryptoTypes.EnvelopeType envelopeType =
                Objects.requireNonNull(slot.envelopeType(), "slot envelope type required for envelope");
        return new CryptoTypes.OutboundEnvelope(
                CryptoTypes.ENVELOPE_FORMAT_VERSION,
                CryptoTypes.CRYPTO_SUITE,
                envelopeType,
                messageRequestId,
                senderDeviceId,
                slot.recipientDeviceId(),
                slot.envelopeCiphertext());
    }
}
