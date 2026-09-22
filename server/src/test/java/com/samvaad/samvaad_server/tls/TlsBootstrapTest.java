package com.samvaad.samvaad_server.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for TLS bootstrap orchestration: preservation, failure policy,
 * atomicity, concurrency, and external configuration handling.
 */
class TlsBootstrapTest {

    private static final char[] PASSWORD = "test-keystore-password".toCharArray();
    private static final char[] WRONG_PASSWORD = "wrong-password".toCharArray();

    @TempDir
    Path tempDir;

    private SamvaadHome homeIn(Path root) {
        Map<String, String> env = new HashMap<>();
        env.put(SamvaadHome.CONFIG_DIR_ENV, root.resolve("home").toString());
        return SamvaadHome.resolve(env);
    }

    @Test
    void existingValidKeystoreIsPreservedByteIdentical() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        TlsBootstrap.ensureReady(home, PASSWORD);
        Path store = home.keystorePath();
        byte[] before = Files.readAllBytes(store);

        TlsBootstrap.TlsReady second = TlsBootstrap.ensureReady(home, PASSWORD);

        assertArrayEquals(before, Files.readAllBytes(store));
        assertEquals(
                TlsCertificateGenerator.inspect(store, PASSWORD).sha256Fingerprint(),
                second.identity().sha256Fingerprint());
    }

    @Test
    void expiredCertificateFailsStartupWithoutRegenerating() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        // Lay down an already-expired store directly: generate() itself
        // validates and would refuse to create it.
        TlsCertificateGenerator.writeStore(
                home.keystorePath(),
                PASSWORD,
                new TlsCertificateGenerator.Spec(List.of("DNS:localhost"), -10),
                java.time.Instant.now());
        byte[] before = Files.readAllBytes(home.keystorePath());

        TlsBootstrapException failure =
                assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));

        assertTrue(failure.getMessage().toLowerCase().contains("expired"));
        assertArrayEquals(before, Files.readAllBytes(home.keystorePath()));
    }

    @Test
    void malformedKeystoreFailsStartup() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Files.write(home.keystorePath(), "not-a-keystore".getBytes(StandardCharsets.UTF_8));

        assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));
    }

    @Test
    void wrongPasswordFailsStartup() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        TlsBootstrap.ensureReady(home, PASSWORD);

        TlsBootstrapException failure =
                assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, WRONG_PASSWORD));

        assertTrue(
                failure.getMessage().toLowerCase().contains("password")
                        || failure.getMessage().toLowerCase().contains("corrupt"));
    }

    @Test
    void missingAliasFailsStartup() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, PASSWORD);
        store.setKeyEntry(
                "other",
                testKey(home),
                PASSWORD,
                new java.security.cert.Certificate[] {testCertificate(home)});
        try (var out = Files.newOutputStream(home.keystorePath())) {
            store.store(out, PASSWORD);
        }

        TlsBootstrapException failure =
                assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));

        assertTrue(failure.getMessage().contains("samvaad"));
    }

    @Test
    void missingPasswordFailsFastWithActionableMessage() {
        SamvaadHome home = homeIn(tempDir);

        TlsBootstrapException failure =
                assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, new char[0]));

        assertTrue(failure.getMessage().contains("SAMVAAD_TLS_KEYSTORE_PASSWORD"));
    }

    @Test
    void concurrentFirstStartupProducesSingleIdentity() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> TlsBootstrap.ensureReady(home, PASSWORD).identity().sha256Fingerprint());
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            String first = futures.get(0).get();
            for (Future<String> future : futures) {
                assertEquals(first, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(Files.isRegularFile(home.keystorePath()));
        try (Stream<Path> files = Files.list(home.tlsDir())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "no partial temporary stores may remain");
        }
        // The single surviving identity is valid.
        TlsCertificateGenerator.inspect(home.keystorePath(), PASSWORD);
    }

    @Test
    void missingExternalConfigCreatesDefaults() throws Exception {
        SamvaadHome home = homeIn(tempDir);

        TlsBootstrap.ensureReady(home, PASSWORD);

        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        assertTrue(Files.isRegularFile(appYaml));
        String content = Files.readString(appYaml, StandardCharsets.UTF_8);
        assertTrue(content.contains("DNS:localhost"));
        assertTrue(content.contains("IP:127.0.0.1"));
        assertTrue(content.contains("server:"));
        // No secret values in the generated operator configuration: the
        // keystore password appears only as an environment reference.
        assertTrue(content.contains("${SAMVAAD_TLS_KEYSTORE_PASSWORD}"));
    }

    @Test
    void existingExternalConfigIsNeverOverwritten() throws Exception {        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        String custom = "server:\n  port: 8080\nsamvaad:\n  tls:\n    sans:\n      - \"DNS:custom.local\"\n";
        Files.writeString(appYaml, custom, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(appYaml);

        TlsBootstrap.ensureReady(home, PASSWORD);

        assertArrayEquals(before, Files.readAllBytes(appYaml));
    }

    @Test
    void emptyExternalConfigIsPopulatedOnceWithDefaults() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        Files.writeString(appYaml, "", StandardCharsets.UTF_8);

        TlsBootstrap.ensureReady(home, PASSWORD);

        String content = Files.readString(appYaml, StandardCharsets.UTF_8);
        assertTrue(content.contains("DNS:localhost"));
        assertTrue(content.contains("server:"));
    }

    @Test
    void existingCustomSansArePreservedAndLoaded() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        Files.writeString(
                appYaml,
                "samvaad:\n  tls:\n    sans:\n      - \"DNS:localhost\"\n      - \"IP:127.0.0.1\"\n"
                        + "      - \"DNS:custom.local\"\n      - \"IP:192.168.1.50\"\n",
                StandardCharsets.UTF_8);

        TlsBootstrap.TlsReady ready = TlsBootstrap.ensureReady(home, PASSWORD);

        assertTrue(ready.identity().sans().contains("DNS:custom.local"));
        assertTrue(ready.identity().sans().contains("IP:192.168.1.50"));
    }

    @Test
    void malformedExternalConfigFailsFast() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        Files.writeString(appYaml, "samvaad:\n  tls: [unclosed\n", StandardCharsets.UTF_8);

        assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));
    }

    @Test
    void applicationYamlDirectoryFailsWithActionableMessage() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        Files.createDirectory(appYaml);

        TlsBootstrapException failure =
                assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));

        assertTrue(failure.getMessage().toLowerCase().contains("directory"));
    }

    @Test
    void nonStringScalarSansFailInsteadOfFallingBack() throws Exception {
        SamvaadHome home = homeIn(tempDir);
        home.ensureDirectories();
        Path appYaml = home.configDir().resolve(ExternalAppConfig.FILE_NAME);
        Files.writeString(appYaml, "samvaad:\n  tls:\n    sans: 123\n", StandardCharsets.UTF_8);

        assertThrows(TlsBootstrapException.class, () -> TlsBootstrap.ensureReady(home, PASSWORD));
    }

    @Test
    void homeResolutionPrefersExplicitEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(SamvaadHome.CONFIG_DIR_ENV, tempDir.resolve("cfg").toString());
        env.put(SamvaadHome.TLS_DIR_ENV, tempDir.resolve("tlsvol").toString());

        SamvaadHome home = SamvaadHome.resolve(env);

        assertEquals(tempDir.resolve("cfg"), home.configDir());
        assertEquals(tempDir.resolve("tlsvol"), home.tlsDir());
        assertEquals(tempDir.resolve("tlsvol").resolve("keystore.p12"), home.keystorePath());
    }

    private java.security.PrivateKey testKey(SamvaadHome home) throws Exception {
        TlsBootstrap.ensureReady(home, PASSWORD);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(home.keystorePath())) {
            store.load(in, PASSWORD);
        }
        return (java.security.PrivateKey) store.getKey(TlsCertificateGenerator.ALIAS, PASSWORD);
    }

    private java.security.cert.Certificate testCertificate(SamvaadHome home) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(home.keystorePath())) {
            store.load(in, PASSWORD);
        }
        return store.getCertificate(TlsCertificateGenerator.ALIAS);
    }
}
