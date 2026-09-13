# Testing Guide

This document describes the testing approach, test structure, and verification commands used in the Samvaad backend server.

---

## Testing Approach

The test suite in `src/test` is organized into distinct testing layers:

### 1. Unit Tests (Isolated Logic & Mappers)

Unit tests run quickly without Spring application context or external services. They verify deterministic domain transformations and isolated service behavior:

- **`UserMapperTest`** (`src/test/java/com/samvaad/samvaad_server/user/UserMapperTest.java`): Tests bidirectional mapping between `UserDto` and `User` entities.
- **`UserProfileMapperTest`** (`src/test/java/com/samvaad/samvaad_server/user/userprofile/UserProfileMapperTest.java`): Tests partial profile updates from `UserProfileUpdateDto` onto existing `UserProfile` entities.
- **`AuthenticationServiceTest`** (`src/test/java/com/samvaad/samvaad_server/auth/AuthenticationServiceTest.java`): Tests credential verification, login behavior, session-capacity handling, token generation, and blocked-login event publication in isolation.
- **`RefreshTokenServiceTest`** (`src/test/java/com/samvaad/samvaad_server/auth/RefreshTokenServiceTest.java`): Tests refresh-token validation and rotation behavior in isolation.
- **`SessionServiceTest`** (`src/test/java/com/samvaad/samvaad_server/session/SessionServiceTest.java`): Tests session creation and active-session counting behavior.
- **Messaging service tests**: Verify friend authorization, self-message rejection, authorization-before-conversation-creation, conversation creation/race handling, request-ID replay/conflict behavior, and message sequencing.

### 2. Web Layer Tests (MockMvc & Controller Isolation)

Controller tests verify endpoint routing, HTTP status codes, request/response JSON serialization, validation, and service interactions in isolation:

- **`UserProfileControllerTest`** (`src/test/java/com/samvaad/samvaad_server/user/userprofile/UserProfileControllerTest.java`): Tests the profile PATCH endpoint with standalone MockMvc.
- **`AuthControllerTest`** (`src/test/java/com/samvaad/samvaad_server/auth/AuthControllerTest.java`): Tests the login and refresh HTTP endpoints, request validation, response serialization, and service delegation.
- **Messaging controller tests**: Verify direct-message request validation, authentication context usage, `201` creation vs `200` idempotent replay, and `409` request-ID conflicts.

### 3. Integration & Concurrency Tests (Testcontainers & Spring Boot)

Integration tests verify the Spring Boot context, JPA mappings, Liquibase migrations, persistence behavior, and concurrency-sensitive behavior against PostgreSQL:

- **`SamvaadServerApplicationTests`** (`src/test/java/com/samvaad/samvaad_server/SamvaadServerApplicationTests.java`): Boots the Spring context with Testcontainers PostgreSQL and verifies application initialization.
- **`RefreshTokenIntegrationTest`**: Verifies persisted refresh-token behavior across the HTTP/service boundary, including rotation and invalidation of the old token.
- **`RefreshTokenConcurrencyIntegrationTest`**: Exercises concurrent refresh attempts against the same session/token state.
- **`LoginConcurrencyIntegrationTest`**: Exercises concurrent login attempts and verifies the five-session capacity is enforced transactionally; blocked-login events are also verified.
- **Messaging integration tests**: Verify friend-gated sending, conversation uniqueness, ordered message sequences, request-ID idempotency, database uniqueness constraints, and unauthorized access behavior.
- **`TestcontainersConfiguration`** (`src/test/java/com/samvaad/samvaad_server/TestcontainersConfiguration.java`): Configures the PostgreSQL Testcontainer used by the integration tests.

### 4. Development Runtime Test Harness

- **`TestSamvaadServerApplication`** (`src/test/java/com/samvaad/samvaad_server/TestSamvaadServerApplication.java`): Executable test application entry point that runs `SamvaadServerApplication` with Testcontainers, allowing developers to run the server with an ephemeral database.

---

## What the Tests Currently Establish

The current suite provides automated evidence for the implemented user/profile, authentication/session, relationship, and initial direct-messaging foundation, including:

- user/profile mapping and controller behavior
- BCrypt-backed credential verification
- login success/failure behavior
- persisted session creation
- five-active-session capacity enforcement
- concurrent login serialization around session capacity
- blocked-login event publication
- JWT access-token generation
- refresh-token hashing
- refresh-token rotation
- rejection of expired/revoked/old refresh tokens
- concurrent refresh behavior
- Spring Boot + PostgreSQL + Liquibase context initialization
- exact username discovery behavior
- friend-request lifecycle and ownership authorization
- direct-message friend authorization and self-message rejection
- direct-message authorization-before-conversation-creation regression coverage
- normalized direct-conversation uniqueness
- atomic conversation/first-message persistence
- server timestamps and monotonic per-conversation message sequencing
- request-ID idempotent replay and foreign request-ID conflict handling
- database-backed conversation/message uniqueness invariants

The suite does **not** establish that the full planned Samvaad system is implemented. Realtime transport, read state, message mutations, replies, reconnect/offline synchronization, blocking/unfriend/mute/archive, and other later conversation lifecycle features remain outside the implemented slices.

---

## Running Tests

Tests are executed using the Gradle wrapper (`./gradlew`):

### Run the Full Test Suite
```bash
./gradlew test
```

### Run a Specific Test Class
```bash
./gradlew test --tests com.samvaad.samvaad_server.auth.AuthenticationServiceTest
```

### Run Tests in a Specific Package
```bash
./gradlew test --tests "com.samvaad.samvaad_server.auth.*"
```

### Run Full Verification (Compilation + Tests + Checks)
```bash
./gradlew check
```

---

## Test Reports

After running tests, Gradle generates standard HTML test reports located at:
```
build/reports/tests/test/index.html
```

---

## Related Documentation

- [Local Development Setup](setup.md) — Local environment and database execution options.
- [Current Implementation State](../architecture/current-state.md) — Current architectural layers and entities.
- [Current Security Posture](../security/current-security-posture.md) — Implemented security controls and remaining gaps.
- [Documentation Map](../README.md) — Full repository documentation index.
