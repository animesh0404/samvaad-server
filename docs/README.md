# Samvaad Documentation Map

This directory contains the design authority, decision records, architectural snapshots, and developer guides for the Samvaad backend server.

---

## Decision Framework & Status Terminology

When reading or contributing to documentation and code, distinguish carefully between the following categories:

| Term | Meaning & Rule | Example in Samvaad |
| --- | --- | --- |
| **LOCKED** | An accepted, binding architectural or product decision recorded in the canonical design documents or ADRs. Must not be silently bypassed, overridden, or reopened; any conflict requires raising an explicit decision. | Server-authoritative state; User/UserProfile boundary; BCrypt passwords; exclusion of E2EE, groups, and reactions from V1. |
| **DEFERRED** | An intentionally postponed design decision or feature area where resolution is scheduled for a later phase or when concrete needs arise. Must not be implemented prematurely or presented as already decided. | V1 application-level profile/message encryption; exact JWT key management/rotation; transport reconnect mechanics; pagination cursor protocol. |
| **Implementation-time decisions** | Tactical details deliberately left open during high-level design to be resolved by concrete implementation constraints and empirical evidence as vertical slices are built. | Field-level Bean Validation constraints; Jackson serialization configs; mapper method structures. |
| **Current implementation** | What is actually written, tested, and executable in the repository right now. | User/profile HTTP endpoints; BCrypt-backed login; persisted sessions; session-bound JWT access tokens; rotating refresh tokens; five-session capacity enforcement; Liquibase migrations. |
| **Known implementation gaps** | Specific divergences between current codebase behavior and accepted design or API contracts that remain to be resolved. | Password registration; authorization enforcement; logout/session-revocation operations; realtime delivery of blocked-login security events; messaging/transport; profile PATCH field-presence semantics. |

---

## Documentation Map

### 1. Canonical Design Authority (Original Design Records)

These three original records contain the product rationale, technical architecture, and implementation sequencing. They are the primary source of truth for architectural intent:

- **[Samvaad Product & Design Decisions](Samvaad%20Product%20%26%20Design%20Decisions.md)**  
  Core product philosophy, domain invariants, locked decisions, deferred items, and product guardrails.
- **[Samvaad Technical Design](Samvaad%20Technical%20Design.md)**  
  System architecture, entity relationships, database constraints, protocol flows, and security boundaries.
- **[Samvaad Implementation Roadmap](Samvaad%20Implementation%20Roadmap.md)**  
  Phased implementation plan detailing vertical slices from project bootstrap to V1 readiness. It records actual implementation status even when work proceeds out of numerical phase order.

### 2. Architecture Decision Records (ADRs)

ADRs record durable decisions and their trade-offs concisely for fast reference during implementation and review. Located in [`adr/`](adr/):

- **[ADR Index](adr/README.md)**: Overview and indexing rules.
- **[ADR 0001: Server-Authoritative Identity and State](adr/0001-server-authoritative-identity-and-state.md)**: Server maintains authority over all identities, state transitions, and client claims.
- **[ADR 0002: User/Profile Boundary and Email Ownership](adr/0002-user-profile-boundary-and-email-ownership.md)**: Email belongs to the `User` identity entity; public profile attributes live in `UserProfile`.
- **[ADR 0003: Authentication and Session Model](adr/0003-authentication-and-session-model.md)**: BCrypt password hashing, session-derived identity, JWT access tokens, and rotating refresh tokens; implementation status is tracked in the ADR.
- **[ADR 0004: Conversation and Message Integrity](adr/0004-conversation-and-message-integrity.md)**: Direct conversation invariants, message idempotency keys, and append-only message sequencing.
- **[ADR 0005: V1 Scope and Data-Protection Boundary](adr/0005-v1-scope-and-data-protection-boundary.md)**: Clear V1 non-goals (no E2EE, groups, or rich text) and deferral of application-level encryption.
- **[ADR 0006: Profile PATCH Field-Presence Semantics](adr/0006-profile-patch-field-presence.md)**: Contract for partial updates (omitted = unchanged, non-null = replace, null = clear).

### 3. Current State Snapshots

Concise technical snapshots of the current codebase state:

- **Architecture**: **[Current Implementation State](architecture/current-state.md)**  
  Summary of implemented layers, database models, remaining authentication work, next planned slices, and maintained [architecture diagrams](architecture/diagrams/current-user-profile.puml).
- **API**: **[Current User and Profile API](api/current-user-profile-api.md)**  
  Contract, payload schemas, and known gaps for implemented HTTP endpoints, including the current login and refresh endpoints.
- **Security**: **[Current Security Posture](security/current-security-posture.md)**  
  Factual snapshot of active security controls, persistence of sensitive values, authentication/session implementation, and remaining security gaps.

### 4. Developer Guides

Guides for developers and coding tools onboarding into the codebase:

- **[Environment Setup](development/setup.md)**  
  Prerequisites, toolchain requirements (Java 25, Gradle 9.7.0, PostgreSQL 18), Docker Compose, Testcontainers dev mode, environment-secret setup, and build commands.
- **[Testing Guide](development/testing.md)**  
  Test suite structure across user/profile, authentication/session, concurrency, and Spring/Testcontainers verification, plus Gradle test execution commands.
- **[Agent Guidance](../AGENTS.md)**  
  Rules of engagement and guardrails for AI coding assistants working in this repository.
