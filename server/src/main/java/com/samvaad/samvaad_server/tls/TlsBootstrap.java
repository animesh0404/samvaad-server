package com.samvaad.samvaad_server.tls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates TLS readiness before the embedded servlet container is created
 * (ADR 0017):
 *
 * <ol>
 *   <li>Resolve the canonical home ({@link SamvaadHome}).</li>
 *   <li>Ensure the operator-owned external {@code application.yaml} exists
 *   (created with defaults once, never overwritten).</li>
 *   <li>Ensure the TLS keystore exists and is valid: generate on first boot
 *   only; reuse a valid store; fail startup on expired, malformed,
 *   wrong-password, or alias-missing stores instead of replacing them.</li>
 *   <li>Contribute the computed SSL properties to Spring Boot.</li>
 * </ol>
 */
public final class TlsBootstrap {

    /** Environment variable carrying the TLS keystore password (a secret). */
    public static final String KEYSTORE_PASSWORD_ENV = "SAMVAAD_TLS_KEYSTORE_PASSWORD";

    private TlsBootstrap() {
    }

    /** Outcome of a successful bootstrap: Spring properties plus identity view. */
    public record TlsReady(Map<String, Object> springProperties,
            TlsCertificateGenerator.CertificateInfo identity) {
    }

    /**
     * Runs the full pre-Spring bootstrap against the real environment.
     *
     * @return Spring Boot properties and the active TLS identity (fingerprint
     *     only; no secrets)
     * @throws TlsBootstrapException when TLS cannot be made ready; startup
     *     must abort
     */
    public static TlsReady ensureReady() {
        return ensureReady(SamvaadHome.resolve(), readPassword(System.getenv()));
    }

    /**
     * Runs the bootstrap against explicit inputs (testability seam).
     *
     * @param home resolved canonical directories (created when missing)
     * @param keystorePassword TLS keystore password; required, never logged
     */
    public static TlsReady ensureReady(SamvaadHome home, char[] keystorePassword) {
        requirePassword(keystorePassword);
        home.ensureDirectories();
        Path appYaml = ExternalAppConfig.ensurePresent(home.configDir());
        List<String> sans = ExternalAppConfig.readSans(appYaml);
        return ensureReady(home, sans, keystorePassword);
    }

    static TlsReady ensureReady(SamvaadHome home, List<String> sans, char[] keystorePassword) {
        requirePassword(keystorePassword);
        home.ensureDirectories();
        Path keystorePath = home.keystorePath();
        // Serialize first boot across processes AND threads: exactly one
        // winner generates; losers adopt the validated winner. tryLock()
        // returns null when another process holds the lock, while same-JVM
        // contention surfaces as OverlappingFileLockException; both back off
        // and retry so no startup fails spuriously.
        Path lockFile = home.tlsDir().resolve("keystore.lock");
        for (int attempt = 0; attempt < 100; attempt++) {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    lockFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock ignored = channel.tryLock()) {
                try {
                    java.nio.file.Files.setPosixFilePermissions(
                            lockFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignoredLockPerms) {
                    // Non-POSIX platforms (Windows): default ACLs apply.
                }
                if (ignored == null) {
                    backoff(attempt);
                    continue;
                }
                TlsCertificateGenerator.CertificateInfo identity;
                if (Files.isRegularFile(keystorePath)) {
                    identity = TlsCertificateGenerator.inspect(keystorePath, keystorePassword);
                    System.out.println("[samvaad] Reusing TLS identity " + identity.sha256Fingerprint());
                } else {
                    identity = TlsCertificateGenerator.generate(
                            keystorePath,
                            keystorePassword,
                            new TlsCertificateGenerator.Spec(sans, TlsCertificateGenerator.VALIDITY_DAYS));
                    System.out.println("[samvaad] Generated new TLS identity " + identity.sha256Fingerprint());
                }
                Map<String, Object> properties = new HashMap<>();
                properties.put("spring.config.additional-location", home.configDir().toUri().toString());
                properties.put("server.ssl.enabled", "true");
                properties.put("server.ssl.key-store", "file:" + keystorePath.toAbsolutePath());
                properties.put("server.ssl.key-store-password", new String(keystorePassword));
                properties.put("server.ssl.key-store-type", "PKCS12");
                properties.put("server.ssl.key-alias", TlsCertificateGenerator.ALIAS);
                return new TlsReady(Map.copyOf(properties), identity);
            } catch (java.nio.channels.OverlappingFileLockException e) {
                backoff(attempt);
            } catch (TlsBootstrapException e) {
                throw e;
            } catch (Exception e) {
                throw new TlsBootstrapException(
                        "TLS bootstrap failed: " + e.getMessage(), e);
            }
        }
        throw new TlsBootstrapException(
                "Timed out waiting for the TLS identity lock. Another process may be generating it;");
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(100 + (long) attempt * 50, 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TlsBootstrapException("TLS bootstrap interrupted while waiting for the identity lock.", e);
        }
    }

    static char[] readPassword(Map<String, String> environment) {
        String value = environment.get(KEYSTORE_PASSWORD_ENV);
        if (value == null || value.isEmpty()) {
            throw new TlsBootstrapException(
                    "TLS keystore password is not set. Set " + KEYSTORE_PASSWORD_ENV
                            + " (generated by scripts/install.sh, scripts/start.sh, or the Windows installer)"
                            + " and restart. The password is never generated implicitly at runtime.");
        }
        return value.toCharArray();
    }

    private static void requirePassword(char[] keystorePassword) {
        if (keystorePassword == null || keystorePassword.length == 0) {
            throw new TlsBootstrapException(
                    "TLS keystore password is not set. Set " + KEYSTORE_PASSWORD_ENV + " and restart.");
        }
    }
}
