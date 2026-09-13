# Current security posture

This is a factual snapshot, not a claim that all planned security controls are
implemented.

## Accepted security decisions

- Passwords must use BCrypt with generated salt. Plaintext passwords must never
  be persisted or logged.
- V1 uses session-derived identity, JWT access tokens, and rotating refresh
  tokens.
- End-to-end encryption is excluded from V1.
- Application-level encryption of profile/private application data is deferred
  for V1. It remains a planned future security phase.

See ADRs 0003 and 0005 and the original design documents for rationale.

## Current implementation

- Password verification uses the configured Spring Security `PasswordEncoder`
  and the authentication flow rejects users without a password hash.
- Login is implemented at `POST /api/auth/login`. Successful authentication
  creates a persisted session, generates a session-bound JWT access token, and
  returns a refresh token.
- Refresh is implemented at `POST /api/auth/refresh`. Refresh tokens are stored
  as hashes, validated against the persisted session, checked for revocation and
  expiry, and rotated when a refresh succeeds.
- The local JWT signing secret is supplied through the `SAMVAAD_JWT_SECRET`
  environment variable rather than committed application configuration.
- User email and current profile fields are persisted as ordinary plaintext
  columns. No application-level profile/message encryption exists.
- PostgreSQL credentials in the committed local configuration are development
  setup, not a production secret-management design.
- JPA auditing exists, but the current actor is the fixed value `"system"`;
  this is not authenticated-user attribution.
- Authorization is enforced for the current HTTP account/user operations,
  including ADMIN-only provisioning/listing/deletion, self-only account mutations,
  and authenticated exact username discovery.
- `POST /api/auth/logout` revokes the current persisted session, and revoked
  sessions subsequently fail authenticated requests and refresh attempts.
- Username discovery returns only `userId` and `username`; email, credentials,
  role, session state, and token material are not exposed by the discovery DTO.

## Remaining gaps and implications

The implemented HTTP authentication/session boundary includes authorization,
logout/session revocation, and the current account lifecycle. The complete
planned security boundary is not finished because realtime transport
authentication/delivery, production JWT key management, rate limiting, and other
hardening remain pending.

Do not claim profile/message encryption or E2EE. When application-level
encryption is designed, introduce it at a deliberate boundary without
changing domain behavior, API contracts, or message semantics solely to expose
ciphertext handling.

Exact JWT signing/claims/key management remains intentionally deferred. The
local `SAMVAAD_JWT_SECRET` setup is a development configuration mechanism, not a
production key-management design.
