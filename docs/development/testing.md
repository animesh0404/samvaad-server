# Testing Guide

This document describes the testing approach, test structure, and verification commands used in the Samvaad backend server.

---

## Testing Approach

The test suite in `src/test` is organized into distinct testing layers:

### 1. Unit Tests (Isolated Logic & Mappers)

Unit tests run quickly without Spring application context or external services. They verify deterministic domain transformations:

- **`UserMapperTest`** (`src/test/java/com/samvaad/samvaad_server/user/UserMapperTest.java`):  
  Tests bidirectional mapping between `UserDto` and `User` entities, verifying username and email preservation.
- **`UserProfileMapperTest`** (`src/test/java/com/samvaad/samvaad_server/user/userprofile/UserProfileMapperTest.java`):  
  Tests `UserProfileMapper.updateEntity` partial updates from `UserProfileUpdateDto` onto existing `UserProfile` entities.

### 2. Web Layer Tests (MockMvc & Controller Isolation)

Controller tests verify endpoint routing, HTTP status codes, request/response JSON serialization, and service interactions in isolation:

- **`UserProfileControllerTest`** (`src/test/java/com/samvaad/samvaad_server/user/userprofile/UserProfileControllerTest.java`):  
  Uses `@ExtendWith(MockitoExtension.class)` and `MockMvcBuilders.standaloneSetup(...)` to test `PATCH /api/users/{userId}/profile` without booting the full Spring context. Verifies payload deserialization, HTTP 200 responses, returned JSON fields, and mock service method invocations.

### 3. Integration & Context Tests (Testcontainers & Spring Boot)

Full-stack integration tests verify that the Spring Boot context, JPA mappings, and Liquibase migrations configure correctly against a live PostgreSQL instance:

- **`SamvaadServerApplicationTests`** (`src/test/java/com/samvaad/samvaad_server/SamvaadServerApplicationTests.java`):  
  Annotated with `@SpringBootTest` and `@Import(TestcontainersConfiguration.class)`. Boots the Spring container and runs Liquibase migrations against a containerized PostgreSQL database to verify application startup and context initialization.
- **`TestcontainersConfiguration`** (`src/test/java/com/samvaad/samvaad_server/TestcontainersConfiguration.java`):  
  Configures a `PostgreSQLContainer` bean with `@ServiceConnection` using image `postgres:latest`.

### 4. Development Runtime Test Harness

- **`TestSamvaadServerApplication`** (`src/test/java/com/samvaad/samvaad_server/TestSamvaadServerApplication.java`):  
  Executable test application entry point (`main`) that runs `SamvaadServerApplication` with `TestcontainersConfiguration`, allowing developers to run the server with an automated ephemeral database.

---

## Running Tests

Tests are executed using the Gradle wrapper (`./gradlew`):

### Run the Full Test Suite
```bash
./gradlew test
```

### Run a Specific Test Class
```bash
./gradlew test --tests com.samvaad.samvaad_server.user.UserMapperTest
```

### Run Tests in a Specific Package
```bash
./gradlew test --tests "com.samvaad.samvaad_server.user.*"
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
- [Documentation Map](../README.md) — Full repository documentation index.
