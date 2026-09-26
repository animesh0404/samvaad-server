package com.samvaad.samvaad_server.e2ee.client;

import com.samvaad.samvaad_server.e2ee.client.fake.FakeSignalAdapter;
import com.samvaad.samvaad_server.e2ee.client.fake.InMemoryStores;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic harness for the fake-slice state-machine tests. */
public final class CryptoTestFixtures {

    private CryptoTestFixtures() {
    }

    public static byte[] key(String seed) {
        return ("test-key:" + seed).getBytes(StandardCharsets.UTF_8);
    }

    /** Directory-style bundle (Kyber triple present, no OTPK body until claimed). */
    public static CryptoTypes.RecipientBundle listed(
            UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId) {
        return new CryptoTypes.RecipientBundle(deviceId, userId, signalDeviceId, regId,
                key(seed + ":id"), 11, key(seed + ":spk"), key(seed + ":sig"), null, null,
                8100 + signalDeviceId, key(seed + ":kyb"), key(seed + ":ksig"));
    }

    /** Full claimed bundle with OTPK body (Kyber triple constant per device). */
    public static CryptoTypes.RecipientBundle claimed(
            UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId, int otpId) {
        return claimed(userId, deviceId, signalDeviceId, seed, regId, otpId, key(seed + ":otpk:" + otpId));
    }

    /** Full claimed bundle with an explicit OTPK body (e.g. a device-uploaded public). */
    public static CryptoTypes.RecipientBundle claimed(
            UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId,
            int otpId, byte[] otpPublic) {
        return new CryptoTypes.RecipientBundle(deviceId, userId, signalDeviceId, regId,
                key(seed + ":id"), 11, key(seed + ":spk"), key(seed + ":sig"),
                otpId, otpPublic,
                8100 + signalDeviceId, key(seed + ":kyb"), key(seed + ":ksig"));
    }

    /** Claimed bundle modelling an exhausted pool: signed-prekey fallback (Kyber still present). */
    public static CryptoTypes.RecipientBundle claimedFallback(
            UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId) {
        return new CryptoTypes.RecipientBundle(deviceId, userId, signalDeviceId, regId,
                key(seed + ":id"), 11, key(seed + ":spk"), key(seed + ":sig"), null, null,
                8100 + signalDeviceId, key(seed + ":kyb"), key(seed + ":ksig"));
    }

    public static final class ClaimFake implements SamvaadCryptoService.ClaimClient {
        private final Map<UUID, String> seeds = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> regIds = new ConcurrentHashMap<>();
        private final Map<UUID, UUID> userIds = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> signalIds = new ConcurrentHashMap<>();
        private final Set<UUID> exhausted = ConcurrentHashMap.newKeySet();
        private final Map<String, CryptoTypes.RecipientBundle> replay = new ConcurrentHashMap<>();
        /**
         * Server-side pool of device-uploaded OTPK publics, mirroring the
         * real directory: each fresh claimRequestId pops exactly one entry,
         * so one server OTPK is consumed per slot and retries replay the
         * same bundle. Empty pool (or exhausted flag) models an empty pool
         * and yields signed-prekey fallback.
         */
        private final Map<UUID, java.util.Queue<SignalAdapter.OneTimePrekeyPair>> otpkPool =
                new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        public void register(UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId) {
            seeds.put(deviceId, seed);
            regIds.put(deviceId, regId);
            userIds.put(deviceId, userId);
            signalIds.put(deviceId, signalDeviceId);
        }

        /** Server-side upload: public parts only, privates stay on the device. */
        public void uploadOneTimePrekeys(
                UUID deviceId, List<SignalAdapter.OneTimePrekeyPair> pairs) {
            otpkPool.computeIfAbsent(deviceId, k -> new java.util.ArrayDeque<>()).addAll(pairs);
        }

        /** Uploaded OTPK publics still available for this device. */
        public int availableOneTimePrekeys(UUID deviceId) {
            java.util.Queue<SignalAdapter.OneTimePrekeyPair> q = otpkPool.get(deviceId);
            return q == null ? 0 : q.size();
        }

        public void setExhausted(UUID deviceId, boolean value) {
            if (value) {
                exhausted.add(deviceId);
            } else {
                exhausted.remove(deviceId);
            }
        }

        @Override
        public CryptoTypes.RecipientBundle claim(UUID recipientDeviceId, UUID claimRequestId) {
            calls.incrementAndGet();
            String k = recipientDeviceId + ":" + claimRequestId;
            CryptoTypes.RecipientBundle hit = replay.get(k);
            if (hit != null) {
                return hit;
            }
            String seed = seeds.getOrDefault(recipientDeviceId, recipientDeviceId.toString());
            int regId = regIds.getOrDefault(recipientDeviceId, 7);
            UUID userId = userIds.getOrDefault(recipientDeviceId, recipientDeviceId);
            int signalId = signalIds.getOrDefault(recipientDeviceId, 1);
            SignalAdapter.OneTimePrekeyPair otpk = null;
            if (!exhausted.contains(recipientDeviceId)) {
                java.util.Queue<SignalAdapter.OneTimePrekeyPair> q = otpkPool.get(recipientDeviceId);
                if (q != null) {
                    otpk = q.poll();
                }
            }
            CryptoTypes.RecipientBundle bundle = otpk != null
                    ? claimed(userId, recipientDeviceId, signalId, seed, regId,
                            otpk.prekeyId(), otpk.publicKey())
                    : claimedFallback(userId, recipientDeviceId, signalId, seed, regId);
            replay.put(k, bundle);
            return bundle;
        }

        public int calls() {
            return calls.get();
        }
    }

    public static final class SubmitFake implements SamvaadCryptoService.SubmitClient {
        private final List<List<CryptoTypes.OutboundEnvelope>> batches = new ArrayList<>();
        private boolean failNext = false;
        private final AtomicInteger calls = new AtomicInteger();

        public void failNext() {
            failNext = true;
        }

        @Override
        public void submit(UUID messageRequestId, List<CryptoTypes.OutboundEnvelope> envelopes) {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new CryptoException.TransientException("injected submit failure");
            }
            batches.add(List.copyOf(envelopes));
        }

        public List<List<CryptoTypes.OutboundEnvelope>> batches() {
            return batches;
        }

        public int calls() {
            return calls.get();
        }
    }

    public record Harness(
            FakeSignalAdapter adapter,
            InMemoryStores.DeviceKeys keys,
            InMemoryStores.Sessions sessions,
            InMemoryStores.Trust trust,
            ClaimFake claimFake,
            SubmitFake submitFake,
            SamvaadCryptoService service) {
    }

    public static Harness harness(UUID ownDeviceId) {
        return harness(ownDeviceId, new FakeSignalAdapter());
    }

    public static Harness harness(UUID ownDeviceId, FakeSignalAdapter adapter) {
        InMemoryStores.DeviceKeys keys = new InMemoryStores.DeviceKeys(ownDeviceId, 42);
        keys.provision(adapter.generateIdentity(), adapter.generateSignedPrekey(new FakeSignalAdapter.FakeHandle(UUID.randomUUID()), 11));
        InMemoryStores.Sessions sessions = new InMemoryStores.Sessions();
        InMemoryStores.Trust trust = new InMemoryStores.Trust();
        ClaimFake claimFake = new ClaimFake();
        SubmitFake submitFake = new SubmitFake();
        SamvaadCryptoService service = new SamvaadCryptoServiceImpl(
                adapter, keys, sessions, trust, claimFake, submitFake);
        return new Harness(adapter, keys, sessions, trust, claimFake, submitFake, service);
    }

    /**
     * Honest OTPK issuance for inbound tests: generates {@code count} real
     * pairs from the owner's adapter, keeps the sealed privates in the
     * owner's device store, and uploads the public parts to the server view
     * used by senders. Returns the issued pairs in pool (FIFO claim) order.
     */
    public static List<SignalAdapter.OneTimePrekeyPair> uploadOtpks(
            Harness owner, ClaimFake serverView, UUID deviceId, int firstId, int count) {
        List<SignalAdapter.OneTimePrekeyPair> pairs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            SignalAdapter.OneTimePrekeyPair pair = owner.adapter().generateOneTimePrekey(firstId + i);
            owner.keys().putOneTimePrivate(pair.prekeyId(), pair.privateHandle());
            pairs.add(pair);
        }
        serverView.uploadOneTimePrekeys(deviceId, pairs);
        return List.copyOf(pairs);
    }
}
