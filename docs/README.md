# Samvaad Documentation

The documentation set tracks the implementation state of the Samvaad Server and the locked product/architecture decisions.

Current implementation phases:
- Phase 0 — Design reconciliation: complete.
- Phase 1 — Authentication & authorization boundary: complete.
- Phase 2 — Exact username discovery: complete.
- Phase 3 — Friend request vertical slice: complete.
- Phase 4 — Direct messaging vertical slice: next.

Friend-request behavior is documented as an authenticated lifecycle from no relationship to pending to accepted/friendship, with rejected and cancelled terminal states. The accepted request row is the friendship record, while an internal `areFriends(a, b)` query provides the relationship check needed by later messaging authorization.

See the implementation roadmap and architecture/security documents for the current state and explicit deferred areas.
