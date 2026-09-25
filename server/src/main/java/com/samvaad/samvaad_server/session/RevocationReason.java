package com.samvaad.samvaad_server.session;

public enum RevocationReason {
    USER_LOGOUT,
    USER_REVOKED,
    ADMIN_REVOKED,
    SESSION_EXPIRED,
    DEVICE_REVOKED,
    PENDING_EXPIRED
}
