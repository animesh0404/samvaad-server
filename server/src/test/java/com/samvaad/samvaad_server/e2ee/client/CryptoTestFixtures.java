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
        return new CryptoTypes.RecipientBundle(deviceId, userId, signalDeviceId, regId,
                key(seed + ":id"), 11, key(seed + ":spk"), key(seed + ":sig"),
                otpId, key(seed + ":otpk:" + otpId),
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
        private final AtomicInteger otpCounter = new AtomicInteger(5000);
        private final AtomicInteger calls = new AtomicInteger();

        public void register(UUID userId, UUID deviceId, int signalDeviceId, String seed, int regId) {
            seeds.put(deviceId, seed);
            regIds.put(deviceId, regId);
            userIds.put(deviceId, userId);
            signalIds.put(deviceId, signalDeviceId);
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
            CryptoTypes.RecipientBundle bundle = exhausted.contains(recipientDeviceId)
                    ? claimedFallback(userId, recipientDeviceId, signalId, seed, regId)
                    : claimed(userId, recipientDeviceId, signalId, seed, regId, otpCounter.getAndIncrement());
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
        FakeSignalAdapter adapter = new FakeSignalAdapter();
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
}
