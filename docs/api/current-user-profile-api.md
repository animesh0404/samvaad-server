# Current User / Profile API

## Username discovery

Implemented endpoint:

`GET /api/users/lookup?username={username}`

Authentication is required. Matching is exact and case-insensitive. Missing or blank input returns `400`; an unknown username returns `404`. The response is restricted to discovery-safe identity fields (`userId` and `username`) and does not expose email, password/hash, role, sessions, tokens, or internal security metadata.

Self-lookup is allowed. Friendship is not required for lookup.

## Friend-request relationship

Friend-request lifecycle is implemented separately under `/api/friend-requests`. Friend-gated profile visibility is not activated by Phase 3.

## Profile PATCH

`PATCH /api/users/{userId}/profile` is implemented as a self-only update. Omitted fields remain unchanged, present non-null fields replace the stored value, and explicitly present `null` fields clear the stored value.
