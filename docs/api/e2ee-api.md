# E2EE Device & Prekey API Contract

Server-side E2EE device record and public prekey directory (ADR 0018
foundation). The server stores and serves **public key material only** and
is cryptographically blind: it never generates, receives, or holds private
keys, never creates Signal sessions, and never decrypts messages.

All key-material fields cross the wire as standard Base64 of opaque public
bytes. Caller identity always comes from the authenticated server
principal.

## `POST /api/e2ee/devices` — enroll

`201 Created`. Body (`EnrollDeviceRequestDto`):

```json
{
  "registrationId": 1001,
  "deviceIdentityPublicKey": "<base64>",
  "signedPrekeyId": 2001,
  "signedPrekey": "<base64>",
  "signedPrekeySignature": "<base64>",
  "kyberPrekeyId": 4001,
  "kyberPrekey": "<base64>",
  "kyberPrekeySignature": "<base64>",
  "clientPlatform": "WEB",
  "clientName": "Test",
  "clientVersion": "1.0.0"
}
```

All key fields are required, including the last-resort Kyber (PQXDH)
public material (`kyberPrekeyId`, `kyberPrekey`, `kyberPrekeySignature`).
The Kyber key must be unique across devices, like the device identity
key; reuse of either is rejected with `409 Conflict`
(`DeviceAlreadyExistsException`). Malformed material is `400`
(`InvalidKeyMaterialException` / `InvalidPrekeyBatchException`).
Enrollment lifecycle, session binding, approval, device limit, and
recovery rules are unchanged.

## `GET /api/e2ee/devices` — owner list

`200 OK`. Each `DeviceDto` carries the owner's own Kyber public material
(`kyberPrekeyId`, `kyberPrekey`, `kyberPrekeySignature`; public bytes
only) alongside the existing fields.

## `PUT /api/e2ee/devices/{deviceId}/kyber-prekey` — replace Kyber key

`200 OK`. Authenticated replacement of the device's own last-resort
Kyber public material. Body (`RotateKyberPrekeyRequestDto`):

```json
{
  "kyberPrekeyId": 4002,
  "kyberPrekey": "<base64>",
  "kyberPrekeySignature": "<base64>"
}
```

Rules: owner only; device must be `ACTIVE` (`PENDING`/`REVOKED`
→ `DeviceNotActiveException`); the calling session must be bound to the
device, otherwise `403`. Replacement is atomic and versioned by
`kyberPrekeyId`: an identical id with identical bytes is a no-op
success, an identical id with different bytes is `400`, a fresh id
replaces all three columns together. Adopting another device's key is
`409`. No rotation cadence is defined; the client owns generation and
private-key retention.

## `GET /api/e2ee/users/{username}/devices` — directory

`200 OK`, friendship-gated (`403` for non-friends). Each entry carries
the full bundle needed for asynchronous session establishment: identity
public key, signed prekey + signature, `hasAvailableOneTimePrekey`
flag, **and** the last-resort Kyber public material (`kyberPrekeyId`,
`kyberPrekey`, `kyberPrekeySignature`). Devices enrolled before Kyber
support expose `null` Kyber fields and cannot serve PQXDH bundles until
the owner replaces the material. No private material, pool counts, or
key bodies beyond the directory bundle are exposed.

## `POST /api/e2ee/devices/{deviceId}/one-time-prekeys/claim` — claim

`200 OK`, friendship-gated. Atomically consumes one EC one-time prekey
(`requestId` replay returns the same key without consuming another;
empty pool falls back to the signed prekey). The response additionally
carries the device's last-resort Kyber public material on **every**
claim, including replays and empty-pool fallbacks. The Kyber key is
reusable and is never consumed by the claim.

## Other endpoints

`POST /api/e2ee/devices/{deviceId}/approve`,
`PUT /api/e2ee/devices/{deviceId}/one-time-prekeys`,
`DELETE /api/e2ee/devices/{deviceId}`, and the recovery endpoints keep
their existing contracts; enrollment-bearing recovery flows require the
Kyber fields through the shared enrollment DTO.

