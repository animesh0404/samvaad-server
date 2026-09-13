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

## File rotation and retention

Operational file logging uses configuration-driven size-based rolling with compressed archives and bounded retention.

The policy is:

- configure the maximum log-file size rather than allowing unbounded files
- rotate logs when the configured size limit is reached
- compress rolled archives
- retain a bounded number of rolled files
- remove the oldest rolled files when the retention limit is exceeded
- default retention target: **50 rolled files**

The size limit and retention count remain configurable through application logging configuration.

## Audit boundary

Operational logging is not a full audit/event-history system. Logs are intended for application operations, troubleshooting, and useful security observability. Requirements for immutable, queryable, long-term audit history or domain event storage remain separate architectural work and are not implied by this policy.
