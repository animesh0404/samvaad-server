package com.samvaad.samvaad_server.e2ee.client.persist;

import com.samvaad.samvaad_server.e2ee.client.ClientCryptoStore;
import com.samvaad.samvaad_server.e2ee.client.CryptoTypes;
import com.samvaad.samvaad_server.e2ee.client.PersistentStoreException;
import com.samvaad.samvaad_server.e2ee.client.SignalAdapter;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Reference JVM {@link ClientCryptoStore} with real crash-safe durability.
 *
 * <p>All state lives in one versioned JSON snapshot ({@code
 * client-crypto-store-v1.json}) inside a single-process-owned directory.
 * Every mutation rewrites the snapshot through temp-file + fsync + atomic
 * rename, so a crash at any instant leaves either the complete previous
 * snapshot or the complete new one — never a half-written store. In
 * particular the two mandatory atomic boundaries ({@link
 * #commitOutboundCiphertext} and {@link #commitInboundEstablishment}) each
 * perform exactly one snapshot write.
 *
 * <p>Private-key protection: sealed private material is persisted
 * exclusively as opaque handle references (stable {@code handleId} UUIDs),
 * reconstituted on load as {@link PersistentHandle}. No key bytes are ever
 * written. The platform keystore owns the material behind those references;
 * on this reference backend the handles are meaningful only to the adapter
 * that issued them — cross-restart handle <em>values</em> are never
 * interpreted, only their identity and lifecycle.
 *
 * <p>Schema versioning: the snapshot carries {@link
 * CryptoTypes#STORE_FORMAT_VERSION}. Open rejects any other version with
 * {@link PersistentStoreException} and never overwrites or migrates it:
 * upgrades require an explicit migration path (and ADR), never silent
 * rewriting.
 *
 * <p>Scope notes: single-process ownership (no cross-process locking);
 * write-through on every mutation (production SQLite/Room/IndexedDB
 * backends may batch internally but must preserve the same two atomic
 * boundaries); OTPK issuance high-water allocation stays with {@code
 * PrekeyManager}/the platform and is not tracked here. The snapshot codec
 * ({@link SnapshotJson}) is JDK-only on purpose, so this boundary never
 * tracks a JSON library's version.
 */
public final class FileBackedClientCryptoStore implements ClientCryptoStore {

    /** Crash-simulation seam: invoked inside the atomic persist step. */
    public interface CommitFault {
        void beforeCommit();
    }

    /** Opaque reconstitution of a persisted sealed-handle reference. */
    public record PersistentHandle(UUID handleId) implements SignalAdapter.SealedPrivateHandle {
        public PersistentHandle {
            Objects.requireNonNull(handleId, "handleId");
        }
    }

    private static final String FILE_NAME = "client-crypto-store-v1.json";

    private final Path dir;
    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile CommitFault fault;

    private UUID ownDeviceId;
    private int registrationId;
    private DeviceState device;
    private final Map<Integer, UUID> otpkHandles = new LinkedHashMap<>();
    private final Map<UUID, CryptoTypes.SessionRecord> sessions = new LinkedHashMap<>();
    private final Map<UUID, CryptoTypes.TrustRecord> trust = new LinkedHashMap<>();
    private final Map<String, CryptoTypes.OutboundSlot> slots = new LinkedHashMap<>();

    private record DeviceState(
            byte[] identityPublicKey,
            UUID identityHandle,
            int signedPrekeyId,
            byte[] signedPrekeyPublic,
            UUID signedHandle) {
    }

    private FileBackedClientCryptoStore(Path dir, UUID ownDeviceId, int registrationId, CommitFault fault) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.fault = fault == null ? () -> { } : fault;
        loadOrInit(ownDeviceId, registrationId);
    }

    /** Opens (or creates) the store directory for the given own device. */
    public static FileBackedClientCryptoStore open(Path dir, UUID ownDeviceId, int registrationId) {
        return new FileBackedClientCryptoStore(dir, ownDeviceId, registrationId, null);
    }

    /** Opens with a crash-simulation fault (verification only). */
    public static FileBackedClientCryptoStore open(
            Path dir, UUID ownDeviceId, int registrationId, CommitFault fault) {
        return new FileBackedClientCryptoStore(dir, ownDeviceId, registrationId, fault);
    }

    /** Replaces the crash-simulation fault (verification only). */
    public void setCommitFaultForTesting(CommitFault fault) {
        this.fault = fault == null ? () -> { } : fault;
    }

    /** Store directory (lets tests inspect the raw snapshot file). */
    public Path directory() {
        return dir;
    }

    // ---- DeviceKeyStore ----

    @Override
    public void provision(
            SignalAdapter.LocalIdentity identity, SignalAdapter.SignedPrekeyPair signedPrekey) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(signedPrekey, "signedPrekey");
        lock.writeLock().lock();
        try {
            device = new DeviceState(
                    copy(identity.identityPublicKey()),
                    identity.identityPrivate().handleId(),
                    signedPrekey.prekeyId(),
                    copy(signedPrekey.publicKey()),
                    signedPrekey.privateHandle().handleId());
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isProvisioned() {
        lock.readLock().lock();
        try {
            return device != null;
        } finally {
            lock.readLock().unlock();
        }
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
        lock.readLock().lock();
        try {
            requireProvisioned();
            return copy(device.identityPublicKey());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public SignalAdapter.SealedPrivateHandle identityPrivate() {
        lock.readLock().lock();
        try {
            requireProvisioned();
            return new PersistentHandle(device.identityHandle());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public SignalAdapter.SignedPrekeyPair signedPrekey() {
        lock.readLock().lock();
        try {
            requireProvisioned();
            return new SignalAdapter.SignedPrekeyPair(
                    device.signedPrekeyId(),
                    copy(device.signedPrekeyPublic()),
                    new PersistentHandle(device.signedHandle()));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void putOneTimePrivate(int prekeyId, SignalAdapter.SealedPrivateHandle privateHandle) {
        Objects.requireNonNull(privateHandle, "privateHandle");
        lock.writeLock().lock();
        try {
            otpkHandles.put(prekeyId, privateHandle.handleId());
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<SignalAdapter.SealedPrivateHandle> oneTimePrivate(int prekeyId) {
        lock.readLock().lock();
        try {
            UUID handle = otpkHandles.get(prekeyId);
            return handle == null ? Optional.empty() : Optional.of(new PersistentHandle(handle));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void forgetOneTimePrivate(int prekeyId) {
        lock.writeLock().lock();
        try {
            if (otpkHandles.remove(prekeyId) != null) {
                persistSnapshot();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- SessionStore ----

    @Override
    public void saveSession(CryptoTypes.SessionRecord record) {
        Objects.requireNonNull(record, "record");
        lock.writeLock().lock();
        try {
            sessions.put(record.peerDeviceId(), record);
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CryptoTypes.SessionRecord> loadSession(UUID peerDeviceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(sessions.get(peerDeviceId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteSession(UUID peerDeviceId) {
        lock.writeLock().lock();
        try {
            if (sessions.remove(peerDeviceId) != null) {
                persistSnapshot();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CryptoTypes.SessionRecord> allSessions() {
        lock.readLock().lock();
        try {
            return List.copyOf(sessions.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveSlot(CryptoTypes.OutboundSlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (slot.state() == CryptoTypes.OutboundSlotState.COMMITTED) {
            throw new IllegalStateException(
                    "COMMITTED slots must be written via commitOutboundCiphertext");
        }
        lock.writeLock().lock();
        try {
            CryptoTypes.OutboundSlot existing = slots.get(slotKey(slot));
            checkBundleContinuity(existing, slot);
            slots.put(slotKey(slot), slot);
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CryptoTypes.OutboundSlot> loadSlot(UUID messageRequestId, UUID recipientDeviceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(slots.get(slotKey(messageRequestId, recipientDeviceId)));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CryptoTypes.OutboundSlot> slotsForMessage(UUID messageRequestId) {
        lock.readLock().lock();
        try {
            List<CryptoTypes.OutboundSlot> out = new ArrayList<>();
            for (CryptoTypes.OutboundSlot slot : slots.values()) {
                if (slot.messageRequestId().equals(messageRequestId)) {
                    out.add(slot);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CryptoTypes.OutboundSlot> pendingSlots() {
        lock.readLock().lock();
        try {
            List<CryptoTypes.OutboundSlot> out = new ArrayList<>();
            for (CryptoTypes.OutboundSlot slot : slots.values()) {
                if (slot.state() != CryptoTypes.OutboundSlotState.ACKED) {
                    out.add(slot);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void commitOutboundCiphertext(
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
        lock.writeLock().lock();
        try {
            CryptoTypes.SessionRecord previous = sessions.get(advancedSession.peerDeviceId());
            if (previous == null
                    || advancedSession.encryptCounter() != previous.encryptCounter() + 1
                    || advancedSession.decryptCounter() != previous.decryptCounter()
                    || !Arrays.equals(
                            advancedSession.peerIdentityPublicKey(), previous.peerIdentityPublicKey())) {
                throw new IllegalStateException(
                        "atomic commit must advance exactly the previously committed session");
            }
            checkBundleContinuity(
                    slots.get(slotKey(committedSlot.messageRequestId(), committedSlot.recipientDeviceId())),
                    committedSlot);
            sessions.put(advancedSession.peerDeviceId(), advancedSession);
            slots.put(
                    slotKey(committedSlot.messageRequestId(), committedSlot.recipientDeviceId()),
                    committedSlot);
            // Single snapshot write: both rows durable together, or neither.
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void commitInboundEstablishment(
            CryptoTypes.SessionRecord inboundSession, Integer consumedOneTimePrekeyIdOrNull) {
        if (inboundSession == null) {
            throw new IllegalArgumentException("inbound commit requires a session");
        }
        if (inboundSession.state() != CryptoTypes.LocalSessionState.READY
                || inboundSession.sessionBlob() == null) {
            throw new IllegalArgumentException("inbound commit requires a READY session with a blob");
        }
        lock.writeLock().lock();
        try {
            sessions.put(inboundSession.peerDeviceId(), inboundSession);
            if (consumedOneTimePrekeyIdOrNull != null) {
                otpkHandles.remove(consumedOneTimePrekeyIdOrNull);
            }
            // Single snapshot write: session and OTPK consumption durable together.
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- TrustStore ----

    @Override
    public CryptoTypes.TrustRecord observe(UUID peerDeviceId, byte[] identityPublicKey) {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(identityPublicKey, "identityPublicKey");
        lock.writeLock().lock();
        try {
            CryptoTypes.TrustRecord existing = trust.get(peerDeviceId);
            if (existing == null) {
                CryptoTypes.TrustRecord fresh = new CryptoTypes.TrustRecord(
                        peerDeviceId, copy(identityPublicKey), CryptoTypes.TrustState.TRUSTED);
                trust.put(peerDeviceId, fresh);
                persistSnapshot();
                return fresh;
            }
            if (existing.state() == CryptoTypes.TrustState.REVOKED_EXPLICIT) {
                return existing;
            }
            if (existing.identityPublicKey() != null
                    && !Arrays.equals(existing.identityPublicKey(), identityPublicKey)) {
                CryptoTypes.TrustRecord paused = new CryptoTypes.TrustRecord(
                        peerDeviceId, existing.identityPublicKey(),
                        CryptoTypes.TrustState.PAUSED_KEY_CHANGED);
                trust.put(peerDeviceId, paused);
                persistSnapshot();
                return paused;
            }
            return existing;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CryptoTypes.TrustRecord> load(UUID peerDeviceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(trust.get(peerDeviceId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void acceptKeyChange(UUID peerDeviceId, byte[] newIdentityPublicKey) {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(newIdentityPublicKey, "newIdentityPublicKey");
        lock.writeLock().lock();
        try {
            trust.put(peerDeviceId, new CryptoTypes.TrustRecord(
                    peerDeviceId, copy(newIdentityPublicKey), CryptoTypes.TrustState.TRUSTED));
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void rejectKeyChange(UUID peerDeviceId) {
        lock.writeLock().lock();
        try {
            CryptoTypes.TrustRecord existing = trust.get(peerDeviceId);
            byte[] key = existing == null ? null : existing.identityPublicKey();
            trust.put(peerDeviceId,
                    new CryptoTypes.TrustRecord(peerDeviceId, key,
                            CryptoTypes.TrustState.PAUSED_KEY_CHANGED));
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void markRevoked(UUID peerDeviceId) {
        lock.writeLock().lock();
        try {
            CryptoTypes.TrustRecord existing = trust.get(peerDeviceId);
            byte[] key = existing == null ? null : existing.identityPublicKey();
            trust.put(peerDeviceId,
                    new CryptoTypes.TrustRecord(peerDeviceId, key,
                            CryptoTypes.TrustState.REVOKED_EXPLICIT));
            persistSnapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---- snapshot I/O ----

    private void requireProvisioned() {
        if (device == null) {
            throw new IllegalStateException("own device not provisioned");
        }
    }

    private void loadOrInit(UUID expectedDeviceId, int expectedRegistrationId) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new PersistentStoreException("cannot create store directory: " + dir, e);
        }
        if (!Files.exists(file)) {
            this.ownDeviceId = Objects.requireNonNull(expectedDeviceId, "ownDeviceId");
            this.registrationId = expectedRegistrationId;
            persistSnapshot();
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PersistentStoreException("cannot read store snapshot: " + file, e);
        }
        SnapshotJson.Obj root;
        try {
            root = SnapshotJson.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw new PersistentStoreException("corrupt store snapshot: " + file, e);
        }
        try {
            int version = reqInt(root, "formatVersion");
            if (version != CryptoTypes.STORE_FORMAT_VERSION) {
                throw new PersistentStoreException(
                        "unsupported store format version " + version
                                + "; expected " + CryptoTypes.STORE_FORMAT_VERSION
                                + " (upgrades require an explicit migration path)");
            }
            UUID storedDevice = UUID.fromString(reqText(root, "ownDeviceId"));
            int storedReg = reqInt(root, "registrationId");
            if (!storedDevice.equals(Objects.requireNonNull(expectedDeviceId, "ownDeviceId"))
                    || storedReg != expectedRegistrationId) {
                throw new PersistentStoreException(
                        "store belongs to a different device/registration; refusing to open");
            }
            this.ownDeviceId = storedDevice;
            this.registrationId = storedReg;
            SnapshotJson.Val deviceVal = root.get("device");
            if (!(deviceVal instanceof SnapshotJson.Nul)) {
                SnapshotJson.Obj deviceNode = asObject(deviceVal, "device");
                device = new DeviceState(
                        reqBytes(deviceNode, "identityPublicKey"),
                        UUID.fromString(reqText(deviceNode, "identityHandle")),
                        reqInt(deviceNode, "signedPrekeyId"),
                        reqBytes(deviceNode, "signedPrekeyPublic"),
                        UUID.fromString(reqText(deviceNode, "signedHandle")));
            }
            for (SnapshotJson.Val entry : asArray(root.get("oneTimePrivates")).items()) {
                SnapshotJson.Obj item = asObject(entry, "oneTimePrivates[]");
                otpkHandles.put(reqInt(item, "id"), UUID.fromString(reqText(item, "handle")));
            }
            for (SnapshotJson.Val entry : asArray(root.get("sessions")).items()) {
                SnapshotJson.Obj node = asObject(entry, "sessions[]");
                CryptoTypes.SessionRecord record = new CryptoTypes.SessionRecord(
                        UUID.fromString(reqText(node, "peerDeviceId")),
                        optBytes(node, "peerIdentityPublicKey"),
                        reqInt(node, "peerRegistrationId"),
                        CryptoTypes.LocalSessionState.valueOf(reqText(node, "state")),
                        optEnum(node, "establishedVia", CryptoTypes.EstablishmentMode.class),
                        optBytes(node, "sessionBlob"),
                        reqLong(node, "encryptCounter"),
                        reqLong(node, "decryptCounter"));
                sessions.put(record.peerDeviceId(), record);
            }
            for (SnapshotJson.Val entry : asArray(root.get("trust")).items()) {
                SnapshotJson.Obj node = asObject(entry, "trust[]");
                CryptoTypes.TrustRecord record = new CryptoTypes.TrustRecord(
                        UUID.fromString(reqText(node, "peerDeviceId")),
                        optBytes(node, "identityPublicKey"),
                        CryptoTypes.TrustState.valueOf(reqText(node, "state")));
                trust.put(record.peerDeviceId(), record);
            }
            for (SnapshotJson.Val entry : asArray(root.get("slots")).items()) {
                SnapshotJson.Obj node = asObject(entry, "slots[]");
                CryptoTypes.OutboundSlot slot = new CryptoTypes.OutboundSlot(
                        UUID.fromString(reqText(node, "messageRequestId")),
                        UUID.fromString(reqText(node, "senderDeviceId")),
                        UUID.fromString(reqText(node, "recipientDeviceId")),
                        UUID.fromString(reqText(node, "claimRequestId")),
                        CryptoTypes.OutboundSlotState.valueOf(reqText(node, "state")),
                        optEnum(node, "establishmentMode", CryptoTypes.EstablishmentMode.class),
                        optEnum(node, "envelopeType", CryptoTypes.EnvelopeType.class),
                        optBundle(node.get("claimedBundle")),
                        optBytes(node, "envelopeCiphertext"));
                slots.put(slotKey(slot.messageRequestId(), slot.recipientDeviceId()), slot);
            }
        } catch (PersistentStoreException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new PersistentStoreException("corrupt store snapshot: " + file, e);
        }
    }

    private CryptoTypes.RecipientBundle optBundle(SnapshotJson.Val node) {
        if (node instanceof SnapshotJson.Nul) {
            return null;
        }
        SnapshotJson.Obj bundle = asObject(node, "claimedBundle");
        try {
            return new CryptoTypes.RecipientBundle(
                    UUID.fromString(reqText(bundle, "deviceId")),
                    UUID.fromString(reqText(bundle, "userId")),
                    reqInt(bundle, "signalDeviceId"),
                    reqInt(bundle, "registrationId"),
                    optBytes(bundle, "identityPublicKey"),
                    reqInt(bundle, "signedPrekeyId"),
                    optBytes(bundle, "signedPrekey"),
                    optBytes(bundle, "signedPrekeySignature"),
                    optInt(bundle, "oneTimePrekeyId"),
                    optBytes(bundle, "oneTimePrekey"),
                    optInt(bundle, "kyberPrekeyId"),
                    optBytes(bundle, "kyberPrekey"),
                    optBytes(bundle, "kyberPrekeySignature"));
        } catch (PersistentStoreException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new PersistentStoreException("corrupt claimed bundle in snapshot", e);
        }
    }

    private void persistSnapshot() {
        // Crash-simulation point: a fault here means nothing in this boundary
        // became durable; recovery observes the previous complete snapshot.
        fault.beforeCommit();
        byte[] bytes = SnapshotJson.render(renderSnapshot()).getBytes(StandardCharsets.UTF_8);
        Path tmp = dir.resolve(FILE_NAME + ".tmp");
        try {
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.READ)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new PersistentStoreException(
                        "store directory requires atomic rename support", e);
            }
            try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
                dirChannel.force(true);
            }
        } catch (PersistentStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new PersistentStoreException("cannot durably write store snapshot", e);
        }
    }

    private SnapshotJson.Obj renderSnapshot() {
        SnapshotJson.Obj root = new SnapshotJson.Obj();
        root.put("formatVersion", new SnapshotJson.Num(CryptoTypes.STORE_FORMAT_VERSION));
        root.put("ownDeviceId", new SnapshotJson.Str(ownDeviceId.toString()));
        root.put("registrationId", new SnapshotJson.Num(registrationId));
        if (device == null) {
            root.put("device", SnapshotJson.NULL);
        } else {
            SnapshotJson.Obj deviceNode = new SnapshotJson.Obj();
            deviceNode.put("identityPublicKey", new SnapshotJson.Str(encode(device.identityPublicKey())));
            deviceNode.put("identityHandle", new SnapshotJson.Str(device.identityHandle().toString()));
            deviceNode.put("signedPrekeyId", new SnapshotJson.Num(device.signedPrekeyId()));
            deviceNode.put("signedPrekeyPublic", new SnapshotJson.Str(encode(device.signedPrekeyPublic())));
            deviceNode.put("signedHandle", new SnapshotJson.Str(device.signedHandle().toString()));
            root.put("device", deviceNode);
        }
        SnapshotJson.Arr otpks = new SnapshotJson.Arr();
        for (Map.Entry<Integer, UUID> entry : otpkHandles.entrySet()) {
            SnapshotJson.Obj item = new SnapshotJson.Obj();
            item.put("id", new SnapshotJson.Num(entry.getKey()));
            item.put("handle", new SnapshotJson.Str(entry.getValue().toString()));
            otpks.add(item);
        }
        root.put("oneTimePrivates", otpks);
        SnapshotJson.Arr sessionArray = new SnapshotJson.Arr();
        for (CryptoTypes.SessionRecord record : sessions.values()) {
            SnapshotJson.Obj node = new SnapshotJson.Obj();
            node.put("peerDeviceId", new SnapshotJson.Str(record.peerDeviceId().toString()));
            putBytes(node, "peerIdentityPublicKey", record.peerIdentityPublicKey());
            node.put("peerRegistrationId", new SnapshotJson.Num(record.peerRegistrationId()));
            node.put("state", new SnapshotJson.Str(record.state().name()));
            putEnum(node, "establishedVia", record.establishedVia());
            putBytes(node, "sessionBlob", record.sessionBlob());
            node.put("encryptCounter", new SnapshotJson.Num(record.encryptCounter()));
            node.put("decryptCounter", new SnapshotJson.Num(record.decryptCounter()));
            sessionArray.add(node);
        }
        root.put("sessions", sessionArray);
        SnapshotJson.Arr trustArray = new SnapshotJson.Arr();
        for (CryptoTypes.TrustRecord record : trust.values()) {
            SnapshotJson.Obj node = new SnapshotJson.Obj();
            node.put("peerDeviceId", new SnapshotJson.Str(record.peerDeviceId().toString()));
            putBytes(node, "identityPublicKey", record.identityPublicKey());
            node.put("state", new SnapshotJson.Str(record.state().name()));
            trustArray.add(node);
        }
        root.put("trust", trustArray);
        SnapshotJson.Arr slotArray = new SnapshotJson.Arr();
        for (CryptoTypes.OutboundSlot slot : slots.values()) {
            SnapshotJson.Obj node = new SnapshotJson.Obj();
            node.put("messageRequestId", new SnapshotJson.Str(slot.messageRequestId().toString()));
            node.put("senderDeviceId", new SnapshotJson.Str(slot.senderDeviceId().toString()));
            node.put("recipientDeviceId", new SnapshotJson.Str(slot.recipientDeviceId().toString()));
            node.put("claimRequestId", new SnapshotJson.Str(slot.claimRequestId().toString()));
            node.put("state", new SnapshotJson.Str(slot.state().name()));
            putEnum(node, "establishmentMode", slot.establishmentMode());
            putEnum(node, "envelopeType", slot.envelopeType());
            if (slot.claimedBundle() == null) {
                node.put("claimedBundle", SnapshotJson.NULL);
            } else {
                node.put("claimedBundle", renderBundle(slot.claimedBundle()));
            }
            putBytes(node, "envelopeCiphertext", slot.envelopeCiphertext());
            slotArray.add(node);
        }
        root.put("slots", slotArray);
        return root;
    }

    private SnapshotJson.Obj renderBundle(CryptoTypes.RecipientBundle bundle) {
        SnapshotJson.Obj node = new SnapshotJson.Obj();
        node.put("deviceId", new SnapshotJson.Str(bundle.deviceId().toString()));
        node.put("userId", new SnapshotJson.Str(bundle.userId().toString()));
        node.put("signalDeviceId", new SnapshotJson.Num(bundle.signalDeviceId()));
        node.put("registrationId", new SnapshotJson.Num(bundle.registrationId()));
        putBytes(node, "identityPublicKey", bundle.identityPublicKey());
        node.put("signedPrekeyId", new SnapshotJson.Num(bundle.signedPrekeyId()));
        putBytes(node, "signedPrekey", bundle.signedPrekey());
        putBytes(node, "signedPrekeySignature", bundle.signedPrekeySignature());
        if (bundle.oneTimePrekeyId() == null) {
            node.put("oneTimePrekeyId", SnapshotJson.NULL);
        } else {
            node.put("oneTimePrekeyId", new SnapshotJson.Num(bundle.oneTimePrekeyId()));
        }
        putBytes(node, "oneTimePrekey", bundle.oneTimePrekey());
        if (bundle.kyberPrekeyId() == null) {
            node.put("kyberPrekeyId", SnapshotJson.NULL);
        } else {
            node.put("kyberPrekeyId", new SnapshotJson.Num(bundle.kyberPrekeyId()));
        }
        putBytes(node, "kyberPrekey", bundle.kyberPrekey());
        putBytes(node, "kyberPrekeySignature", bundle.kyberPrekeySignature());
        return node;
    }

    // ---- snapshot field helpers ----

    private static String slotKey(CryptoTypes.OutboundSlot slot) {
        return slotKey(slot.messageRequestId(), slot.recipientDeviceId());
    }

    private static String slotKey(UUID messageRequestId, UUID recipient) {
        return messageRequestId + ":" + recipient;
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

    private static byte[] copy(byte[] in) {
        return in == null ? null : Arrays.copyOf(in, in.length);
    }

    private static String encode(byte[] in) {
        return Base64.getEncoder().encodeToString(in);
    }

    private static byte[] decode(String in) {
        return Base64.getDecoder().decode(in);
    }

    private static SnapshotJson.Obj asObject(SnapshotJson.Val value, String field) {
        if (value instanceof SnapshotJson.Obj obj) {
            return obj;
        }
        throw new PersistentStoreException("snapshot corrupt object field: " + field);
    }

    private static SnapshotJson.Arr asArray(SnapshotJson.Val value) {
        if (value instanceof SnapshotJson.Arr arr) {
            return arr;
        }
        throw new PersistentStoreException("snapshot corrupt array field");
    }

    private void putBytes(SnapshotJson.Obj node, String field, byte[] value) {
        if (value == null) {
            node.put(field, SnapshotJson.NULL);
        } else {
            node.put(field, new SnapshotJson.Str(encode(value)));
        }
    }

    private void putEnum(SnapshotJson.Obj node, String field, Enum<?> value) {
        if (value == null) {
            node.put(field, SnapshotJson.NULL);
        } else {
            node.put(field, new SnapshotJson.Str(value.name()));
        }
    }

    private static String reqText(SnapshotJson.Obj node, String field) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Str str) {
            return str.value();
        }
        throw new PersistentStoreException("snapshot missing text field: " + field);
    }

    private static int reqInt(SnapshotJson.Obj node, String field) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Num num) {
            long value = num.value();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new PersistentStoreException("snapshot integer out of range: " + field);
            }
            return (int) value;
        }
        throw new PersistentStoreException("snapshot missing numeric field: " + field);
    }

    private static long reqLong(SnapshotJson.Obj node, String field) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Num num) {
            return num.value();
        }
        throw new PersistentStoreException("snapshot missing numeric field: " + field);
    }

    private static byte[] reqBytes(SnapshotJson.Obj node, String field) {
        return decode(reqText(node, field));
    }

    private static byte[] optBytes(SnapshotJson.Obj node, String field) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Nul) {
            return null;
        }
        if (child instanceof SnapshotJson.Str str) {
            try {
                return decode(str.value());
            } catch (IllegalArgumentException e) {
                throw new PersistentStoreException("snapshot corrupt base64 field: " + field, e);
            }
        }
        throw new PersistentStoreException("snapshot corrupt bytes field: " + field);
    }

    private static Integer optInt(SnapshotJson.Obj node, String field) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Nul) {
            return null;
        }
        if (child instanceof SnapshotJson.Num num) {
            long value = num.value();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new PersistentStoreException("snapshot integer out of range: " + field);
            }
            return (int) value;
        }
        throw new PersistentStoreException("snapshot corrupt numeric field: " + field);
    }

    private static <E extends Enum<E>> E optEnum(SnapshotJson.Obj node, String field, Class<E> type) {
        SnapshotJson.Val child = node.get(field);
        if (child instanceof SnapshotJson.Nul) {
            return null;
        }
        if (child instanceof SnapshotJson.Str str) {
            try {
                return Enum.valueOf(type, str.value());
            } catch (IllegalArgumentException e) {
                throw new PersistentStoreException("snapshot corrupt enum field: " + field, e);
            }
        }
        throw new PersistentStoreException("snapshot corrupt enum field: " + field);
    }
}
