package com.samvaad.e2ee.client.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.samvaad.e2ee.client.CryptoException;
import com.samvaad.e2ee.client.CryptoTestFixtures;
import com.samvaad.e2ee.client.CryptoTypes;
import com.samvaad.e2ee.client.PersistentStoreException;
import com.samvaad.e2ee.client.SamvaadCryptoService;
import com.samvaad.e2ee.client.SamvaadCryptoServiceImpl;
import com.samvaad.e2ee.client.SignalAdapter;
import com.samvaad.e2ee.client.persist.FileBackedClientCryptoStore;

/**
 * Restart proof for persistent JVM private-key custody: a real device
 * seals identity/signed/OTPK/Kyber material into the password vault, the
 * in-memory adapter and vault are destroyed, and a fresh instance recovers
 * everything from disk — fresh establishment, inbound PREKEY_INIT, and
 * continued ratchet messaging all work, while wrong credentials fail
 * closed and the normal snapshot stays free of private bytes.
 */
class KeyCustodyRestartTest {

    /** Test-only vault password material (never a real secret). */
    private static char[] testPassword() {
        return "samvaad-test-only-vault-password".toCharArray();
    }

    /** One vault-backed real device behind the Samvaad service. */
    record VaultDevice(
            LibSignalAdapter adapter,
            FilePrivateKeyVault vault,
            FileBackedClientCryptoStore stores,
            CryptoTestFixtures.ClaimFake claims,
            CryptoTestFixtures.SubmitFake submit,
            SamvaadCryptoService service,
            Path dir,
            UUID device,
            UUID user,
            int reg,
            byte[] identityPublic,
            SignalAdapter.SignedPrekeyPair signed,
            SignalAdapter.KyberPrekeyPair kyber) {

        CryptoTypes.RecipientBundle bundle(SignalAdapter.OneTimePrekeyPair otp) {
            return new CryptoTypes.RecipientBundle(device, user, 1, reg, identityPublic,
                    signed.prekeyId(), signed.publicKey(), signed.signature(),
                    otp == null ? null : otp.prekeyId(), otp == null ? null : otp.publicKey(),
                    kyber.prekeyId(), kyber.publicKey(), kyber.signature());
        }

        SignalAdapter.OneTimePrekeyPair issueOtpk(int id) {
            SignalAdapter.OneTimePrekeyPair pair = adapter.generateOneTimePrekey(id);
            stores.putOneTimePrivate(id, pair.privateHandle());
            return pair;
        }
    }

    private static VaultDevice freshDevice(
            Path dir, UUID device, UUID user, int reg, char[] password) {
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, device, password);
        LibSignalAdapter adapter = new LibSignalAdapter(reg, vault);
        FileBackedClientCryptoStore stores =
                FileBackedClientCryptoStore.open(dir, device, reg);
        SignalAdapter.LocalIdentity identity = adapter.generateIdentity();
        SignalAdapter.SignedPrekeyPair signed =
                adapter.generateSignedPrekey(identity.identityPrivate(), 11);
        stores.provision(identity, signed);
        SignalAdapter.KyberPrekeyPair kyber =
                adapter.generateKyberPrekey(stores.identityPrivate(), 22);
        CryptoTestFixtures.ClaimFake claims = new CryptoTestFixtures.ClaimFake();
        CryptoTestFixtures.SubmitFake submit = new CryptoTestFixtures.SubmitFake();
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, claims, submit);
        return new VaultDevice(adapter, vault, stores, claims, submit, service, dir, device,
                user, reg, stores.identityPublicKey(), stores.signedPrekey(), kyber);
    }

    private static VaultDevice reopenDevice(Path dir, UUID device, UUID user, int reg,
            CryptoTestFixtures.ClaimFake claims, CryptoTestFixtures.SubmitFake submit,
            SignalAdapter.KyberPrekeyPair kyberTriple, char[] password) {
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, device, password);
        LibSignalAdapter adapter = new LibSignalAdapter(reg, vault);
        FileBackedClientCryptoStore stores =
                FileBackedClientCryptoStore.open(dir, device, reg);
        SamvaadCryptoService service =
                new SamvaadCryptoServiceImpl(adapter, stores, claims, submit);
        return new VaultDevice(adapter, vault, stores, claims, submit, service, dir, device,
                user, reg, stores.identityPublicKey(), stores.signedPrekey(), kyberTriple);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void restartRecoversAllPrivateMaterial(@TempDir Path aliceDir, @TempDir Path bobDir,
            @TempDir Path daveDir) {
        UUID aliceDevice = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        UUID daveDevice = UUID.randomUUID();
        VaultDevice alice = freshDevice(aliceDir, aliceDevice, UUID.randomUUID(), 1001, testPassword());
        VaultDevice bob = freshDevice(bobDir, bobDevice, UUID.randomUUID(), 1002, testPassword());
        VaultDevice dave = freshDevice(daveDir, daveDevice, UUID.randomUUID(), 1003, testPassword());

        SignalAdapter.OneTimePrekeyPair bobOtp = bob.issueOtpk(100);
        SignalAdapter.OneTimePrekeyPair bobSpare = bob.issueOtpk(101);
        alice.claims().pinBundle(bobDevice, bob.bundle(bobOtp));

        // Healthy baseline before restart.
        UUID msg1 = UUID.randomUUID();
        alice.service().sendToDevices(msg1, aliceDevice, bytes("one"),
                List.of(bob.bundle(bobOtp)), Set.of());
        byte[] wire1 = alice.submit().batches().get(0).get(0).envelopeCiphertext();
        assertArrayEquals(bytes("one"), bob.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, wire1));
        assertTrue(bob.stores().oneTimePrivate(100).isEmpty());

        // Bob replies so both sides acknowledge before the restart.
        SignalAdapter.OneTimePrekeyPair aliceOtp = alice.issueOtpk(300);
        bob.claims().pinBundle(aliceDevice, alice.bundle(aliceOtp));
        UUID replyId = UUID.randomUUID();
        bob.service().sendToDevices(replyId, bobDevice, bytes("reply"),
                List.of(alice.bundle(null)), Set.of());
        byte[] replyWire = bob.submit().batches().get(bob.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertArrayEquals(bytes("reply"), alice.service().decrypt(bobDevice, bobDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, replyWire));

        // Simulate full process death: lock the vault and drop everything.
        // Only the device home (vault file + snapshot), the server views,
        // and the public Kyber triple (which production re-reads from the
        // server directory) cross the restart.
        Path bobHome = bob.dir();
        UUID bobUser = bob.user();
        SignalAdapter.KyberPrekeyPair bobKyberTriple = bob.kyber();
        CryptoTestFixtures.ClaimFake bobClaims = bob.claims();
        CryptoTestFixtures.SubmitFake bobSubmit = bob.submit();
        bob.vault().close();
        bob = null;

        // Fresh process: new vault, adapter, and store over the same home.
        VaultDevice bob2 = reopenDevice(bobHome, bobDevice, bobUser, 1002, bobClaims,
                bobSubmit, bobKyberTriple, testPassword());

        // Ratchet continuity on the pre-restart session, no privates needed.
        UUID msg2 = UUID.randomUUID();
        alice.service().sendToDevices(msg2, aliceDevice, bytes("two"),
                List.of(bob2.bundle(null)), Set.of());
        byte[] wire2 = alice.submit().batches().get(alice.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertArrayEquals(bytes("two"), bob2.service().decrypt(aliceDevice, aliceDevice,
                CryptoTypes.EnvelopeType.RATCHET, wire2));

        // Fresh inbound PREKEY_INIT after restart: Carol establishes with
        // Bob's spare OTPK; identity/signed/OTPK/Kyber all recover from vault.
        LibSignalAdapter carolAdapter = new LibSignalAdapter(1004);
        SignalAdapter.LocalIdentity carolId = carolAdapter.generateIdentity();
        SignalAdapter.EstablishedSession carolOut = carolAdapter.establishOutbound(
                carolId.identityPrivate(), bob2.bundle(bobSpare));
        SignalAdapter.EncryptResult carolWire =
                carolAdapter.encrypt(carolOut.sessionBlob(), bytes("carol-hi"));
        UUID carolDevice = UUID.randomUUID();
        assertArrayEquals(bytes("carol-hi"), bob2.service().decrypt(carolDevice, carolDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, carolWire.envelopeCiphertext()));
        assertTrue(bob2.stores().oneTimePrivate(101).isEmpty());

        // Fresh Bob-initiated establishment after restart, decrypted by Dave.
        SignalAdapter.OneTimePrekeyPair daveOtp = dave.issueOtpk(400);
        bob2.claims().pinBundle(daveDevice, dave.bundle(daveOtp));
        UUID msg3 = UUID.randomUUID();
        SamvaadCryptoService.FanoutResult r3 = bob2.service().sendToDevices(msg3, bobDevice,
                bytes("bob-to-dave"), List.of(dave.bundle(daveOtp)), Set.of());
        assertEquals(SamvaadCryptoService.DeviceOutcome.SENT, r3.outcomes().get(daveDevice));
        byte[] wire3 = bob2.submit().batches().get(bob2.submit().batches().size() - 1)
                .get(0).envelopeCiphertext();
        assertArrayEquals(bytes("bob-to-dave"), dave.service().decrypt(bobDevice, bobDevice,
                CryptoTypes.EnvelopeType.PREKEY_INIT, wire3));

        // Trust and slot contracts intact across the restart.
        assertEquals(CryptoTypes.TrustState.TRUSTED,
                bob2.stores().load(aliceDevice).orElseThrow().state());
        assertEquals(CryptoTypes.OutboundSlotState.ACKED,
                bob2.stores().loadSlot(msg3, daveDevice).orElseThrow().state());

        // The normal snapshot carries handle references only.
        String snapshot = readSnapshot(bobHome);
        assertTrue(snapshot.contains("\"identityHandle\""));
        assertTrue(snapshot.contains("\"signedHandle\""));
        String lower = snapshot.toLowerCase();
        assertTrue(!lower.contains("privatekey"));
        assertTrue(!lower.contains("privatebytes"));
        assertTrue(!lower.contains("secretkey"));
        assertTrue(!lower.contains("seed"));
        bob2.vault().close();
        alice.vault().close();
        dave.vault().close();
    }

    @Test
    void wrongVaultPasswordFailsClosed(@TempDir Path dir) {
        UUID device = UUID.randomUUID();
        VaultDevice bob = freshDevice(dir, device, UUID.randomUUID(), 1002, testPassword());
        bob.issueOtpk(100);
        bob.vault().close();

        assertThrows(PersistentStoreException.class, () -> FilePrivateKeyVault.open(
                dir, device, "wrong-test-only-password".toCharArray()));
    }

    private static String readSnapshot(Path home) {
        try {
            return Files.readString(home.resolve("client-crypto-store-v1.json"),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read snapshot", e);
        }
    }
}
