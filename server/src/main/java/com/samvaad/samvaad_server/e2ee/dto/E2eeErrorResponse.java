package com.samvaad.samvaad_server.e2ee.dto;

/**
 * E2EE-specific error body: the standard message plus a stable machine
 * reason token so clients can route enrollment-state flows (for example
 * {@code E2EE_RECOVERY_REQUIRED}) without string-matching messages.
 */
public record E2eeErrorResponse(String message, String reason) {
}
