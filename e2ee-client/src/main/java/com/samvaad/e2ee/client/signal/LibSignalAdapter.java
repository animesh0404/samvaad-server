package com.samvaad.e2ee.client.signal;

import com.samvaad.e2ee.client.CryptoException;
import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.SignalAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyStore;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

/**
 * Production JVM {@link SignalAdapter} backed by libsignal-client 0.86.5.
 *
 * <p>All libsignal types are confined to this class (plus the transient
 * per-call store below). The Samvaad layer sees only opaque blobs, public
 * bytes, and {@link SealedPrivateHandle}s.
 *
 * <p>Private-key custody: generated private objects live in an in-process
 * registry keyed by handle UUID, so store reopenings in the same process
 * keep resolving (the file store persists only the handle reference). When
 * a {@link PrivateKeyVault} is attached, every generation is additionally
 * sealed into encrypted persistent custody, and registry misses are
 * recovered from the vault on demand — so a full process restart restores
 * identity, signed-prekey, OTPK, and last-resort Kyber material, and fresh
 * establishment plus inbound prekey-init keep working. Without a vault, a
 * restart empties the registry and those operations fail closed; persisted
 * sessions still decrypt ratchet messages and committed ciphertext still
 * replays, neither of which needs private keys.
 *
 * <p>Kyber: the selected libsignal line mandates last-resort Kyber material
 * in every bundle (X3DH-only bundles are rejected at construction), so
 * establishment always runs PQXDH using the bundle triple, and inbound
 * uses this device's remembered last-resort Kyber pair. One-time Kyber
 * pools remain deferred.
 */
public final class LibSignalAdapter implements SignalAdapter {

    /** Opaque handle issued by this adapter; the UUID keys the registry. */
    public record SignalHandle(UUID handleId) implements SealedPrivateHandle {
        public SignalHandle {
            Objects.requireNonNull(handleId, "handleId");
        }
    }

    private sealed interface StoredKey permits IdentityEntry, SignedEntry, OtpkEntry, KyberEntry {
    }

    private record IdentityEntry(IdentityKeyPair pair) implements StoredKey {
    }

    private record SignedEntry(int id, long timestamp, ECKeyPair pair, byte[] signature)
            implements StoredKey {
    }

    private record OtpkEntry(int id, ECKeyPair pair) implements StoredKey {
    }

    private record KyberEntry(int id, long timestamp, KEMKeyPair pair, byte[] signature)
            implements StoredKey {
    }

    private final int localRegistrationId;
    private final PrivateKeyVault vault;
    private final Map<UUID, StoredKey> keys = new ConcurrentHashMap<>();
    /**
     * This device's last-resort Kyber pair (inbound needs it; no handle
     * param carries it). Single last-resort invariant: regenerating replaces
     * the vault entry, so recovery stays unambiguous.
     */
    private volatile KyberDevice deviceKyber;

    private record KyberDevice(UUID handleId, KyberEntry entry) {
    }

    /**
     * @param localRegistrationId this device's registration id, stable for
     *                            the device lifetime (same value it uploads)
     */
    public LibSignalAdapter(int localRegistrationId) {
        this(localRegistrationId, null);
    }

    /**
     * @param localRegistrationId this device's registration id, stable for
     *                            the device lifetime (same value it uploads)
     * @param vault persistent private-key custody, or null for in-process
     *              only (restart loses private material)
     */
    public LibSignalAdapter(int localRegistrationId, PrivateKeyVault vault) {
        if (localRegistrationId < 1) {
            throw new IllegalArgumentException("registration id starts at 1");
        }
        this.localRegistrationId = localRegistrationId;
        this.vault = vault;
    }

    // ---- generation ----

    @Override
    public LocalIdentity generateIdentity() {
        IdentityKeyPair pair = IdentityKeyPair.generate();
        UUID id = UUID.randomUUID();
        seal(id, PrivateKeyVault.KeyKind.IDENTITY, pair.serialize());
        keys.put(id, new IdentityEntry(pair));
        return new LocalIdentity(pair.getPublicKey().serialize(), new SignalHandle(id));
    }

    @Override
    public SignedPrekeyPair generateSignedPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
        IdentityKeyPair identity = identityOf(identityPrivate);
        ECKeyPair signed = ECKeyPair.generate();
        byte[] signature =
                identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
        long timestamp = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        seal(id, PrivateKeyVault.KeyKind.SIGNED,
                new SignedPreKeyRecord(prekeyId, timestamp, signed, signature).serialize());
        keys.put(id, new SignedEntry(prekeyId, timestamp, signed, signature.clone()));
        return new SignedPrekeyPair(
                prekeyId, signed.getPublicKey().serialize(), signature.clone(), new SignalHandle(id));
    }

    @Override
    public OneTimePrekeyPair generateOneTimePrekey(int prekeyId) {
        ECKeyPair pair = ECKeyPair.generate();
        UUID id = UUID.randomUUID();
        seal(id, PrivateKeyVault.KeyKind.OTPK, new PreKeyRecord(prekeyId, pair).serialize());
        keys.put(id, new OtpkEntry(prekeyId, pair));
        return new OneTimePrekeyPair(
                prekeyId, pair.getPublicKey().serialize(), new SignalHandle(id));
    }

    @Override
    public KyberPrekeyPair generateKyberPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
        IdentityKeyPair identity = identityOf(identityPrivate);
        KEMKeyPair pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] signature =
                identity.getPrivateKey().calculateSignature(pair.getPublicKey().serialize());
        long timestamp = System.currentTimeMillis();
        UUID id = UUID.randomUUID();
        seal(id, PrivateKeyVault.KeyKind.KYBER,
                new KyberPreKeyRecord(prekeyId, timestamp, pair, signature).serialize());
        KyberEntry entry = new KyberEntry(prekeyId, timestamp, pair, signature.clone());
        keys.put(id, entry);
        KyberDevice previous = deviceKyber;
        deviceKyber = new KyberDevice(id, entry);
        if (vault != null && previous != null && !previous.handleId().equals(id)) {
            // Single last-resort invariant: retire the superseded entry so
            // recovery stays unambiguous.
            vault.remove(previous.handleId());
        }
        return new KyberPrekeyPair(
                prekeyId, pair.getPublicKey().serialize(), signature.clone(), new SignalHandle(id));
    }

    // ---- display / verify ----

    @Override
    public String fingerprint(byte[] identityPublicKey) {
        IdentityFingerprints.requireCanonicalKey(identityPublicKey);
        return java.util.HexFormat.of().formatHex(sha256(identityPublicKey));
    }

    @Override
    public boolean verifySignedPrekey(byte[] identityPublicKey, byte[] signedPrekey, byte[] signature) {
        try {
            IdentityKey identity = parseIdentity(identityPublicKey);
            ECPublicKey signed = parseEcPublic(signedPrekey, "signed prekey");
            if (signature == null || signature.length == 0) {
                return false;
            }
            return identity.getPublicKey().verifySignature(signed.serialize(), signature);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- outbound ----

    @Override
    public EstablishedSession establishOutbound(
            SealedPrivateHandle ownIdentityPrivate, CryptoTypes.RecipientBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        IdentityKeyPair identity = identityOf(ownIdentityPrivate);
        IdentityKey remoteIdentity = parseIdentity(bundle.identityPublicKey());
        ECPublicKey signedPub = parseEcPublic(bundle.signedPrekey(), "signed prekey");
        requireSignature(
                remoteIdentity, signedPub, bundle.signedPrekeySignature(), "signed prekey");
        if (!bundle.hasKyber()) {
            throw new CryptoException.ClaimFailedException("bundle lacks kyber");
        }
        KEMPublicKey kyberPub = parseKyberPublic(bundle.kyberPrekey());
        requireSignature(
                remoteIdentity, bundle.kyberPrekey(), bundle.kyberPrekeySignature(), "kyber prekey");
        ECPublicKey otpPub = null;
        int otpId = PreKeyBundle.NULL_PRE_KEY_ID;
        if (bundle.hasOneTimePrekey()) {
            otpPub = parseEcPublic(bundle.oneTimePrekey(), "one-time prekey");
            otpId = bundle.oneTimePrekeyId();
        }
        SignalProtocolAddress remote =
                new SignalProtocolAddress(bundle.userId().toString(), bundle.signalDeviceId());
        TransientStore store = new TransientStore(identity, localRegistrationId);
        try {
            PreKeyBundle preKeyBundle = new PreKeyBundle(
                    bundle.registrationId(),
                    bundle.signalDeviceId(),
                    otpId,
                    otpPub,
                    bundle.signedPrekeyId(),
                    signedPub,
                    bundle.signedPrekeySignature(),
                    remoteIdentity,
                    bundle.kyberPrekeyId(),
                    kyberPub,
                    bundle.kyberPrekeySignature());
            try {
                new SessionBuilder(store, remote).process(preKeyBundle);
            } catch (InvalidKeyException e) {
                throw new CryptoException.ClaimFailedException(
                        "bundle rejected by session establishment: " + e.getMessage());
            } catch (UntrustedIdentityException e) {
                throw new CryptoException.SessionCorruptException(
                        "untrusted identity during establishment");
            }
            byte[] blob = store.loadSession(remote).serialize();
            CryptoTypes.EstablishmentMode mode = bundle.hasOneTimePrekey()
                    ? CryptoTypes.EstablishmentMode.WITH_ONE_TIME_PREKEY
                    : CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK;
            return new EstablishedSession(blob, mode);
        } catch (CryptoException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CryptoException.SessionCorruptException(
                    "establishment failed: " + e.getMessage());
        }
    }

    @Override
    public EncryptResult encrypt(byte[] sessionBlob, byte[] plaintextAssoc) {
        Objects.requireNonNull(sessionBlob, "sessionBlob");
        Objects.requireNonNull(plaintextAssoc, "plaintextAssoc");
        if (sessionBlob.length == 0) {
            throw new CryptoException.SessionCorruptException("empty session blob");
        }
        TransientStore store = new TransientStore(null, localRegistrationId);
        SessionRecord session = parseSession(sessionBlob);
        SignalProtocolAddress peer = transientAddress();
        store.storeSession(peer, session);
        try {
            CiphertextMessage message = new SessionCipher(store, peer).encrypt(plaintextAssoc);
            try {
                byte[] wire = message.serialize();
                byte[] updated = store.loadSession(peer).serialize();
                CryptoTypes.EnvelopeType type = switch (message.getType()) {
                    case CiphertextMessage.PREKEY_TYPE -> CryptoTypes.EnvelopeType.PREKEY_INIT;
                    case CiphertextMessage.WHISPER_TYPE -> CryptoTypes.EnvelopeType.RATCHET;
                    default -> throw new CryptoException.SessionCorruptException(
                            "unexpected message type " + message.getType());
                };
                return new EncryptResult(updated, wire, type);
            } finally {
                closeQuietly(message);
            }
        } catch (NoSessionException | UntrustedIdentityException e) {
            throw new CryptoException.SessionCorruptException("encrypt failed: " + e.getMessage());
        }
    }

    // ---- inbound ----

    @Override
    public DecryptResult decryptPrekeyInit(
            SealedPrivateHandle ownIdentityPrivate,
            SealedPrivateHandle ownSignedPrivate,
            OtpkResolver otpks,
            byte[] currentSessionBlobOrNull,
            byte[] envelopeCiphertext) {
        Objects.requireNonNull(ownIdentityPrivate, "ownIdentityPrivate");
        Objects.requireNonNull(ownSignedPrivate, "ownSignedPrivate");
        Objects.requireNonNull(otpks, "otpks");
        Objects.requireNonNull(envelopeCiphertext, "envelopeCiphertext");
        PreKeySignalMessage message = parsePreKeyMessage(envelopeCiphertext);
        try {
            // The exact referenced OTPK ID comes from the parsed message;
            // resolution is a peek owned by the Samvaad side.
            Optional<Integer> referenced = message.getPreKeyId();
            ECKeyPair otpPair = null;
            if (referenced.isPresent()) {
                SealedPrivateHandle otpHandle = otpks.resolve(referenced.get());
                otpPair = otpkOf(otpHandle);
            }
            IdentityKeyPair identity = identityOf(ownIdentityPrivate);
            SignedEntry signed = signedOf(ownSignedPrivate);
            KyberEntry kyber = deviceKyber == null ? null : deviceKyber.entry();
            if (kyber == null) {
                kyber = recoverDeviceKyber();
            }
            if (kyber == null) {
                throw new CryptoException.SessionCorruptException(
                        "device kyber key unavailable in this process");
            }
            TransientStore store = new TransientStore(identity, localRegistrationId);
            store.storeSignedPreKey(signed.id(),
                    new SignedPreKeyRecord(signed.id(), signed.timestamp(), signed.pair(),
                            signed.signature()));
            if (otpPair != null) {
                int otpId = referenced.orElseThrow();
                store.storePreKey(otpId, new PreKeyRecord(otpId, otpPair));
            }
            store.storeKyberPreKey(kyber.id(),
                    new KyberPreKeyRecord(kyber.id(), kyber.timestamp(), kyber.pair(),
                            kyber.signature()));
            SignalProtocolAddress peer = transientAddress();
            if (currentSessionBlobOrNull != null) {
                store.storeSession(peer, parseSession(currentSessionBlobOrNull));
            }
            try {
                byte[] plain = new SessionCipher(store, peer).decrypt(message);
                byte[] updated = store.loadSession(peer).serialize();
                return new DecryptResult(updated, plain, referenced.orElse(null));
            } catch (DuplicateMessageException | InvalidKeyIdException e) {
                // Duplicate or consumed-OTPK replay: deterministic rejection,
                // session untouched, never another OTPK.
                throw new CryptoException.ClaimFailedException(
                        "duplicate or consumed prekey init: " + e.getMessage());
            } catch (InvalidMessageException | InvalidKeyException
                    | UntrustedIdentityException e) {
                throw new CryptoException.SessionCorruptException(
                        "prekey init undecryptable: " + e.getMessage());
            }
        } finally {
            closeQuietly(message);
        }
    }

    @Override
    public DecryptResult decrypt(byte[] sessionBlob, byte[] envelopeCiphertext) {
        Objects.requireNonNull(sessionBlob, "sessionBlob");
        Objects.requireNonNull(envelopeCiphertext, "envelopeCiphertext");
        if (sessionBlob.length == 0) {
            throw new CryptoException.SessionCorruptException("empty session blob");
        }
        SignalMessage message;
        try {
            message = new SignalMessage(envelopeCiphertext);
        } catch (InvalidMessageException | InvalidVersionException
                | InvalidKeyException | LegacyMessageException e) {
            throw new CryptoException.SessionCorruptException("not a ratchet message");
        }
        try {
            TransientStore store = new TransientStore(null, localRegistrationId);
            SignalProtocolAddress peer = transientAddress();
            store.storeSession(peer, parseSession(sessionBlob));
            try {
                byte[] plain = new SessionCipher(store, peer).decrypt(message);
                byte[] updated = store.loadSession(peer).serialize();
                return new DecryptResult(updated, plain, null);
            } catch (InvalidMessageException | InvalidVersionException
                    | DuplicateMessageException | NoSessionException
                    | UntrustedIdentityException e) {
                throw new CryptoException.SessionCorruptException(
                        "ratchet message undecryptable: " + e.getMessage());
            }
        } finally {
            closeQuietly(message);
        }
    }

    // ---- registry ----

    /**
     * Seals freshly generated material into persistent custody. Consumes
     * (zeroes) the supplied plaintext array. A vault failure fails the
     * generation itself: no handle for unsealed material is ever returned.
     */
    private void seal(UUID handleId, PrivateKeyVault.KeyKind kind, byte[] plaintext) {
        if (vault == null) {
            return;
        }
        try {
            vault.store(handleId, kind, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Recovers one entry from persistent custody into the registry. Returns
     * null when no vault is attached or the handle is unknown there; fails
     * closed on tampering or kind mismatch.
     */
    private StoredKey recoverFromVault(
            UUID handleId, PrivateKeyVault.KeyKind expected, String role) {
        if (vault == null) {
            return null;
        }
        PrivateKeyVault.SealedEntry sealed = vault.load(handleId);
        if (sealed == null) {
            return null;
        }
        byte[] plaintext = sealed.plaintext();
        try {
            if (sealed.kind() != expected) {
                throw new IllegalArgumentException(
                        "handle is not " + role + " (vault kind mismatch)");
            }
            StoredKey recovered = switch (expected) {
                case IDENTITY -> new IdentityEntry(new IdentityKeyPair(plaintext));
                case SIGNED -> {
                    SignedPreKeyRecord record = new SignedPreKeyRecord(plaintext);
                    yield new SignedEntry(
                            record.getId(), record.getTimestamp(), record.getKeyPair(),
                            record.getSignature());
                }
                case OTPK -> {
                    PreKeyRecord record = new PreKeyRecord(plaintext);
                    yield new OtpkEntry(record.getId(), record.getKeyPair());
                }
                case KYBER -> {
                    KyberPreKeyRecord record = new KyberPreKeyRecord(plaintext);
                    yield new KyberEntry(
                            record.getId(), record.getTimestamp(), record.getKeyPair(),
                            record.getSignature());
                }
            };
            keys.put(handleId, recovered);
            return recovered;
        } catch (InvalidKeyException | InvalidMessageException e) {
            throw new CryptoException.SessionCorruptException(
                    "vault entry undecryptable as " + role);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Recovers the lone last-resort Kyber entry; ambiguous/absent stays null. */
    private KyberEntry recoverDeviceKyber() {
        if (vault == null) {
            return null;
        }
        Set<UUID> candidates = vault.handlesOfKind(PrivateKeyVault.KeyKind.KYBER);
        if (candidates.size() != 1) {
            return null;
        }
        UUID handleId = candidates.iterator().next();
        StoredKey recovered = recoverFromVault(handleId, PrivateKeyVault.KeyKind.KYBER, "a kyber key");
        if (recovered instanceof KyberEntry entry) {
            deviceKyber = new KyberDevice(handleId, entry);
            return entry;
        }
        return null;
    }

    private IdentityKeyPair identityOf(SealedPrivateHandle handle) {
        Objects.requireNonNull(handle, "identity handle");
        StoredKey key = keys.get(handle.handleId());
        if (key == null) {
            key = recoverFromVault(
                    handle.handleId(), PrivateKeyVault.KeyKind.IDENTITY, "an identity key");
        }
        if (key == null) {
            throw new CryptoException.SessionCorruptException(
                    "identity key unavailable in this process; re-provision platform key material");
        }
        if (!(key instanceof IdentityEntry entry)) {
            throw new IllegalArgumentException("handle is not an identity key");
        }
        return entry.pair();
    }

    private SignedEntry signedOf(SealedPrivateHandle handle) {
        Objects.requireNonNull(handle, "signed prekey handle");
        StoredKey key = keys.get(handle.handleId());
        if (key == null) {
            key = recoverFromVault(
                    handle.handleId(), PrivateKeyVault.KeyKind.SIGNED, "a signed prekey");
        }
        if (key == null) {
            throw new CryptoException.SessionCorruptException(
                    "signed prekey unavailable in this process");
        }
        if (!(key instanceof SignedEntry entry)) {
            throw new IllegalArgumentException("handle is not a signed prekey");
        }
        return entry;
    }

    private ECKeyPair otpkOf(SealedPrivateHandle handle) {
        Objects.requireNonNull(handle, "one-time prekey handle");
        StoredKey key = keys.get(handle.handleId());
        if (key == null) {
            key = recoverFromVault(
                    handle.handleId(), PrivateKeyVault.KeyKind.OTPK, "a one-time prekey");
        }
        if (key == null) {
            throw new CryptoException.SessionCorruptException(
                    "one-time prekey unavailable in this process");
        }
        if (!(key instanceof OtpkEntry entry)) {
            throw new IllegalArgumentException("handle is not a one-time prekey");
        }
        return entry.pair();
    }

    // ---- parsing / validation ----

    private static IdentityKey parseIdentity(byte[] bytes) {
        IdentityFingerprints.requireCanonicalKey(bytes);
        try {
            return new IdentityKey(bytes);
        } catch (InvalidKeyException e) {
            throw new CryptoException.ClaimFailedException("malformed identity key");
        }
    }

    private static ECPublicKey parseEcPublic(byte[] bytes, String what) {
        if (bytes == null || bytes.length == 0) {
            throw new CryptoException.ClaimFailedException("missing " + what);
        }
        try {
            return new ECPublicKey(bytes);
        } catch (InvalidKeyException e) {
            throw new CryptoException.ClaimFailedException("malformed " + what);
        }
    }

    private static KEMPublicKey parseKyberPublic(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CryptoException.ClaimFailedException("missing kyber prekey");
        }
        try {
            return new KEMPublicKey(bytes);
        } catch (InvalidKeyException e) {
            throw new CryptoException.ClaimFailedException("malformed kyber prekey");
        }
    }

    private static void requireSignature(
            IdentityKey identity, ECPublicKey signed, byte[] signature, String what) {
        if (signature == null || signature.length == 0) {
            throw new CryptoException.ClaimFailedException("missing " + what + " signature");
        }
        if (!identity.getPublicKey().verifySignature(signed.serialize(), signature)) {
            throw new CryptoException.ClaimFailedException("invalid " + what + " signature");
        }
    }

    private static void requireSignature(
            IdentityKey identity, byte[] kyberPublic, byte[] signature, String what) {
        if (kyberPublic == null || signature == null || signature.length == 0) {
            throw new CryptoException.ClaimFailedException("missing " + what + " material");
        }
        if (!identity.getPublicKey().verifySignature(kyberPublic, signature)) {
            throw new CryptoException.ClaimFailedException("invalid " + what + " signature");
        }
    }

    private static SessionRecord parseSession(byte[] blob) {
        try {
            return new SessionRecord(blob);
        } catch (InvalidMessageException e) {
            throw new CryptoException.SessionCorruptException("unreadable session blob");
        }
    }

    private static PreKeySignalMessage parsePreKeyMessage(byte[] wire) {
        try {
            return new PreKeySignalMessage(wire);
        } catch (InvalidMessageException | InvalidVersionException
                | LegacyMessageException | InvalidKeyException e) {
            throw new CryptoException.SessionCorruptException("not a prekey init message");
        }
    }

    private static SignalProtocolAddress transientAddress() {
        // Transient routing key only: each call owns a single-session store,
        // so the address never leaves this adapter and never hits the wire.
        // Samvaad routing stays on UUIDs + the frozen (userId, signalDeviceId).
        return new SignalProtocolAddress("samvaad-transient", 1);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void closeQuietly(Object closeable) {
        if (closeable instanceof AutoCloseable auto) {
            try {
                auto.close();
            } catch (Exception ignored) {
                // Best-effort native release; GC cleaners are the backstop.
            }
        }
    }

    /**
     * Transient single-session libsignal store for exactly one adapter call.
     * Trust verdicts stay Samvaad-owned (always trusted here); OTPK removal
     * is ephemeral (durable consumption is the Samvaad commit boundary).
     */
    private static final class TransientStore implements SignalProtocolStore {
        private final IdentityKeyPair localIdentity;
        private final int localRegistrationId;
        private final Map<String, SessionRecord> sessions = new java.util.HashMap<>();
        private final Map<Integer, PreKeyRecord> preKeys = new java.util.HashMap<>();
        private final Map<Integer, SignedPreKeyRecord> signedPreKeys = new java.util.HashMap<>();
        private final Map<Integer, KyberPreKeyRecord> kyberPreKeys = new java.util.HashMap<>();

        TransientStore(IdentityKeyPair localIdentity, int localRegistrationId) {
            this.localIdentity = localIdentity;
            this.localRegistrationId = localRegistrationId;
        }

        @Override
        public IdentityKeyPair getIdentityKeyPair() {
            if (localIdentity == null) {
                throw new CryptoException.SessionCorruptException(
                        "local identity unavailable for this operation");
            }
            return localIdentity;
        }

        @Override
        public int getLocalRegistrationId() {
            return localRegistrationId;
        }

        @Override
        public IdentityKeyStore.IdentityChange saveIdentity(
                SignalProtocolAddress address, IdentityKey identityKey) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED;
        }

        @Override
        public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey,
                IdentityKeyStore.Direction direction) {
            return true;
        }

        @Override
        public IdentityKey getIdentity(SignalProtocolAddress address) {
            return null;
        }

        @Override
        public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
            PreKeyRecord record = preKeys.get(preKeyId);
            if (record == null) {
                throw new InvalidKeyIdException("no one-time prekey " + preKeyId);
            }
            return record;
        }

        @Override
        public void storePreKey(int preKeyId, PreKeyRecord record) {
            preKeys.put(preKeyId, record);
        }

        @Override
        public boolean containsPreKey(int preKeyId) {
            return preKeys.containsKey(preKeyId);
        }

        @Override
        public void removePreKey(int preKeyId) {
            preKeys.remove(preKeyId);
        }

        @Override
        public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
            SignedPreKeyRecord record = signedPreKeys.get(signedPreKeyId);
            if (record == null) {
                throw new InvalidKeyIdException("no signed prekey " + signedPreKeyId);
            }
            return record;
        }

        @Override
        public List<SignedPreKeyRecord> loadSignedPreKeys() {
            return List.copyOf(signedPreKeys.values());
        }

        @Override
        public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
            signedPreKeys.put(signedPreKeyId, record);
        }

        @Override
        public boolean containsSignedPreKey(int signedPreKeyId) {
            return signedPreKeys.containsKey(signedPreKeyId);
        }

        @Override
        public void removeSignedPreKey(int signedPreKeyId) {
            signedPreKeys.remove(signedPreKeyId);
        }

        @Override
        public KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
            KyberPreKeyRecord record = kyberPreKeys.get(kyberPreKeyId);
            if (record == null) {
                throw new InvalidKeyIdException("no kyber prekey " + kyberPreKeyId);
            }
            return record;
        }

        @Override
        public List<KyberPreKeyRecord> loadKyberPreKeys() {
            return List.copyOf(kyberPreKeys.values());
        }

        @Override
        public void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
            kyberPreKeys.put(kyberPreKeyId, record);
        }

        @Override
        public boolean containsKyberPreKey(int kyberPreKeyId) {
            return kyberPreKeys.containsKey(kyberPreKeyId);
        }

        @Override
        public void markKyberPreKeyUsed(int kyberPreKeyId, int signedPreKeyId, ECPublicKey baseKey) {
            // Last-resort Kyber keys are reusable by design; one-time Kyber
            // pools are deferred, so there is nothing to retire here.
        }

        @Override
        public SessionRecord loadSession(SignalProtocolAddress address) {
            return sessions.getOrDefault(address.toString(), new SessionRecord());
        }

        @Override
        public List<SessionRecord> loadExistingSessions(
                List<SignalProtocolAddress> addresses) {
            return List.of();
        }

        @Override
        public List<Integer> getSubDeviceSessions(String name) {
            return List.of();
        }

        @Override
        public void storeSession(SignalProtocolAddress address, SessionRecord record) {
            sessions.put(address.toString(), record);
        }

        @Override
        public boolean containsSession(SignalProtocolAddress address) {
            return sessions.containsKey(address.toString());
        }

        @Override
        public void deleteSession(SignalProtocolAddress address) {
            sessions.remove(address.toString());
        }

        @Override
        public void deleteAllSessions(String name) {
            sessions.keySet().removeIf(key -> key.startsWith(name));
        }

        @Override
        public void storeSenderKey(SignalProtocolAddress address, UUID distributionId,
                SenderKeyRecord record) {
            // Group sender keys are out of scope for V1 one-to-one.
        }

        @Override
        public SenderKeyRecord loadSenderKey(SignalProtocolAddress address, UUID distributionId) {
            throw new UnsupportedOperationException("no group sender keys in V1 one-to-one");
        }
    }

}
