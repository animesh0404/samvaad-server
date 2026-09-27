package com.samvaad.e2ee.client.signal;

import com.samvaad.e2ee.client.PersistentStoreException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-based file vault for sealed private-key material (JVM reference
 * custody backend, JDK crypto only).
 *
 * <p>Master key: PBKDF2-HMAC-SHA256 (600,000 iterations, 256-bit) over the
 * caller-supplied password and a random per-vault 16-byte salt. Entries:
 * AES-256-GCM with a fresh 12-byte nonce per write, handle-UUID + kind as
 * associated data (entry swapping fails authentication). File layout is
 * versioned ({@code SAMVAAD-VAULT-V1}, format 1); unknown versions are
 * refused, never migrated. Writes go through temp-file + fsync + atomic
 * rename, so a crash leaves the previous complete vault or the new one.
 *
 * <p>Password handling: the caller passes a {@code char[]} (fresh copy per
 * open recommended); this vault copies it for key derivation, then zeroes
 * both its copy and the caller's array. The password never reaches disk,
 * logs, or configuration — there is deliberately no file/env/config
 * fallback. A nil-UUID verifier entry makes wrong passwords and tampering
 * fail fast at open time.
 *
 * <p>Security notes (honest limits): memory hygiene is best-effort — Java
 * heap copies made by the runtime/GC cannot be wiped, only the vault's own
 * buffers are zeroed on close. Side-channel resistance is whatever the
 * platform JCE provider offers. One vault per device home directory;
 * sharing a directory between devices is unsupported and fails closed on
 * owner mismatch.
 */
public final class FilePrivateKeyVault implements PrivateKeyVault {

    private static final String FILE_NAME = "crypto-vault-v1.dat";
    private static final byte[] MAGIC = "SAMVAAD-VAULT-V1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final byte KDF_PBKDF2_SHA256 = 1;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte KIND_CHECK = 0x7F;
    private static final byte[] VERIFIER_PLAINTEXT =
            "samvaad-vault-check-v1".getBytes(StandardCharsets.US_ASCII);
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_ENTRY_BYTES = 1024 * 1024;

    private final Path dir;
    private final Path file;
    private final UUID ownerDeviceId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<UUID, StoredEntry> entries = new LinkedHashMap<>();
    private byte[] salt;
    private byte[] masterKey;
    private volatile boolean closed;

    private record StoredEntry(byte kind, byte[] nonce, byte[] ciphertext) {
    }

    private FilePrivateKeyVault(Path dir, UUID ownerDeviceId, char[] password) {
        this.dir = dir;
        this.file = dir.resolve(FILE_NAME);
        this.ownerDeviceId = Objects.requireNonNull(ownerDeviceId, "ownerDeviceId");
        Objects.requireNonNull(password, "password");
        char[] copy = Arrays.copyOf(password, password.length);
        Arrays.fill(password, '\0');
        try {
            if (!Files.exists(file)) {
                byte[] freshSalt = new byte[SALT_BYTES];
                new SecureRandom().nextBytes(freshSalt);
                this.salt = freshSalt;
                this.masterKey = derive(copy, freshSalt);
                entries.put(NIL_UUID, verifierEntry());
                persistLocked();
            } else {
                byte[] raw;
                try {
                    raw = Files.readAllBytes(file);
                } catch (IOException e) {
                    throw new PersistentStoreException("cannot read vault file: " + file, e);
                }
                unlock(copy, raw);
            }
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /**
     * Opens (creating with a fresh salt when absent) the vault in {@code
     * dir} for {@code ownerDeviceId}. The caller's password array is zeroed
     * before return. Wrong passwords, tampering, version drift, and foreign
     * owners fail closed with {@link PersistentStoreException}.
     */
    public static FilePrivateKeyVault open(Path dir, UUID ownerDeviceId, char[] password) {
        try {
            Files.createDirectories(Objects.requireNonNull(dir, "dir"));
        } catch (IOException e) {
            throw new PersistentStoreException("cannot create vault directory: " + dir, e);
        }
        return new FilePrivateKeyVault(dir, ownerDeviceId, password);
    }

    /** Vault directory (lets tests inspect the raw vault file). */
    public Path directory() {
        return dir;
    }

    @Override
    public void store(UUID handleId, KeyKind kind, byte[] plaintext) {
        Objects.requireNonNull(handleId, "handleId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(plaintext, "plaintext");
        if (handleId.equals(NIL_UUID)) {
            throw new IllegalArgumentException("nil handle id is reserved");
        }
        lock.writeLock().lock();
        try {
            ensureOpen();
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            byte[] ciphertext = seal(handleId, (byte) kind.ordinal(), nonce, plaintext);
            entries.put(handleId, new StoredEntry((byte) kind.ordinal(), nonce, ciphertext));
            persistLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public SealedEntry load(UUID handleId) {
        Objects.requireNonNull(handleId, "handleId");
        lock.readLock().lock();
        try {
            ensureOpen();
            StoredEntry stored = entries.get(handleId);
            if (stored == null) {
                return null;
            }
            byte[] plaintext = openEntry(handleId, stored);
            return new SealedEntry(KeyKind.values()[stored.kind() & 0xFF], plaintext);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void remove(UUID handleId) {
        Objects.requireNonNull(handleId, "handleId");
        lock.writeLock().lock();
        try {
            ensureOpen();
            if (entries.remove(handleId) != null) {
                persistLocked();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Set<UUID> handlesOfKind(KeyKind kind) {
        Objects.requireNonNull(kind, "kind");
        lock.readLock().lock();
        try {
            ensureOpen();
            Set<UUID> out = new HashSet<>();
            for (Map.Entry<UUID, StoredEntry> entry : entries.entrySet()) {
                if (!entry.getKey().equals(NIL_UUID)
                        && (entry.getValue().kind() & 0xFF) == kind.ordinal()) {
                    out.add(entry.getKey());
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            closed = true;
            if (masterKey != null) {
                Arrays.fill(masterKey, (byte) 0);
                masterKey = null;
            }
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
                salt = null;
            }
            entries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed || masterKey == null) {
            throw new PersistentStoreException("private-key vault is closed");
        }
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception e) {
            throw new PersistentStoreException("cannot derive vault key", e);
        } finally {
            spec.clearPassword();
        }
    }

    private void unlock(char[] password, byte[] raw) {
        ParsedVault parsed = parse(raw);
        if (parsed.version() != FORMAT_VERSION || parsed.kdfId() != KDF_PBKDF2_SHA256) {
            throw new PersistentStoreException(
                    "unsupported vault format (version " + parsed.version() + ")");
        }
        if (!parsed.owner().equals(ownerDeviceId)) {
            throw new PersistentStoreException(
                    "vault belongs to a different device; refusing to open");
        }
        byte[] key = derive(password, parsed.salt());
        this.salt = Arrays.copyOf(parsed.salt(), parsed.salt().length);
        this.masterKey = key;
        try {
            StoredEntry verifier = parsed.entries().get(NIL_UUID);
            if (verifier == null) {
                throw new PersistentStoreException("vault integrity check missing");
            }
            byte[] check = openEntry(NIL_UUID, verifier);
            try {
                if (!constantTimeEquals(check, VERIFIER_PLAINTEXT)) {
                    throw new PersistentStoreException("vault integrity check failed");
                }
            } finally {
                Arrays.fill(check, (byte) 0);
            }
        } catch (PersistentStoreException e) {
            Arrays.fill(key, (byte) 0);
            this.masterKey = null;
            this.salt = null;
            throw e;
        }
        entries.clear();
        entries.putAll(parsed.entries());
    }

    private StoredEntry verifierEntry() {
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        return new StoredEntry(KIND_CHECK, nonce, seal(NIL_UUID, KIND_CHECK, nonce, VERIFIER_PLAINTEXT));
    }

    private void persistLocked() {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeByte(KDF_PBKDF2_SHA256);
            out.writeInt(PBKDF2_ITERATIONS);
            writeUuid(out, ownerDeviceId);
            out.writeInt(salt.length);
            out.write(salt);
            Map<UUID, StoredEntry> snapshot = new LinkedHashMap<>(entries);
            if (!snapshot.containsKey(NIL_UUID)) {
                snapshot.put(NIL_UUID, verifierEntry());
            }
            out.writeInt(snapshot.size());
            for (Map.Entry<UUID, StoredEntry> entry : snapshot.entrySet()) {
                writeUuid(out, entry.getKey());
                out.writeByte(entry.getValue().kind());
                out.write(entry.getValue().nonce());
                out.writeInt(entry.getValue().ciphertext().length);
                out.write(entry.getValue().ciphertext());
            }
            out.flush();
            byte[] bytes = body.toByteArray();
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
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
                        "vault directory requires atomic rename support", e);
            }
            try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
                dirChannel.force(true);
            }
            entries.clear();
            entries.putAll(snapshot);
        } catch (PersistentStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new PersistentStoreException("cannot durably write vault file", e);
        }
    }

    private byte[] seal(UUID handleId, byte kind, byte[] nonce, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(handleId, kind));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new PersistentStoreException("cannot seal vault entry", e);
        }
    }

    private byte[] openEntry(UUID handleId, StoredEntry stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, stored.nonce()));
            cipher.updateAAD(aad(handleId, stored.kind()));
            return cipher.doFinal(stored.ciphertext());
        } catch (Exception e) {
            throw new PersistentStoreException(
                    "cannot open vault entry (wrong password or tampered vault)", e);
        }
    }

    private static byte[] aad(UUID handleId, byte kind) {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.putLong(handleId.getMostSignificantBits());
        buffer.putLong(handleId.getLeastSignificantBits());
        buffer.put(kind);
        return buffer.array();
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private record ParsedVault(
            int version, byte kdfId, UUID owner, byte[] salt, Map<UUID, StoredEntry> entries) {
    }

    private static ParsedVault parse(byte[] raw) {
        try {
            java.io.DataInputStream in =
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new PersistentStoreException("not a Samvaad vault file");
            }
            int version = in.readInt();
            byte kdfId = in.readByte();
            int iterations = in.readInt();
            if (iterations <= 0) {
                throw new PersistentStoreException("corrupt vault header");
            }
            UUID owner = new UUID(in.readLong(), in.readLong());
            int saltLen = in.readInt();
            if (saltLen <= 0 || saltLen > 128) {
                throw new PersistentStoreException("corrupt vault salt");
            }
            byte[] salt = new byte[saltLen];
            in.readFully(salt);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new PersistentStoreException("corrupt vault entry count");
            }
            Map<UUID, StoredEntry> entries = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                byte kind = in.readByte();
                byte[] nonce = new byte[NONCE_BYTES];
                in.readFully(nonce);
                int ctLen = in.readInt();
                if (ctLen < 0 || ctLen > MAX_ENTRY_BYTES) {
                    throw new PersistentStoreException("corrupt vault entry");
                }
                byte[] ct = new byte[ctLen];
                in.readFully(ct);
                entries.put(id, new StoredEntry(kind, nonce, ct));
            }
            if (in.available() > 0) {
                throw new PersistentStoreException("trailing bytes in vault file");
            }
            return new ParsedVault(version, kdfId, owner, salt, entries);
        } catch (PersistentStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new PersistentStoreException("corrupt vault file", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
