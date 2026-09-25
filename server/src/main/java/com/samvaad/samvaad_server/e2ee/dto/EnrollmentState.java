package com.samvaad.samvaad_server.e2ee.dto;

/** Account-level E2EE enrollment state, derived from device rows. */
public enum EnrollmentState {
    NEVER_ENROLLED,
    ENROLLED_ACTIVE,
    RECOVERY_REQUIRED
}
