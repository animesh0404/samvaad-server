package com.samvaad.samvaad_server.common.logging;

import java.util.UUID;

/**
 * Shared correlation-identifier handling for HTTP and STOMP transports.
 * A caller-supplied identifier is accepted only when it is safe to echo into
 * log patterns and response/frame metadata; otherwise a UUID is generated.
 */
public final class TraceIds {

    public static final String MDC_KEY = "traceId";

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    public static final String TRACE_ID_HEADER = "X-Trace-ID";

    private static final int MAX_LENGTH = 128;

    private TraceIds() {
    }

    public static boolean isValid(String candidate) {
        if (candidate == null) {
            return false;
        }
        String value = candidate.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    public static String resolveOrGenerate(String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                if (isValid(candidate)) {
                    return candidate.trim();
                }
            }
        }
        return UUID.randomUUID().toString();
    }
}
