# ADR 0020: V1 E2EE ciphertext mailbox and history transport

## Status

Accepted; server-side ciphertext transport implemented (migration `018`,
`POST /api/e2ee/messages`, mailbox fetch/ack, history reads, sync
cursors, full E2EE suite passing).

Extends ADR 0018 and ADR 0019, which remain the locked decisions for V1
E2EE identity/enrollment and PQXDH public material. Nothing in either
ADR is altered. An ADR may supersede an earlier ADR only by explicitly
naming it; this ADR names ADR 0018 and ADR 0019 and supersedes none of
their text.

No library is selected or locked by this ADR. No SignalAdapter exists yet.

> **Supersession note (implementation update, decision body below unchanged):**
> two statements in this ADR no longer describe the current implementation.
> First, real-time/STOMP fan-out is implemented, not deferred: committed
> messages fan out post-commit to `/topic/devices/{recipientDeviceId}` with
> the mailbox as durable fallback (mailbox polling is no longer the sole
> delivery mechanism). Second, the JVM Signal adapter and e2ee-client library
> work it defers has since landed (see ADR 0023, ADR 0024). The persistence,
> idempotency, sequencing, and cursor decisions below stand unchanged.

## Context

After the crypto layer produces a frozen 7-field `OutboundEnvelope` per
recipient device, the server must route, order, durably retain, deliver,
and synchronize opaque ciphertext without ever decrypting, parsing, or
otherwise interpreting it — while preserving the server-authoritative
sequencing, friendship authorization, and idempotency invariants that
govern plaintext messaging.

## Decision

### Transport scope

- One logical message carries one client-generated `messageRequestId`
  and one opaque envelope per recipient device. A logical message
  addresses exactly one recipient user (distinct from the sender);
  own-device synchronization happens through history reads, not
  self-addressed envelopes.
- Submission is a single batched HTTP request. One HTTP request per
  recipient device is not required.
- The server persists `envelopeType` exactly as supplied
  (`PREKEY_INIT`/`RATCHET`, check-constrained) and never inspects
  `envelopeCiphertext` beyond transport decoding.

### Persistence

- `e2ee_messages`: the logical message — conversation (reused
  pair-scoped `Conversation`), sender user, sender device UUID,
  unique `request_id`, per-conversation `sequence_number`
  (unique per conversation), server timestamp. No plaintext, no
  Signal-parsed fields.
- `e2ee_envelopes`: durable per-device ciphertext — message,
  recipient device UUID, type, opaque bytes; unique per
  (message, device). History is permanent.
- `e2ee_mailbox`: undelivered pointers — recipient device UUID plus
  message reference; unique per (device, message).
- `e2ee_sync_cursors`: per (device, conversation) processed marker.

### Mailbox vs history vs cursor

- Mailbox entries are undelivered ciphertext pointers. Fetching is a
  stable-ordered read; acknowledgement deletes the entry only and is
  idempotent and strictly per-device. Delivery to one device never
  affects another, and offline devices never block others.
- History reads come from durable envelopes, so acknowledged messages
  remain readable. History never deletes.
- The sync cursor means **durably processed** as asserted by the
  device. Fetching and acknowledging never advance it; only an
  explicit advance does. Backwards moves are rejected (409);
  repeats are safe. The server never treats the cursor as proof of
  successful decryption.

### Idempotency and concurrency

- `messageRequestId` is globally unique. Identical retries return the
  original response (`createdNew=false`) without duplicating messages,
  envelopes, or mailbox entries. Same id with different content,
  recipients, or sender is `409 Conflict`.
- PostgreSQL aborts a transaction on the first failed statement, so a
  lost insert race cannot be recovered inside the same transaction:
  submission runs in a fresh transaction per attempt (bounded retry),
  and a raced insert resolves by replaying the committed winner.
  Unique constraints — not application checks — arbitrate duplicates.
- Submission is atomic: a rejected recipient aborts the whole batch;
  partial mailbox state is never visible.

### Ordering

- The server assigns the authoritative per-conversation sequence at
  acceptance time under the conversation row lock, with the
  (conversation, sequence) unique constraint as backstop. Client
  timestamps are never authoritative and never influence order.
- Sequence order means acceptance order within one 1:1 conversation,
  across all sender devices. Mailbox order is stable acceptance order
  (server timestamp, message id tiebreak). No cross-conversation
  global ordering is promised.

### Security boundary

- Every acting device is derived from the authenticated session
  binding: sender spoofing, cross-device acknowledgement, and
  cross-user mailbox reads are structurally impossible or explicitly
  rejected (403). Revoked senders cannot submit; revoked/inactive
  recipients receive nothing (404, existence-hiding, mirroring the
  claim path). Friendship gates submission; history reads require
  participation only, mirroring plaintext reads. No private crypto
  material exists anywhere in this slice.

### Consequences

- The future SignalAdapter needs no transport changes: it produces
  frozen envelopes and consumes mailbox/history/cursor APIs.
- User deletion removes ciphertext transport rows for the user's
  conversations before conversation removal.
- Rate limiting remains a separate concern.

## Explicitly deferred

- Real-time/STOMP fan-out of ciphertext (mailbox polling is the V1
  delivery mechanism).
- One-time Kyber pools, rotation cadences, concrete library selection,
  browser/WASM, licensing/product decisions (unchanged).
- Read receipts and delivery receipts beyond mailbox acknowledgement.
- Message edits/deletes, groups/MLS, attachments.

## Relationship to existing ADRs

- **ADR 0004:** conversation/message integrity invariants extended to
  ciphertext: same pair-scoped conversations, same atomic
  find-or-create, same request-id idempotency shape, same
  participant/FORBIDDEN read rules.
- **ADR 0008:** friendship gates ciphertext submission; friendship is
  not re-checked on history reads.
- **ADR 0018:** server-authoritative sequencing and crypto-blindness
  preserved; mailbox/history/cursor are the asynchronous delivery and
  synchronization semantics its §10–§11 require.
- **ADR 0019:** directory/claim bundle transport unchanged; envelopes
  carry the Kyber-complete ciphertext opaquely.
