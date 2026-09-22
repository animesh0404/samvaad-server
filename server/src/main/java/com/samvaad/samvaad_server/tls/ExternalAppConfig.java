package com.samvaad.samvaad_server.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Owns the operator-owned external {@code application.yaml} (ADR 0017).
 *
 * <p>The file is created once with safe non-secret defaults when missing and
 * never overwritten, regenerated, or merged afterwards: packaged defaults
 * changing in a later release must not clobber operator configuration. No
 * secrets are ever written here; secrets live in {@code .env}.
 *
 * <p>YAML is read through Spring's own {@link YamlPropertySourceLoader}, not
 * a manual parser.
 */
public final class ExternalAppConfig {

    /** External configuration file name inside the config directory. */
    public static final String FILE_NAME = "application.yaml";

    /** Property key for the explicitly configured TLS SAN list. */
    public static final String SANS_KEY = "samvaad.tls.sans";

    /** Default SANs used when the operator configures none. */
    public static final List<String> DEFAULT_SANS = List.of("DNS:localhost", "IP:127.0.0.1");

    /** Default operator configuration written exactly once on first boot. */
    static final String DEFAULT_YAML = """
            # Samvaad operator-owned deployment configuration.
            # Created automatically on first start; never overwritten afterwards.
            # Secrets belong in .env and must never be written here.
            server:
              port: 8080
              ssl:
                enabled: true
                key-store-type: PKCS12
                key-alias: samvaad
                # Resolved from the environment at startup (a secret; the
                # keystore path itself is computed by TLS bootstrap).
                key-store-password: ${SAMVAAD_TLS_KEYSTORE_PASSWORD}
            samvaad:
              tls:
                # Explicitly configured certificate SANs, honoured only when
                # the keystore is (re)generated. Changing this list later does
                # not replace the existing certificate.
                sans:
                  - "DNS:localhost"
                  - "IP:127.0.0.1"
            """;

    private ExternalAppConfig() {
    }

    /**
     * Returns the external configuration path, creating it with defaults when
     * absent. An existing non-empty file is returned untouched. An existing
     * <em>empty</em> file (as created by installers that only ensure presence)
     * is populated with defaults exactly once: it carries no operator content
     * to preserve. A concurrent creator winning the race is adopted, never
     * clobbered.
     */
    public static Path ensurePresent(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(configDir);
            if (Files.isDirectory(file)) {
                // Typically a Docker bind mount auto-created as a directory
                // because the host file did not exist. Never delete or
                // replace it automatically.
                throw new TlsBootstrapException(
                        "External configuration path " + file
                                + " is a directory, not a file (Docker creates a directory"
                                + " when bind-mounting a missing file). Remove the directory so"
                                + " Samvaad can create the configuration file, then restart.");
            }
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                return file;
            }
            if (Files.isRegularFile(file)) {
                Files.writeString(
                        file, DEFAULT_YAML, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                Files.writeString(
                        file, DEFAULT_YAML, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX platforms (Windows): default ACLs apply.
            }
            return file;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // A concurrent first boot created it first: use that file as-is.
            return file;
        } catch (IOException e) {
            throw new TlsBootstrapException(
                    "Could not create external configuration at " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads the configured TLS SAN list, falling back to {@link #DEFAULT_SANS}
     * only when the file carries no SAN configuration at all. List entries
     * are read through their indexed flattened keys
     * ({@code samvaad.tls.sans[0]}, …), which is how Spring's YAML loader
     * exposes collections. An explicitly present but unusable SAN value
     * fails instead of silently falling back, so operators never get a
     * different certificate than they configured.
     *
     * @throws TlsBootstrapException when the file is malformed
     */
    public static List<String> readSans(Path file) {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("samvaad-external", new FileSystemResource(file));
            if (sources == null || sources.isEmpty()) {
                return DEFAULT_SANS;
            }
            PropertySource<?> source = sources.get(0);
            List<String> sans = new ArrayList<>();
            Object single = source.getProperty(SANS_KEY);
            if (single != null && !(single instanceof String)) {
                throw new TlsBootstrapException(
                        "External configuration at " + file + ": '" + SANS_KEY
                                + "' must be a string or a list of DNS:<name>/IP:<address> entries.");
            }
            if (single instanceof String text && !text.isBlank()) {
                sans.add(text.trim());
            }
            for (int index = 0; ; index++) {
                Object item = source.getProperty(SANS_KEY + "[" + index + "]");
                if (item == null) {
                    break;
                }
                if (!(item instanceof String entry) || entry.isBlank()) {
                    throw new TlsBootstrapException(
                            "External configuration at " + file + ": '" + SANS_KEY
                                    + "' entries must be DNS:<name>/IP:<address> strings.");
                }
                sans.add(entry.trim());
            }
            if (sans.isEmpty() && sansConfigured(source)) {
                throw new TlsBootstrapException(
                        "External configuration at " + file + ": '" + SANS_KEY
                                + "' is present but produced no usable entries."
                                + " Use DNS:<name>/IP:<address> strings or remove the key for defaults.");
            }
            return sans.isEmpty() ? DEFAULT_SANS : List.copyOf(sans);
        } catch (TlsBootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsBootstrapException(
                    "External configuration at " + file + " is malformed: " + e.getMessage()
                            + ". Fix the file or delete it to regenerate defaults (operator-owned state).",
                    e);
        }
    }

    /**
     * True when any flattened property key belongs to the SAN setting (a map
     * form such as {@code sans: {foo: bar}} flattens to dotted keys rather
     * than indexed entries).
     */
    private static boolean sansConfigured(PropertySource<?> source) {
        if (!(source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)) {
            return false;
        }
        for (String name : enumerable.getPropertyNames()) {
            if (name.equals(SANS_KEY) || name.startsWith(SANS_KEY + "[") || name.startsWith(SANS_KEY + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Test-only view of the defaults template (no filesystem access). */
    static String defaultYaml() {
        return DEFAULT_YAML;
    }
}
