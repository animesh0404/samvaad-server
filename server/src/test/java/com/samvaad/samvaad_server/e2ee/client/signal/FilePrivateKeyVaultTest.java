package com.samvaad.samvaad_server.e2ee.client.signal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.samvaad.samvaad_server.e2ee.client.PersistentStoreException;

/**
 * Unit coverage for the password-based private-key vault: round-trips,
 * reopening, wrong-password/tamper/owner fail-closed behavior, removal,
 * kind indexing, and locking. Passwords below are test-only fixtures,
 * never real secrets.
 */
class FilePrivateKeyVaultTest {

    private static char[] password(String value) {
        return value.toCharArray();
    }

    @Test
    void storeLoadRemoveRoundTrip(@TempDir Path dir) {
        UUID owner = UUID.randomUUID();
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, owner, password("test-only-pw-1"));
        UUID idKey = UUID.randomUUID();
        UUID otpKey = UUID.randomUUID();
        byte[] identityBytes = new byte[64];
        byte[] otpkBytes = new byte[65];
        for (int i = 0; i < identityBytes.length; i++) {
            identityBytes[i] = (byte) i;
            otpkBytes[i % otpkBytes.length] = (byte) (i * 3 + 1);
        }

        vault.store(idKey, PrivateKeyVault.KeyKind.IDENTITY, identityBytes);
        vault.store(otpKey, PrivateKeyVault.KeyKind.OTPK, otpkBytes);

        PrivateKeyVault.SealedEntry identity = vault.load(idKey);
        assertEquals(PrivateKeyVault.KeyKind.IDENTITY, identity.kind());
        assertArrayEquals(identityBytes, identity.plaintext());
        assertEquals(Set.of(idKey), vault.handlesOfKind(PrivateKeyVault.KeyKind.IDENTITY));
        assertEquals(Set.of(otpKey), vault.handlesOfKind(PrivateKeyVault.KeyKind.OTPK));
        assertTrue(vault.handlesOfKind(PrivateKeyVault.KeyKind.KYBER).isEmpty());

        vault.remove(otpKey);
        assertNull(vault.load(otpKey));
        assertTrue(vault.handlesOfKind(PrivateKeyVault.KeyKind.OTPK).isEmpty());
        vault.remove(otpKey);

        // Reopen: identity survives, removal is durable.
        FilePrivateKeyVault reopened =
                FilePrivateKeyVault.open(dir, owner, password("test-only-pw-1"));
        assertArrayEquals(identityBytes, reopened.load(idKey).plaintext());
        assertNull(reopened.load(otpKey));
        reopened.close();
        vault.close();
    }

    @Test
    void wrongPasswordFailsClosedAtOpen(@TempDir Path dir) {
        UUID owner = UUID.randomUUID();
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, owner, password("correct-horse"));
        vault.store(UUID.randomUUID(), PrivateKeyVault.KeyKind.SIGNED, new byte[]{1, 2, 3});
        vault.close();

        assertThrows(PersistentStoreException.class,
                () -> FilePrivateKeyVault.open(dir, owner, password("wrong-horse")));
    }

    @Test
    void tamperedFileFailsClosed(@TempDir Path dir) throws Exception {
        UUID owner = UUID.randomUUID();
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, owner, password("tamper-test"));
        UUID handle = UUID.randomUUID();
        vault.store(handle, PrivateKeyVault.KeyKind.IDENTITY, new byte[48]);
        vault.close();

        Path file = dir.resolve("crypto-vault-v1.dat");
        byte[] original = Files.readAllBytes(file);
        byte[] tampered = original.clone();
        // Corrupt the header: open must refuse, never load partial state.
        tampered[20] ^= 0x01;
        Files.write(file, tampered);
        assertThrows(PersistentStoreException.class,
                () -> FilePrivateKeyVault.open(dir, owner, password("tamper-test")));

        // Restore, then corrupt the tail (entry ciphertext): open succeeds
        // (header intact) but the entry no longer opens.
        Files.write(file, original);
        byte[] tailTampered = Files.readAllBytes(file);
        tailTampered[tailTampered.length - 1] ^= 0x01;
        Files.write(file, tailTampered);
        FilePrivateKeyVault reopened =
                FilePrivateKeyVault.open(dir, owner, password("tamper-test"));
        assertThrows(PersistentStoreException.class, () -> reopened.load(handle));
        reopened.close();
    }

    @Test
    void foreignOwnerAndClosedVaultFailClosed(@TempDir Path dir) {
        UUID owner = UUID.randomUUID();
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, owner, password("owner-test"));
        vault.store(UUID.randomUUID(), PrivateKeyVault.KeyKind.KYBER, new byte[]{9});
        vault.close();

        assertThrows(PersistentStoreException.class,
                () -> FilePrivateKeyVault.open(dir, UUID.randomUUID(), password("owner-test")));

        FilePrivateKeyVault reopened =
                FilePrivateKeyVault.open(dir, owner, password("owner-test"));
        reopened.close();
        UUID handle = UUID.randomUUID();
        assertThrows(PersistentStoreException.class,
                () -> reopened.store(handle, PrivateKeyVault.KeyKind.OTPK, new byte[]{1}));
        assertThrows(PersistentStoreException.class, () -> reopened.load(handle));
        assertThrows(PersistentStoreException.class, () -> reopened.remove(handle));
        assertThrows(PersistentStoreException.class,
                () -> reopened.handlesOfKind(PrivateKeyVault.KeyKind.OTPK));
    }

    @Test
    void unknownHandleLoadsNullAndNilHandleRejected(@TempDir Path dir) {
        UUID owner = UUID.randomUUID();
        FilePrivateKeyVault vault = FilePrivateKeyVault.open(dir, owner, password("nil-test"));
        assertNull(vault.load(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> vault.store(
                new UUID(0L, 0L), PrivateKeyVault.KeyKind.IDENTITY, new byte[]{1}));
        vault.close();
    }
}
