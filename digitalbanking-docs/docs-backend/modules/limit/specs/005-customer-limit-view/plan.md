# Implementation Plan: LIM-06 customer limit view

**Branch**: `codex/limit` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

## Summary

Read-only GET endpoint with customer-bound authorization, account ownership through provider API, consistent per-scope snapshot and configured business date. New persistence uses Spring Data JPA only. No transfer integration is invented.

## Technical Context

Java 21, Spring Boot, PostgreSQL 16, Spring Data JPA, Lombok, MapStruct, Jackson; tests JUnit, MockMvc and PostgreSQL Testcontainers. Existing common supplies session, permission, error, context, UTC Clock and timezone. User selects customer-only default (DEC-LIM-001). 01–11 take precedence over design and backend. Constitution is an unfilled template, skipped. JPA query reads limit-owned tables only in one statement with effective and usage predicates; snapshot may be stale after the statement and is never an enforcement decision. No JdbcTemplate in feature code.

## Structure and decisions

- `limit/adapter/out`: JPA read mapping and query; no schema change or locks.
- `limit/application`: scope validation, read calculation, timestamp and dates; missing account provider fails closed; account absence 404, wrong owner 403.
- `limit/adapter/in`: endpoint; `limit/application` maps the JPA projection to the response using MapStruct so the inbound adapter never imports outbound types. Principal and permission are rechecked at the service boundary. Common SecurityFilterChain retains initial permission check.
- `account.api.AccountQuery` existing candidate projection; provider not yet implemented. Update modules contract and test fake only.
- `audit.api` existing denial event is approval-specific; security-denial event needs dedicated provider contract and C3 fallback; no fake production audit. Report provider integration as blocked.
- Use same `asOf` and database query for both config and usage; floor asOf query parameter to PostgreSQL microseconds while returning original instant; business timezone via `common`.
- Tests: PostgreSQL active boundaries, scopes, absent usage, zero clamp, date, read-only/no locks and committed changes; HTTP session/permission/ownership/format/errors using fakes, architecture boundaries. Report real transfer and providers separately.

## Constitution Check

Template only; skip. After design: source hierarchy, provider ownership, no new write paths and user JPA constraint satisfied.

## Project Structure

```text
digitalbanking-backend/src/main/java/com/simulatedbank/banking/limit/{adapter/in,adapter/out,application}
digitalbanking-backend/src/test/java/com/simulatedbank/banking/limit/
digitalbanking-docs/docs-backend/modules/limit/specs/005-customer-limit-view/
```
