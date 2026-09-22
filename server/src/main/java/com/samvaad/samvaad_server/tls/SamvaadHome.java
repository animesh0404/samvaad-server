package com.samvaad.samvaad_server.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Resolves Samvaad's canonical per-user deployment/configuration directory and
 * the TLS state directory inside it (ADR 0017).
 *
 * <ul>
 *   <li>Standalone: {@code ~/.samvaad} (from {@code user.home}).</li>
 *   <li>Docker: {@code SAMVAAD_CONFIG_DIR} (for example {@code /config} backed
 *   by a named volume).</li>
 * </ul>
 *
 * <p>TLS state lives in {@code SAMVAAD_TLS_DIR} when set (for example
 * {@code /tls} backed by the dedicated {@code samvaad-tls} volume), otherwise
 * in {@code <configDir>/tls}. TLS material is therefore never inside the
 * application JAR, the image, or the PostgreSQL volume.
 */
public final class SamvaadHome {

    /** Environment variable overriding the configuration directory (Docker). */
    public static final String CONFIG_DIR_ENV = "SAMVAAD_CONFIG_DIR";

    /** Environment variable overriding the TLS state directory (Docker). */
    public static final String TLS_DIR_ENV = "SAMVAAD_TLS_DIR";

    /** Keystore file name inside the TLS directory. */
    public static final String KEYSTORE_FILE_NAME = "keystore.p12";

    private final Path configDir;
    private final Path tlsDir;

    private SamvaadHome(Path configDir, Path tlsDir) {
        this.configDir = configDir;
        this.tlsDir = tlsDir;
    }

    public Path configDir() {
        return configDir;
    }

    public Path tlsDir() {
        return tlsDir;
    }

    public Path keystorePath() {
        return tlsDir.resolve(KEYSTORE_FILE_NAME);
    }

    /** Resolves from the real process environment. */
    public static SamvaadHome resolve() {
        return resolve(System.getenv());
    }

    /** Resolves from an explicit environment map (testability seam). */
    public static SamvaadHome resolve(Map<String, String> environment) {
        String configDirValue = trimToNull(environment.get(CONFIG_DIR_ENV));
        Path configDir = configDirValue != null
                ? Paths.get(configDirValue)
                : Paths.get(System.getProperty("user.home"), ".samvaad");
        String tlsDirValue = trimToNull(environment.get(TLS_DIR_ENV));
        Path tlsDir = tlsDirValue != null ? Paths.get(tlsDirValue) : configDir.resolve("tls");
        return new SamvaadHome(configDir.toAbsolutePath().normalize(), tlsDir.toAbsolutePath().normalize());
    }

    /**
     * Creates the configuration and TLS directories when missing. Existing
     * directories and their contents are never modified. Restrictive
     * permissions are applied where the platform supports POSIX attributes.
     */
    public void ensureDirectories() {
        createDirectory(configDir);
        createDirectory(tlsDir);
    }

    private static void createDirectory(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                return;
            }
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX platforms (Windows): default ACLs apply.
            }
        } catch (IOException e) {
            throw new TlsBootstrapException(
                    "Could not create Samvaad directory " + dir + ": " + e.getMessage(), e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
