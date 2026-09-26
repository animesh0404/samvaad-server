package com.samvaad.samvaad_server.e2ee.client.fake;

import com.samvaad.samvaad_server.e2ee.client.CryptoException;
import com.samvaad.samvaad_server.e2ee.client.CryptoTypes;
import com.samvaad.samvaad_server.e2ee.client.SignalAdapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic non-cryptographic stand-in for the real Signal adapter.
 *
 * <p>Blobs are structured text (never real key math); counters make every
 * establish/encrypt call observable so tests can assert no-divergence and no
 * unnecessary re-establishment. Structural bundle checks only — explicitly
 * NOT signature verification.
 */
public final class FakeSignalAdapter implements SignalAdapter {

    private final AtomicInteger establishCalls = new AtomicInteger();
    private final AtomicInteger encryptCalls = new AtomicInteger();
    private final AtomicInteger decryptCalls = new AtomicInteger();
    private volatile boolean corruptNext = false;
    private final ConcurrentHashMap<String, Integer> sessions = new ConcurrentHashMap<>();
    private final List<CryptoTypes.SignalAddress> establishedAddresses =
            Collections.synchronizedList(new ArrayList<>());

    @Override
    public LocalIdentity generateIdentity() {
        UUID id = UUID.randomUUID();
        return new LocalIdentity(("fake-pub:" + id).getBytes(StandardCharsets.UTF_8),
                new FakeHandle(id));
    }

    @Override
    public SignedPrekeyPair generateSignedPrekey(SealedPrivateHandle identityPrivate, int prekeyId) {
        UUID id = UUID.randomUUID();
        return new SignedPrekeyPair(prekeyId,
                ("fake-spub:" + prekeyId + ":" + id).getBytes(StandardCharsets.UTF_8),
                new FakeHandle(id));
    }

    @Override
    public OneTimePrekeyPair generateOneTimePrekey(int prekeyId) {
        UUID id = UUID.randomUUID();
        return new OneTimePrekeyPair(prekeyId,
                ("fake-opub:" + prekeyId + ":" + id).getBytes(StandardCharsets.UTF_8),
                new FakeHandle(id));
    }

    @Override
    public String fingerprint(byte[] identityPublicKey) {
        if (identityPublicKey == null || identityPublicKey.length == 0) {
            throw new CryptoException.ClaimFailedException("empty identity key");
        }
        int h = Arrays.hashCode(identityPublicKey);
        return "fake-fp:" + Integer.toHexString(h);
    }

    @Override
    public boolean verifySignedPrekey(byte[] identityPublicKey, byte[] signedPrekey, byte[] signature) {
        return identityPublicKey != null && identityPublicKey.length > 0
                && signedPrekey != null && signedPrekey.length > 0
                && signature != null && signature.length > 0;
    }

    @Override
    public EstablishedSession establishOutbound(
            SealedPrivateHandle ownIdentityPrivate, CryptoTypes.RecipientBundle bundle) {
        if (bundle == null || bundle.identityPublicKey() == null) {
            throw new CryptoException.ClaimFailedException("bad bundle");
        }
        if (!bundle.hasKyber()) {
            // Models the frozen invariant: libsignal 0.103.x rejects bundles
            // without last-resort Kyber material.
            throw new CryptoException.ClaimFailedException("bundle lacks kyber");
        }
        int n = establishCalls.incrementAndGet();
        establishedAddresses.add(bundle.address());
        String blob = "fake-session:" + bundle.deviceId() + ":"
                + (bundle.hasOneTimePrekey() ? "otpk:" + bundle.oneTimePrekeyId() : "fallback")
                + ":n" + n;
        sessions.put(blob, 0);
        CryptoTypes.EstablishmentMode mode = bundle.hasOneTimePrekey()
                ? CryptoTypes.EstablishmentMode.WITH_ONE_TIME_PREKEY
                : CryptoTypes.EstablishmentMode.SIGNED_PREKEY_FALLBACK;
        return new EstablishedSession(blob.getBytes(StandardCharsets.UTF_8), mode);
    }

    @Override
    public DecryptResult decryptPrekeyInit(
            SealedPrivateHandle ownIdentityPrivate,
            SealedPrivateHandle ownSignedPrivate,
            List<SealedPrivateHandle> ownOneTimePrivates,
            byte[] currentSessionBlobOrNull,
            byte[] envelopeCiphertext) {
        decryptCalls.incrementAndGet();
        maybeCorrupt();
        // Convergence: keep the existing outbound session usable by deriving
        // the inbound state from it when present, else create fresh state.
        String base = currentSessionBlobOrNull == null
                ? "fake-inbound:n" + decryptCalls.get()
                : new String(currentSessionBlobOrNull, StandardCharsets.UTF_8) + "+in";
        byte[] updated = base.getBytes(StandardCharsets.UTF_8);
        sessions.put(base, 1);
        return new DecryptResult(updated, plainOf(envelopeCiphertext));
    }

    @Override
    public EncryptResult encrypt(byte[] sessionBlob, byte[] plaintextAssoc) {
        encryptCalls.incrementAndGet();
        maybeCorrupt();
        if (sessionBlob == null || sessionBlob.length == 0) {
            throw new CryptoException.SessionCorruptException("missing session");
        }
        String base = new String(sessionBlob, StandardCharsets.UTF_8);
        String next = base + "#e" + encryptCalls.get();
        sessions.put(next, 1);
        String cipher = "fake-ct:" + next + ":" + new String(plaintextAssoc, StandardCharsets.UTF_8);
        return new EncryptResult(
                next.getBytes(StandardCharsets.UTF_8), cipher.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public DecryptResult decrypt(byte[] sessionBlob, byte[] envelopeCiphertext) {
        decryptCalls.incrementAndGet();
        maybeCorrupt();
        if (sessionBlob == null || sessionBlob.length == 0) {
            throw new CryptoException.SessionCorruptException("missing session");
        }
        String base = new String(sessionBlob, StandardCharsets.UTF_8);
        String next = base + "#d" + decryptCalls.get();
        return new DecryptResult(
                next.getBytes(StandardCharsets.UTF_8), plainOf(envelopeCiphertext));
    }

    public int establishCalls() {
        return establishCalls.get();
    }

    public int encryptCalls() {
        return encryptCalls.get();
    }

    public int decryptCalls() {
        return decryptCalls.get();
    }

    /** Frozen addresses the fake established sessions for, in call order. */
    public List<CryptoTypes.SignalAddress> establishedAddresses() {
        return List.copyOf(establishedAddresses);
    }

    /** Fail the next crypto op with corruption (crash/corrupt simulation). */
    public void corruptNext() {
        corruptNext = true;
    }

    private void maybeCorrupt() {
        if (corruptNext) {
            corruptNext = false;
            throw new CryptoException.SessionCorruptException("injected corruption");
        }
    }

    private static byte[] plainOf(byte[] envelopeCiphertext) {
        String s = new String(envelopeCiphertext, StandardCharsets.UTF_8);
        String prefix = "fake-ct:";
        if (s.startsWith(prefix)) {
            // fake-ct:<session>:<plain>
            int split = s.indexOf(':', prefix.length());
            // session part itself contains colons; recover by stripping envelope
            // framing in tests via endsWith check instead — return raw payload.
            return s.getBytes(StandardCharsets.UTF_8);
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public record FakeHandle(UUID handleId) implements SealedPrivateHandle {
    }
}
