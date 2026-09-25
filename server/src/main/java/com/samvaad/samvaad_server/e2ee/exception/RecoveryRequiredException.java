package com.samvaad.samvaad_server.e2ee.exception;

/**
 * Raised when the account previously completed E2EE enrollment but currently
 * has zero ACTIVE devices. Account credentials alone are insufficient; an
 * unused recovery code is required before enrollment completes. This is not
 * first-device bootstrap. Maps to HTTP 403 with the stable
 * {@code E2EE_RECOVERY_REQUIRED} reason token.
 */
public class RecoveryRequiredException extends RuntimeException {

    public static final String REASON = "E2EE_RECOVERY_REQUIRED";

    public RecoveryRequiredException() {
        super("E2EE recovery required: the account has no active devices");
    }
}
