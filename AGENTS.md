# Samvaad agent guidance

## Design authority

Before making an architectural or behavioral change, read the three original
design records in `docs/`:

- `Samvaad Product & Design Decisions.md`
- `Samvaad Technical Design.md`
- `Samvaad Implementation Roadmap.md`

Then read the applicable ADRs in `docs/adr/`, relevant code, migrations, and
tests. The original records preserve the detailed rationale. ADRs provide
concise, durable decision summaries.

`LOCKED` means accepted. Do not silently reopen, override, or work around a
locked decision; surface a conflict and obtain an explicit decision. `DEFERRED`
means unresolved: do not present an implementation choice as already accepted.

## Guardrails

- Keep the server authoritative. Authenticated identity must come from the
  server-side session context when authentication is implemented.
- Preserve database-enforced invariants when correctness depends on
  concurrency.
- Preserve the `User`/`UserProfile` separation. Email belongs to `User`.
- Keep API DTOs separate from JPA/domain entities. Use
  `UserProfileUpdateDto` as the profile PATCH input type.
- Profile PATCH semantics are: omitted field = unchanged; present non-null =
  replace; present null = clear. Preserve field presence separately from field
  value when implementing this contract.
- Never persist or log plaintext passwords. Passwords must use BCrypt when the
  authentication slice is implemented.
- Do not implement V1-excluded features (including E2EE, attachments,
  reactions, groups, and rich text) without an explicit scope decision.
- V1 application-level encryption of profile/private application data is
  deferred. Do not add fake encryption abstractions, but avoid coupling domain
  behavior and API contracts directly to persistence representation.
- Keep changes scoped. Add or update tests for behavior changes, including
  boundary, authorization, persistence, and concurrency tests where relevant.
- Do not commit or push unless explicitly instructed.
