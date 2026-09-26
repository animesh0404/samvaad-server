# ADR 0019: V1 PQXDH last-resort Kyber public-material foundation

## Status

Accepted; server-side storage/transport implemented (migration `016`,
`PUT /devices/{deviceId}/kyber-prekey`, directory/claim exposure,
72 E2EE tests passing).

Extends ADR 0018, which remains the locked decision for V1 E2EE,
Signal/Sesame, and independent device identity. Nothing in ADR 0018 is
altered: its device-record contents list (§8) and prekey-pool policy
(§10) predate PQXDH and are extended — not contradicted — by this ADR.
An ADR may supersede an earlier ADR only by explicitly naming it; this
ADR names ADR 0018 and supersedes none of its text.

No library is selected or locked by this ADR.

## Context

The libsignal 0.103.x feasibility spike proved that the validated
integration path requires PQXDH: the `PreKeyBundle` constructor accepts
a nullable EC one-time key (`NULL_PRE_KEY_ID`) but has no absent
representation for Kyber — a null Kyber public key is rejected. The only
`KEMKeyType` is `KYBER_1024` (1569-byte public key, 64-byte signature,
verified experimentally). The pre-Kyber server device schema therefore
cannot serve a constructible bundle, and each device must publish
reusable last-resort Kyber public material.

## Decision

### Locked

1. PQXDH is required for the currently validated libsignal 0.103.x
   integration path.
2. Each `ACTIVE` E2EE device publishes one reusable last-resort Kyber
   public triple: `kyberPrekeyId`, `kyberPrekeyPublic`,
   `kyberPrekeySignature`.
3. Kyber private material is generated and retained only by the client.
4. The server stores and routes only Kyber public material.
5. The last-resort Kyber key is reusable and is NOT consumed by the
   existing EC one-time-prekey claim, including `requestId` replays and
   empty-pool fallbacks.
6. The existing EC one-time-prekey policy is unchanged: 100-key batches,
   replenishment below 20 available, atomic single-use claims.
7. Kyber replacement is authenticated (owner), atomic (all three columns
   together), and permitted only for an `ACTIVE` device over the session
   bound to that device.
8. Replacement with the same id and identical bytes is an idempotent
   no-op.
9. Replacement reusing an id with different bytes is rejected.
10. Adopting another device's Kyber public key — at enrollment or
    replacement — is rejected as a duplicate (`409`), mirroring device
    identity-key semantics including the race re-check.
11. `PENDING` and `REVOKED` devices cannot enroll with or rotate Kyber
    material.
12. The friendship-gated directory and the claim response expose the
    Kyber public triple; devices enrolled before Kyber support expose
    `null` Kyber fields until the owner replaces the material.
13. No private Kyber material is persisted server-side; the
    `e2ee_devices` table holds no secret/private columns (asserted in
    test against `information_schema`).

### Implementation facts

- Migration `016-add-device-kyber-material` adds three nullable columns
  plus a unique constraint on `kyber_prekey` (NULLs distinct, so legacy
  rows are unaffected). No backfill exists or is permitted.
- New enrollment requires all three Kyber fields; validation is
  envelope-only (presence, Base64, generous transport bound) through
  the existing `KeyMaterialEnvelopeValidator` seam — no server-side
  Kyber cryptography.
- New endpoint `PUT /api/e2ee/devices/{deviceId}/kyber-prekey`
  implements the rotation contract above. All other device/prekey
  endpoints keep their contracts with additive Kyber fields.

### Explicitly deferred

- One-time Kyber prekey pools.
- Kyber rotation cadence (intentionally unspecified pending client
  implementation research).

## Consequences

- Senders can construct complete PQXDH bundles (identity, signed EC,
  optional EC one-time, mandatory last-resort Kyber, registration and
  device information) from directory + claim alone.
- Pre-Kyber device rows remain valid but cannot serve PQXDH bundles;
  their owners backfill via rotation, not re-enrollment.
- The future real SignalAdapter needs no server transport changes for
  last-resort Kyber, only the already-identified integer device-slot
  mapping and envelope-type tagging.

## Not yet locked

- Concrete Signal library/version.
- Browser/Web E2EE strategy.
- AGPL licensing/product decision.
- One-time Kyber pool.
- Kyber rotation cadence.
- Exact Signal integer device-slot allocation.
- Final envelope wire format.

## Relationship to existing ADRs

- **ADR 0018:** extended as described above; its authentication,
  enrollment, revocation, friendship-gating, sequencing, and
  cryptographic-blindness invariants all stand unchanged.
- **ADR 0004:** conversation/message integrity invariants unaffected;
  the claim path preserves atomicity and idempotency semantics.
- **ADR 0008:** friendship remains the directory/claim authorization
  gate; friendship still confers no cryptographic trust.
