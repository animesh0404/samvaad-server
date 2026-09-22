package com.samvaad.samvaad_server.tls;

/**
 * Fatal TLS bootstrap failure. The application must fail startup rather than
 * silently regenerate, repair, or replace TLS identity.
 */
public class TlsBootstrapException extends RuntimeException {

    public TlsBootstrapException(String message) {
        super(message);
    }

    public TlsBootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
