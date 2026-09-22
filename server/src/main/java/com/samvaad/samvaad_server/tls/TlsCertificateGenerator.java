package com.samvaad.samvaad_server.tls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates and inspects self-signed PKCS#12 TLS identities for
 * application-managed HTTPS (ADR 0017).
 *
 * <p>Generation uses Bouncy Castle directly. The JDK exposes no supported
 * public API for issuing X.509 certificates ({@code sun.security.x509} is
 * encapsulated on Java 25), and runtime certificate generation must not
 * depend on an external {@code keytool} binary.
 */
public final class TlsCertificateGenerator {

    /** Keystore entry alias used for the Samvaad TLS identity. */
    public static final String ALIAS = "samvaad";

    /** RSA key size in bits. */
    public static final int KEY_SIZE_BITS = 2048;

    /** Signature algorithm. */
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** Self-signed certificate validity in days (approximately 10 years). */
    public static final int VALIDITY_DAYS = 3650;

    /** Self-signed certificate subject. */
    public static final String SUBJECT_DN = "CN=Samvaad";

    private TlsCertificateGenerator() {
    }

    /** Parameters for a self-signed certificate generation. */
    public record Spec(List<String> sans, int validityDays) {
        public Spec {
            sans = List.copyOf(sans);
        }
    }

    /** Verified view of a persisted TLS identity. Never contains secrets. */
    public record CertificateInfo(
            String alias,
            String subject,
            String signatureAlgorithm,
            int keySizeBits,
            List<String> sans,
            Instant notBefore,
            Instant notAfter,
            String sha256Fingerprint) {
    }

    /**
     * Generates a new self-signed PKCS#12 identity atomically: the store is
     * written to a temporary sibling file, validated by re-loading it, then
     * moved into place. If another process wins a concurrent first boot
     * (target already exists), the temporary file is discarded and the
     * existing identity is adopted after validation, so concurrent startups
     * never produce competing identities.
     *
     * @param keystorePath final keystore location (parent must exist)
     * @param password keystore/key password (same password for both)
     * @param spec certificate parameters
     * @return verified view of the persisted identity
     * @throws TlsBootstrapException when generation or validation fails
     */
    public static CertificateInfo generate(Path keystorePath, char[] password, Spec spec) {
        return generate(keystorePath, password, spec, Instant.now());
    }

    static CertificateInfo generate(Path keystorePath, char[] password, Spec spec, Instant now) {
        requirePassword(password);
        if (spec.sans().isEmpty()) {
            throw new TlsBootstrapException("TLS certificate requires at least one SAN entry");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(
                    keystorePath.getParent(), keystorePath.getFileName().toString() + ".", ".tmp");
            writeStore(temp, password, spec, now);
            // Validate exactly what was written before exposing it.
            inspect(temp, password);
            try {
                Files.move(temp, keystorePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, keystorePath);
            }
            temp = null;
            // Enforce owner-only access explicitly rather than relying on
            // temporary-file creation defaults.
            try {
                Files.setPosixFilePermissions(
                        keystorePath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX platforms (Windows): default ACLs apply.
            }
            return inspect(keystorePath, password);
        } catch (TlsBootstrapException e) {
            throw e;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // A concurrent first boot won: adopt the existing identity after
            // validation instead of replacing it.
            return inspect(keystorePath, password);
        } catch (Exception e) {
            throw new TlsBootstrapException(
                    "Failed to generate TLS keystore at " + keystorePath + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort cleanup only; the temp name is unique and
                    // never referenced as the active identity.
                }
            }
        }
    }

    /**
     * Loads and validates an existing keystore without modifying it.
     *
     * @throws TlsBootstrapException with a specific reason when the store is
     *     missing, malformed, password-protected differently, expired,
     *     missing the expected alias/key, or otherwise unusable
     */
    public static CertificateInfo inspect(Path keystorePath, char[] password) {
        requirePassword(password);
        if (!Files.isRegularFile(keystorePath)) {
            throw new TlsBootstrapException(
                    "TLS keystore does not exist: " + keystorePath
                            + ". Delete nothing; create it via a fresh first boot or explicit regeneration.");
        }
        try (InputStream in = Files.newInputStream(keystorePath, StandardOpenOption.READ)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            if (!store.isKeyEntry(ALIAS)) {
                throw new TlsBootstrapException(
                        "TLS keystore at " + keystorePath
                                + " has no key entry for alias '" + ALIAS + "'. Explicit regeneration is required.");
            }
            KeyStore.Entry entry = store.getEntry(ALIAS, new KeyStore.PasswordProtection(password));
            if (!(entry instanceof KeyStore.PrivateKeyEntry keyEntry)) {
                throw new TlsBootstrapException(
                        "TLS keystore alias '" + ALIAS + "' holds no private key. Explicit regeneration is required.");
            }
            Certificate[] chain = keyEntry.getCertificateChain();
            if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate cert)) {
                throw new TlsBootstrapException(
                        "TLS keystore alias '" + ALIAS + "' holds no X.509 certificate. Explicit regeneration is required.");
            }
            try {
                cert.checkValidity();
            } catch (Exception e) {
                throw new TlsBootstrapException(
                        "TLS certificate in " + keystorePath
                                + " is expired or not yet valid. It will NOT be replaced automatically;"
                                + " regenerate explicitly (delete the keystore and restart).",
                        e);
            }
            return new CertificateInfo(
                    ALIAS,
                    cert.getSubjectX500Principal().getName(),
                    cert.getSigAlgName(),
                    keySizeBits(keyEntry.getPrivateKey()),
                    sansOf(cert),
                    cert.getNotBefore().toInstant(),
                    cert.getNotAfter().toInstant(),
                    fingerprint(cert));
        } catch (TlsBootstrapException e) {
            throw e;
        } catch (IOException e) {
            // Language-independent classification: any failure to open the
            // store means wrong password or a corrupted store. Never attempt
            // repair; never regenerate. (Deliberately not sniffing localized
            // JDK messages to distinguish the two.)
            throw new TlsBootstrapException(
                    "TLS keystore at " + keystorePath
                            + " could not be opened: wrong password or corrupted store."
                            + " It will NOT be repaired automatically.",
                    e);
        } catch (Exception e) {
            throw new TlsBootstrapException(
                    "TLS keystore at " + keystorePath + " is unusable: " + e.getMessage(), e);
        }
    }

    // Package-private for fixture setup in tests (writes without validating,
    // so expired/malformed fixtures can be laid down deliberately).
    static void writeStore(Path path, char[] password, Spec spec, Instant now) throws Exception {        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(KEY_SIZE_BITS, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Name subject = new X500Name(SUBJECT_DN);
        // Strictly positive 63-bit serial (RFC 5280 forbids negative serials;
        // BigInteger(64, ...) would be negative half the time).
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        // Backdate slightly so modest client/server clock skew never makes a
        // fresh certificate appear "not yet valid".
        Date notBefore = Date.from(now.minus(Duration.ofHours(1)));
        Date notAfter = Date.from(now.plus(Duration.ofDays(spec.validityDays())));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(
                Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.subjectAlternativeName, false, generalNames(spec.sans()));

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
        X509Certificate certificate =
                new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry(ALIAS, keyPair.getPrivate(), password, new Certificate[] {certificate});
        try (OutputStream out =
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            store.store(out, password);
        }
    }

    private static GeneralNames generalNames(List<String> sans) {
        List<GeneralName> names = new ArrayList<>(sans.size());
        for (String san : sans) {
            int colon = san.indexOf(':');
            if (colon <= 0 || colon == san.length() - 1) {
                throw new TlsBootstrapException(
                        "Invalid TLS SAN entry '" + san + "'. Expected DNS:<name> or IP:<address>.");
            }
            String type = san.substring(0, colon).toUpperCase(Locale.ROOT);
            String value = san.substring(colon + 1);
            switch (type) {
                case "DNS" -> {
                    if (value.isBlank()) {
                        throw new TlsBootstrapException("Invalid TLS SAN entry '" + san + "': empty DNS name.");
                    }
                    names.add(new GeneralName(GeneralName.dNSName, value));
                }
                case "IP" -> {
                    // Numeric IP literals only. InetAddress.getByName would
                    // otherwise resolve hostnames via DNS and silently bake
                    // the resolved address into the certificate.
                    if (!isNumericIpLiteral(value)) {
                        throw new TlsBootstrapException(
                                "Invalid TLS SAN entry '" + san + "': not a numeric IP literal.");
                    }
                    try {
                        names.add(new GeneralName(
                                GeneralName.iPAddress, java.net.InetAddress.getByName(value).getHostAddress()));
                    } catch (Exception e) {
                        throw new TlsBootstrapException(
                                "Invalid TLS SAN entry '" + san + "': not a valid IP address.", e);
                    }
                }
                default -> throw new TlsBootstrapException(
                        "Invalid TLS SAN entry '" + san + "'. Expected DNS:<name> or IP:<address>.");
            }
        }
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    /**
     * True for numeric IPv4 dotted quads and IPv6 literals (with optional
     * zone id), without performing any DNS resolution.
     */
    static boolean isNumericIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.contains(":")) {
            return value.matches("[0-9a-fA-F:.%]+") && value.replace(":", "").matches(".*[0-9a-fA-F].*");
        }
        String[] groups = value.split("\\.", -1);
        if (groups.length != 4) {
            return false;
        }
        for (String group : groups) {
            if (!group.matches("[0-9]{1,3}")) {
                return false;
            }
            try {
                if (Integer.parseInt(group) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static List<String> sansOf(X509Certificate certificate) throws Exception {        List<String> result = new ArrayList<>();
        var altNames = certificate.getSubjectAlternativeNames();
        if (altNames == null) {
            return List.of();
        }
        for (List<?> entry : altNames) {
            int type = (Integer) entry.get(0);
            String value = String.valueOf(entry.get(1));
            if (type == GeneralName.dNSName) {
                result.add("DNS:" + value);
            } else if (type == GeneralName.iPAddress) {
                result.add("IP:" + value);
            }
        }
        return List.copyOf(result);
    }

    private static int keySizeBits(PrivateKey key) {
        // RSA keys expose their modulus through the standard JCA interface.
        if (key instanceof java.security.interfaces.RSAKey rsaKey) {
            return rsaKey.getModulus().bitLength();
        }
        return -1;
    }

    static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        String hex = HexFormat.of().withUpperCase().formatHex(digest);
        StringBuilder out = new StringBuilder("SHA256:");
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new TlsBootstrapException(
                    "TLS keystore password is not set. Set SAMVAAD_TLS_KEYSTORE_PASSWORD and restart.");
        }
    }

}
