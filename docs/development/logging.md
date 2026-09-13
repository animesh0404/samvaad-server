# Samvaad Operational Logging Policy

> **Status:** Current operational policy for the server. Full immutable audit/event history is a separate future concern.

## Purpose

Samvaad logging should make meaningful application behavior, security/authentication activity, and operational failures observable without turning every low-level database operation into noise.

## What to log

Log meaningful business/application events at service-layer business boundaries, especially when an operation changes or attempts to change important domain state.

Useful security and authentication events should also be logged, including significant authentication failures and security-relevant decisions where the event provides operational value.

Relevant log lines should include enough context to trace the business operation across application components. Use a consistent correlation/trace identifier for that purpose.

## What not to log mechanically

Do not add routine logging to every repository getter, CRUD method, or low-level persistence operation merely because it executes. Repository-level logging should be used when it provides specific diagnostic value rather than as a blanket rule.

## Sensitive data

The following must never be written to logs:

- passwords
- access tokens
- refresh tokens
- equivalent authentication or credential secrets

User data, message content, and other sensitive information should be logged only when there is a clear operational justification. Prefer identifiers and non-sensitive metadata over raw content.

## Log categories

Keep the distinction between:

- **Business/application logging** — meaningful domain operations and outcomes.
- **Security/authentication logging** — useful authentication and security events.
- **Infrastructure/debug logging** — implementation diagnostics used when investigating technical failures.

The category should be apparent from the logger/context and should not be used as a reason to expose secrets or unnecessary sensitive data.

## Implementation

The server uses Spring Boot's SLF4J/Logback logging stack with two operational appenders: console output and a rolling file output. Both patterns identify the application as `[samvaad-server]` and carry the MDC `traceId` alongside timestamp, level, thread, logger, and message fields.

HTTP correlation is established by the correlation filter and STOMP correlation context is established by the STOMP interception path. The correlation context is carried through MDC; the logging aspect does not create trace identifiers.

Selective service-layer business operations are annotated with `@OperationalLog`. `OperationalLoggingAspect` provides a generic DEBUG-level envelope containing the operation name, `SUCCESS`/`FAILURE` outcome, and duration; failures additionally include the exception type and are rethrown unchanged. The aspect does not log arguments, return values, or sensitive data. Domain-specific INFO/WARN events remain explicit in the owning services so business meaning is not duplicated by the generic envelope.

## File rotation and retention

Operational file logging uses configuration-driven size-based rolling with compressed archives and bounded retention.

The policy is:

- configure the maximum log-file size rather than allowing unbounded files
- rotate logs when the configured size limit is reached
- compress rolled archives
- retain a bounded number of rolled files
- remove the oldest rolled files when the retention limit is exceeded
- default retention target: **50 rolled files**

The size limit and retention count remain configurable through application logging configuration. The current default maximum active file size is **10MB**.

## Audit boundary

Operational logging is not a full audit/event-history system. Logs are intended for application operations, troubleshooting, and useful security observability. Requirements for immutable, queryable, long-term audit history or domain event storage remain separate architectural work and are not implied by this policy.
