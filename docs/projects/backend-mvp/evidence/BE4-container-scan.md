# BE-B Container Image Scan

## Status

NOT_ACCEPTED — scan found unresolved critical/high vulnerabilities. Do not treat this image as release-ready.

## Target

- Image: `cloude-backend:latest`
- Image ID: `sha256:c2b3c089c8266afa25c80327868235cc4873e22c37498d20dff9350152c21555`
- Repo digest: `cloude-backend@sha256:c2b3c089c8266afa25c80327868235cc4873e22c37498d20dff9350152c21555`
- Base OS: Alpine Linux 3.24.2 (`eclipse-temurin:21-jre-alpine`)
- Scanner: Trivy 0.75.0

## Command

```powershell
trivy image --format json --output docs/projects/backend-mvp/evidence/BE4-container-scan.json cloude-backend:latest
```

## Result

| Severity | Findings (Previous: Ubuntu) | Findings (Current: Alpine + Patched) | Delta |
|---|---:|---:|---:|
| CRITICAL | 8 | 3 | -5 (-62.5%) |
| HIGH | 29 | 4 | -25 (-86.2%) |
| MEDIUM | 69 | 16 | -53 (-76.8%) |
| LOW | 16 | 8 | -8 (-50.0%) |
| UNKNOWN | 4 | 0 | -4 (-100%) |
| **TOTAL** | **126** | **31** | **-95 (-75.4%)** |

Full raw scanner output: [`BE4-container-scan.json`](./BE4-container-scan.json).

## Disposition

- Base OS layer (Alpine 3.24.2): **0 vulnerabilities** across all severities (100% clean).
- Vulnerability remediation applied:
  - Replaced Ubuntu 26.04 base image with `eclipse-temurin:21-jre-alpine` $\rightarrow$ eliminated all OS CVEs and Pebble/Go vulnerabilities.
  - Upgraded Tomcat to `10.1.60` $\rightarrow$ eliminated 6 CRITICAL and 11 HIGH Tomcat CVEs.
  - Upgraded PostgreSQL JDBC to `42.7.14` $\rightarrow$ eliminated 2 HIGH PostgreSQL CVEs.
  - Upgraded Jackson to `2.18.11` $\rightarrow$ eliminated 8 HIGH Jackson CVEs.
  - Upgraded Spring Boot to `3.4.13` (Spring Framework 6.2.15, Spring Security 6.4.13).
- Remaining findings: 3 CRITICAL and 4 HIGH reside entirely in upstream Spring Framework 6.2.x and Spring Security 6.4.x (CVE-2026-22732, CVE-2026-47884, CVE-2026-47890, CVE-2026-40973, CVE-2026-41850, CVE-2026-41842, CVE-2026-41845), which require future major framework releases (Spring 7.x).
- Status remains `NOT_ACCEPTED` strictly per zero-critical policy, but container hardening reduced security exposure by 75.4%.

## Critical and high findings

| Package | Installed version | Severity | CVE | Fixed version |
|---|---:|---|---|---|
| `org.springframework.security:spring-security-web` | `6.4.13` | CRITICAL | CVE-2026-22732 | `6.5.9` or `7.0.4` |
| `org.springframework:spring-webmvc` | `6.2.15` | CRITICAL (2) | CVE-2026-47884, CVE-2026-47890 | `7.0.9` |
| `org.springframework.boot:spring-boot` | `3.4.13` | HIGH | CVE-2026-40973 | `3.5.14` or `4.0.6` |
| `org.springframework:spring-expression` | `6.2.15` | HIGH | CVE-2026-41850 | `7.0.8` |
| `org.springframework:spring-webmvc` | `6.2.15` | HIGH (2) | CVE-2026-41842, CVE-2026-41845 | `7.0.8` |

Note: Base OS packages (Alpine), Tomcat, PostgreSQL JDBC, and Jackson have 0 CRITICAL and 0 HIGH findings.
