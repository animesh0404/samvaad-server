# ADR 0006: Profile PATCH field-presence semantics

## Status

Accepted; implemented.

## Decision

For `PATCH /api/users/{userId}/profile`:

- an omitted field leaves the stored value unchanged;
- a present non-null field replaces the stored value; and
- a present `null` field explicitly clears the stored value.

`UserProfileUpdateDto` is the dedicated PATCH request DTO. The implementation
retains whether each field was present separately from its value.

## Consequences

The profile PATCH implementation distinguishes omitted properties from explicit
JSON `null`. Omitted fields remain unchanged, while explicitly present `null`
values clear the corresponding stored profile field. Tests cover omitted,
non-null, and explicit-null behavior.

## Source material

- Reconciliation decision: PATCH null semantics
