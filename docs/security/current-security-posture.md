# Current security posture

This is a factual snapshot, not a claim that all planned security controls are
implemented.

## Accepted security decisions

- Passwords must use BCrypt with generated salt. Plaintext passwords must never
  be persisted or logged.
- V1 uses session-derived identity, JWT access tokens, and rotating refresh
  tokens when authentication is implemented.
- End-to-end encryption is excluded from V1.
- Application-level encryption of profile/private application data is deferred
  for V1. It remains a planned future security phase.

See ADRs 0003 and 0005 and the original design documents for rationale.

## Current implementation

- There is no password, authentication, session, JWT, or refresh-token
  implementation yet.
- User email and current profile fields are persisted as ordinary plaintext
  columns. No application-level profile/message encryption exists.
- PostgreSQL credentials in the committed local configuration are development
  setup, not a production secret-management design.
- JPA auditing exists, but the current actor is the fixed value `"system"`;
  this is not authenticated-user attribution.

## Implications for future work

Do not claim profile/message encryption, E2EE, or completed authentication.
When authentication is implemented, enforce the password and session decisions.
When application-level encryption is designed, introduce it at a deliberate
boundary without changing domain behavior, API contracts, or message semantics
solely to expose ciphertext handling.
