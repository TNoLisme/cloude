# Observability and Debugging Policy

## Goal

Make backend failures and business-flow transitions diagnosable without exposing credentials, OTPs, tokens, account data or unnecessary personal data. Apply this policy to every implementation phase.

## Request correlation

- `CorrelationIdFilter` accepts a valid UUID from `X-Correlation-Id`; missing or invalid values are replaced with a generated UUID.
- Return the effective value in response header `X-Correlation-Id`.
- Add `correlationId` to MDC for request lifetime and clear it in `finally`.
- Include correlation ID in error responses and structured log events.
- Pass correlation ID explicitly across asynchronous boundaries if introduced later. No asynchronous broker is part of MVP.

## What to log

Use structured SLF4J events. Prefer stable event names and bounded fields over interpolating request bodies.

For each request/important use-case boundary, log as applicable:

- `event`: stable technical event name, such as `request.completed`, `auth.login.rejected`, `transfer.state_changed`.
- `correlationId`: request correlation UUID.
- `operation`: HTTP method + route template or application use-case name. Do not log raw URL query strings.
- `outcome`: success, rejected, failed, replayed, or other bounded enum.
- `durationMs`: elapsed time from a monotonic clock.
- `httpStatus` and stable internal error code where applicable.
- Safe generated resource IDs: user/account/transfer/OTP challenge IDs only when necessary for debugging and policy allows them. IDs are not secrets but still require need-to-know access and retention limits.
- State transition: entity type, old state, new state, and transition reason code. Do not log free-form reason text by default.
- Validation metadata: field name and validation code only. Never log submitted value.
- Database failure metadata: exception class, SQLState/vendor code, operation and correlation ID. Do not log SQL bind values or raw exception messages into broadly accessible logs.
- Retry/idempotency result: operation and outcome (`new`, `replay`, `payload_conflict`). Never log raw idempotency key or payload hash.
- External adapter result: adapter name, bounded outcome and duration. Do not log message payload, OTP or provider credentials.

Log expected business rejections at `INFO` or `WARN` only where operationally useful. Log unexpected failures once at the boundary with stack trace in restricted server logs when policy allows; client response always remains sanitized. Avoid duplicate stack traces at controller, service and repository layers.

## Never log

- Passwords, password hashes, raw PINs, PIN hashes.
- OTP values, OTP hashes, OTP delivery payloads.
- Access/refresh/CSRF tokens, signing keys, credentials, cookies or complete Authorization headers.
- Full account numbers. Use account UUID only when necessary; masked account number only when needed for customer-facing display logs.
- Full request/response bodies or generic DTO serialization.
- Raw phone number or email. Prefer internal UUID and identifier type; if operationally essential, use a reviewed masked value with strict access and retention.
- Free-form customer/operator text, including account block reasons, transfer memo, names, address, audit summaries containing PII.
- Raw SQL parameter values, connection strings, environment variables or exception messages that may embed PII/secrets.

Do not hash secrets to make them safe for logs. Hashes can still enable correlation or offline guessing.

## Business-flow logging

Add logs at meaningful boundaries, not every method call:

1. Request accepted/rejected after validation and rate-limit decision.
2. Authentication/authorization outcome without credentials or raw identifiers.
3. Transaction start only when useful; include operation and correlation ID.
4. State transition and commit outcome. Distinguish attempted transition from committed transition.
5. External adapter dispatch result after the database transaction; include challenge/resource ID only if necessary, never OTP content.
6. Risk evaluation result after commit; do not affect transfer success when evaluation fails.

A state-changing log emitted before commit must be labeled as attempted. Emit `committed` only after transaction commit succeeds. In transaction-synchronization or after-commit handlers, include the same correlation ID and resource ID.

Do not use DEBUG logging as an excuse to print secrets or whole payloads. Sensitive-data restrictions apply at every log level, local profile included.

## Errors and stack traces

- API errors use the OpenAPI `Problem` shape and `application/problem+json`.
- Return safe, actionable detail; never include exception message, SQL, stack trace, token, OTP, or credential value.
- Unexpected errors map to `500 INTERNAL_ERROR`; log correlation ID, exception type and restricted diagnostic stack trace once.
- Database unavailable maps health to `503` with exactly `{status,timestamp}`. Do not reveal host, schema, SQL, database version or credentials.
- Preserve root cause in server-side diagnostics through the logger's throwable argument only when log access and retention are controlled. Do not turn full exception strings into structured public fields.

## Metrics and cardinality

Use bounded labels only: route template, HTTP method, status class, stable outcome/error code. Never label metrics by correlation ID, user ID, account ID, phone, email, transfer ID or idempotency key.

Track request count/latency/errors, database pool health, transfer outcomes, idempotency replay/conflict, lock wait/deadlock/timeout, and risk-flag count as those phases are implemented. Measure; do not assert performance before benchmark evidence.

## Tests and review

For each new log event or error path:

- Test required stable fields and correlation propagation where practical.
- Capture logs for representative success, validation, authorization and unexpected-failure paths.
- Assert absence of known secrets and raw submitted credentials/OTP in captured output.
- Review log levels, duplicate stack traces, cardinality, and free-form metadata.
- Use synthetic identities/data only.

Before delivery, review logging changes and captured output for passwords, PINs, OTPs, tokens, hashes, account numbers, phone/email, SQL parameters and request body serialization.

## Phase 01 current behavior

Phase 01 currently emits a sanitized `500 INTERNAL_ERROR` response and logs correlation ID plus exception type. It does not yet log request parameters or business state because Phase 01 has no business endpoints. Future phases must add bounded structured boundary/state-transition logs under this policy. Never add raw parameters or full payload logging.
