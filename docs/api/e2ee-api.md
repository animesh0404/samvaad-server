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

## `POST /api/e2ee/devices/{deviceId}/bind` — recovery rebind

`200 OK` returns the existing `DeviceDto` after an authenticated unbound session is bound to the named ACTIVE device. Request body:

```json
{"recoveryCode":"<one-time-code>"}
```

The caller must own the device, the device must be ACTIVE, and the caller's session must not already be bound to a device. The server consumes the recovery code and binds the session atomically. No new device is created and no private cryptographic material crosses the API. Invalid/blank or already-consumed codes fail without consuming another code; foreign, inactive, or already-bound targets use the existing exception/error contract.

## Other endpoints

`POST /api/e2ee/devices/{deviceId}/approve`,
`PUT /api/e2ee/devices/{deviceId}/one-time-prekeys`,
`DELETE /api/e2ee/devices/{deviceId}`, and the recovery endpoints keep
their existing contracts; enrollment-bearing recovery flows require the
Kyber fields through the shared enrollment DTO.

# E2EE Ciphertext Transport (ADR 0020)

The server routes, orders, retains, delivers, and synchronizes opaque
ciphertext. It never decrypts, parses, or interprets it. Vocabulary:

- **logical message** — one `messageRequestId` from one sender device;
  carries one envelope per recipient device, one conversation, one
  sequence number.
- **ciphertext envelope** — one recipient device's opaque bytes plus
  the sender-supplied `envelopeType` (`PREKEY_INIT`/`RATCHET`).
- **mailbox entry** — an undelivered pointer (device + message).
  Acknowledgement deletes the entry only.
- **current durable history (transition state)** — per-device envelopes, readable
  after acknowledgement.
- **acknowledgement** — per-device delivery completion; idempotent.
- **synchronization cursor** — per-device, per-conversation
  highest-durably-processed sequence, advanced only by explicit
  client assertion. Fetching/acking never moves it.

## `POST /api/e2ee/messages` — submit

`201 Created` (new) or `200 OK` (identical retry). Body:

```json
{
  "messageRequestId": "<uuid>",
  "envelopes": [
    {
      "senderDeviceId": "<uuid>",
      "recipientDeviceId": "<uuid>",
      "envelopeType": "PREKEY_INIT",
      "ciphertext": "<base64>"
    }
  ]
}
```

Rules: every `senderDeviceId` must equal the session-bound device
(spoofing → `403`); each recipient device must exist, be `ACTIVE`,
and belong to a friend (unknown/inactive → `404`, self/unauthorized
→ `403`); all envelopes address one recipient user in one
conversation (created on demand). Same `messageRequestId` with
identical content replays the original response; any difference is
`409`. The batch is atomic.

Size bounds: at most 65,536 decoded ciphertext bytes per envelope and
at most 10 envelopes per submit (oversized material → `400`
`InvalidKeyMaterialException`, nothing persisted).

## `GET /api/e2ee/mailbox?limit=50` — fetch

`200 OK`. The session-bound device's undelivered ciphertext in stable
acceptance order (`limit` 1–100). Sessions without a device see an
empty mailbox.

## `POST /api/e2ee/mailbox/ack` — acknowledge

`200 OK` with `{"acknowledged": n}`. Deletes only the bound device's
entries for the given message ids; unknown ids and foreign ids
acknowledge nothing (idempotent, per-device).

## `GET /api/e2ee/conversations/{id}/messages?afterSequence=0&limit=20` — history

`200 OK`. The bound device's durable envelopes after `afterSequence`,
ascending, participant-only (`404` unknown / `403` non-participant).
Readable after acknowledgement; history never deletes.

## `PUT /api/e2ee/sync` + `GET /api/e2ee/sync?conversationId=` — cursor

Advance is `{"conversationId": "<uuid>", "throughSequence": n}` → the
stored cursor; `n` must satisfy `0 <= n <= conversation.lastSequenceNumber`
(out of range → `400`/`409`). Repeats are safe, backwards moves are
`409`. Read returns the stored value or `0`. The cursor means durably
processed — never delivery, never proof of decryption.

## Realtime delivery — device channel

Committed messages fan out post-commit, one persisted envelope per
recipient device, to:

```text
/topic/devices/{recipientDeviceId}
```

The payload is the same `E2eeCiphertextItemDto` shape as mailbox and
history reads. Delivery is best-effort: broker failure never fails the
HTTPS submission, and delivery never acknowledges the mailbox entry.

STOMP model: `CONNECT` with `Authorization: Bearer <JWT>` derives a
server-side `StompDevicePrincipal` (JWT + session + bound `ACTIVE`
device; the client never supplies device identity). `SUBSCRIBE` is
authorized by exact match against the connection's own device topic,
with session/device liveness revalidated; anything else is rejected
identically. There is no client SEND handler. Session revocation
terminates the associated live connections; device revocation
terminates every live connection bound to that device's sessions.
