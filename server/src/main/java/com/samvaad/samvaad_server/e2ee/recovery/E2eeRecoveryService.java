package com.samvaad.samvaad_server.e2ee.recovery;

import com.samvaad.samvaad_server.common.logging.OperationalLog;
import com.samvaad.samvaad_server.e2ee.E2eePolicy;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Account-level one-time recovery codes (ADR 0018 §4). Recovery codes are
 * account-recovery credentials, not cryptographic keys and not
 * message-history recovery keys. The server persists only BCrypt hashes;
 * plaintext codes exist only transiently in the generating request and are
 * returned to the client exactly once.
 */
@Service
public class E2eeRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(E2eeRecoveryService.class);

    /**
     * Unambiguous alphabet (no 0/O, 1/I/L): 32 symbols, 5 bits per char.
     * 20 chars per code gives 100 bits of entropy.
     */
    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int CODE_CHARS = 20;
    private static final int DISPLAY_GROUP = 4;

    private final E2eeRecoveryCodeRepo recoveryCodeRepo;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public E2eeRecoveryService(
            E2eeRecoveryCodeRepo recoveryCodeRepo,
            PasswordEncoder passwordEncoder) {
        this.recoveryCodeRepo = recoveryCodeRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Generates the initial recovery-code set for first-device bootstrap.
     * Returns the display-formatted plaintext codes exactly once; only
     * hashes are persisted. Joins the caller's transaction.
     */
    @OperationalLog("e2ee.recovery.generate")
    @Transactional
    public List<String> createInitialSet(User user) {
        return persistNewSet(user).codes();
    }

    /**
     * Explicit rotation: supersedes every still-usable code and generates a
     * fresh set. Rotation is never silent. Returns the new set identifier and
     * the plaintext set exactly once. Joins the caller's transaction.
     */
    @OperationalLog("e2ee.recovery.rotate")
    @Transactional
    public RotateResult rotateSet(User user) {
        recoveryCodeRepo.supersedeUsableByUser(user, LocalDateTime.now());
        GeneratedSet generated = persistNewSet(user);
        log.info("Recovery codes rotated userId={} setId={}", user.getUserId(), generated.setId());
        return new RotateResult(generated.setId(), generated.codes());
    }

    /**
     * Verifies one candidate code against the user's usable set and consumes
     * it atomically in the caller's transaction: a caller failure after this
     * call rolls the consumption back, and two concurrent uses resolve to a
     * single winner because consumption flips {@code consumedAt} exactly
     * once. Failure is a generic error that never reveals which case
     * occurred.
     */
    @Transactional
    public E2eeRecoveryCode consumeCode(User user, String candidate) {
        String canonical = canonicalize(candidate);
        List<E2eeRecoveryCode> usable = recoveryCodeRepo.findUsableByUser(user);
        for (E2eeRecoveryCode code : usable) {
            if (!passwordEncoder.matches(canonical, code.getCodeHash())) {
                continue;
            }
            // Lock the matched row and re-verify: a racing transaction may
            // have consumed or superseded it after this read ran. The lock
            // re-reads committed state, so exactly one consumer wins.
            E2eeRecoveryCode locked = recoveryCodeRepo.findByIdWithLock(code.getRecoveryCodeId())
                    .orElse(null);
            if (locked == null || locked.getConsumedAt() != null || locked.getSupersededAt() != null) {
                break;
            }
            locked.setConsumedAt(LocalDateTime.now());
            E2eeRecoveryCode consumed = recoveryCodeRepo.save(locked);
            log.info("Recovery code consumed userId={} position={}",
                    user.getUserId(), locked.getCodePosition());
            return consumed;
        }
        log.warn("Recovery code rejected userId={}", user.getUserId());
        throw new InvalidRecoveryCodeException();
    }

    private GeneratedSet persistNewSet(User user) {
        UUID setId = UUID.randomUUID();
        List<String> plaintext = new ArrayList<>(E2eePolicy.RECOVERY_CODES_PER_SET);
        for (int position = E2eePolicy.RECOVERY_CODE_POSITION_MIN;
                position <= E2eePolicy.RECOVERY_CODE_POSITION_MAX;
                position++) {
            String canonical = randomCode();
            E2eeRecoveryCode code = new E2eeRecoveryCode();
            code.setUser(user);
            code.setSetId(setId);
            code.setCodePosition(position);
            code.setCodeHash(passwordEncoder.encode(canonical));
            recoveryCodeRepo.save(code);
            plaintext.add(displayForm(canonical));
        }
        log.info("Recovery code set generated userId={} setId={} count={}",
                user.getUserId(), setId, plaintext.size());
        return new GeneratedSet(setId, List.copyOf(plaintext));
    }

    public record RotateResult(UUID setId, List<String> codes) {
    }

    private record GeneratedSet(UUID setId, List<String> codes) {
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_CHARS);
        for (int i = 0; i < CODE_CHARS; i++) {
            code.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private String displayForm(String canonical) {
        StringBuilder display = new StringBuilder(canonical.length() + 4);
        for (int i = 0; i < canonical.length(); i++) {
            if (i > 0 && i % DISPLAY_GROUP == 0) {
                display.append('-');
            }
            display.append(canonical.charAt(i));
        }
        return display.toString();
    }

    /**
     * Normalizes a candidate to the hashed canonical form: case-insensitive,
     * grouping/whitespace tolerant. Returns an empty marker for null input;
     * matching an empty marker never succeeds because no stored hash covers
     * the empty string.
     */
    private String canonicalize(String candidate) {
        if (candidate == null) {
            return "";
        }
        StringBuilder canonical = new StringBuilder(candidate.length());
        for (char c : candidate.toLowerCase().toCharArray()) {
            if (CODE_ALPHABET.indexOf(c) >= 0) {
                canonical.append(c);
            }
        }
        return canonical.toString();
    }
}
