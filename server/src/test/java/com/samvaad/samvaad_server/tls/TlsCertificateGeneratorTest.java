package com.samvaad.samvaad_server.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for self-signed TLS identity generation and inspection.
 */
class TlsCertificateGeneratorTest {

    private static final char[] PASSWORD = "test-keystore-password".toCharArray();

    @TempDir
    Path tempDir;

    @Test
    void firstRunGenerationCreatesValidPkcs12() throws Exception {
        Path store = tempDir.resolve("keystore.p12");

        TlsCertificateGenerator.CertificateInfo info = TlsCertificateGenerator.generate(
                store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("DNS:localhost", "IP:127.0.0.1"), 3650));

        assertTrue(Files.isRegularFile(store));
        // Loads through the standard Java KeyStore APIs as PKCS#12.
        KeyStore loaded = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            loaded.load(in, PASSWORD);
        }
        assertTrue(loaded.isKeyEntry(TlsCertificateGenerator.ALIAS));
        assertEquals(TlsCertificateGenerator.ALIAS, info.alias());
    }

    @Test
    void generatedIdentityCarriesExpectedProperties() {
        Path store = tempDir.resolve("keystore.p12");

        TlsCertificateGenerator.CertificateInfo info = TlsCertificateGenerator.generate(
                store,
                PASSWORD,
                new TlsCertificateGenerator.Spec(
                        List.of("DNS:localhost", "IP:127.0.0.1", "IP:192.168.1.50", "DNS:samvaad.local"),
                        TlsCertificateGenerator.VALIDITY_DAYS));

        assertTrue(info.subject().contains("CN=Samvaad"), "subject: " + info.subject());
        assertEquals("SHA256withRSA", info.signatureAlgorithm());
        assertEquals(2048, info.keySizeBits());        assertTrue(info.sans().contains("DNS:localhost"), "sans: " + info.sans());
        assertTrue(info.sans().contains("IP:127.0.0.1"), "sans: " + info.sans());
        assertTrue(info.sans().contains("IP:192.168.1.50"), "sans: " + info.sans());
        assertTrue(info.sans().contains("DNS:samvaad.local"), "sans: " + info.sans());
        assertEquals(
                TlsCertificateGenerator.VALIDITY_DAYS,
                Duration.between(info.notBefore(), info.notAfter()).toDays());
        assertTrue(info.sha256Fingerprint().startsWith("SHA256:"));
        assertEquals(7 + 32 * 3 - 1, info.sha256Fingerprint().length());
    }

    @Test
    void invalidSanEntriesAreRejected() {
        Path store = tempDir.resolve("keystore.p12");

        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("BOGUS:x"), 3650)));
        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("IP:not-an-ip"), 3650)));
        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, PASSWORD, new TlsCertificateGenerator.Spec(List.of(), 3650)));
    }

    @Test
    void emptyPasswordIsRejected() {
        Path store = tempDir.resolve("keystore.p12");

        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, new char[0], new TlsCertificateGenerator.Spec(List.of("DNS:localhost"), 3650)));
    }

    @Test
    void generatedSerialNumberIsStrictlyPositive() throws Exception {
        // RFC 5280 requires positive serials; repeat to cover randomness.
        for (int i = 0; i < 5; i++) {
            Path store = tempDir.resolve("keystore-" + i + ".p12");
            TlsCertificateGenerator.generate(
                    store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("DNS:localhost"), 3650));
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(store)) {
                loaded.load(in, PASSWORD);
            }
            java.security.cert.X509Certificate certificate =
                    (java.security.cert.X509Certificate) loaded.getCertificate(TlsCertificateGenerator.ALIAS);
            assertTrue(
                    certificate.getSerialNumber().signum() > 0,
                    "serial must be positive: " + certificate.getSerialNumber());
        }
    }

    @Test
    void ipSanMustBeNumericLiteralWithoutDnsResolution() {
        Path store = tempDir.resolve("keystore.p12");

        // Hostname-shaped values must be rejected before any lookup.
        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("IP:db"), 3650)));
        assertThrows(
                TlsBootstrapException.class,
                () -> TlsCertificateGenerator.generate(
                        store, PASSWORD, new TlsCertificateGenerator.Spec(List.of("IP:999.1.1.1"), 3650)));
        assertTrue(TlsCertificateGenerator.isNumericIpLiteral("127.0.0.1"));
        assertTrue(TlsCertificateGenerator.isNumericIpLiteral("192.168.1.50"));
        assertTrue(TlsCertificateGenerator.isNumericIpLiteral("::1"));
        assertTrue(!TlsCertificateGenerator.isNumericIpLiteral("db"));
        assertTrue(!TlsCertificateGenerator.isNumericIpLiteral(""));
    }
}
