# ADR 0006: Profile PATCH field-presence semantics

## Status

Accepted; implementation incomplete.

## Decision

For `PATCH /api/users/{userId}/profile`:

- an omitted field leaves the stored value unchanged;
- a present non-null field replaces the stored value; and
- a present `null` field explicitly clears the stored value.

`UserProfileUpdateDto` is the dedicated PATCH request DTO. The implementation
must retain whether each field was present separately from its value.

## Consequences

The current Java DTO maps omitted properties and explicit JSON `null` to the
same `null` value, so it cannot yet implement the clearing rule. A future
implementation must change this deliberately and add tests for all three cases;
it must not silently preserve the current “null means unchanged” behavior.

## Source material

- Reconciliation decision: PATCH null semantics
