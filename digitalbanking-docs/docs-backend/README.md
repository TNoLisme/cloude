# Backend Documentation (`docs-backend/`)

> **Dự án:** Digital Banking Platform Simulator (Single-Bank, Core Simulated)
> **Phạm vi:** Bộ tài liệu tham chiếu triển khai Backend (Java 21 + Spring Boot 3.3 + PostgreSQL 16)
> **Trạng thái thư mục:** Hoàn thành — v1.0 (15 tài liệu chuyên biệt)
> **Cập nhật:** 07/11/2026

---

## 1. Mục đích & Triết lý Tổ chức

`docs-backend/` là bộ tài liệu **tham chiếu thực thi trực tiếp (Implementation Reference Suite)** dành cho lập trình viên Backend và **Coding Agent**. Toàn bộ nội dung được trích xuất, chuẩn hóa và tổng hợp từ hơn 17,000 dòng đặc tả trong `docs-common/` và `openapi/digital-banking-v1.yaml` thành **15 tệp chuyên biệt** (~200–400 dòng/tệp) theo đúng ranh giới kiến trúc **Modular Monolith**.

**Mục tiêu cốt lõi:**
- **Đủ ngữ cảnh trong 2 tệp (Zero Context-Switching):** Khi code bất kỳ một module nào, Developer hoặc Coding Agent chỉ cần đọc **`00-architecture-and-conventions.md`** + **tệp của module đó trong `modules/`** là có đủ 100% thông tin về bảng DB sở hữu, API endpoints, ranh giới transaction (`T0–T3`), thứ tự khóa chống deadlock, mã lỗi, sự kiện audit và checklist kiểm thử.
- **Không nhập nhằng (Zero Ambiguity):** Các giá trị đánh dấu `(đề xuất)` trong `docs-common` đã được chuẩn hóa thành các tham số cấu hình mặc định trong `application.yml` (`@ConfigurationProperties`).

---

## 2. Cấu trúc Thư mục & Danh mục 15 Tài liệu Backend

```text
docs-backend/
├── README.md                                          # Chỉ mục tổng thể & Hướng dẫn đính kèm ngữ cảnh cho Coding Agent
├── 00-architecture-and-conventions.md                 # [BẮT BUỘC ĐỌC TRƯỚC] Kiến trúc 11 modules, T0-T3, bất biến toàn cục
├── database/
│   ├── 01-schema-and-ddl-reference.md                 # DDL chuẩn của 20 bảng nghiệp vụ trong schema banking + Triggers
│   ├── 02-locking-and-sql-patterns.md                 # Thứ tự khóa toàn cục chống deadlock & 16 mẫu SQL (A.1 - A.16)
│   └── 03-flyway-and-seed-strategy.md                 # Chiến lược Flyway V1-V6, phân quyền DB role & dữ liệu seed chuẩn
├── api/
│   ├── 01-rest-conventions-and-pipelines.md           # Security Filter Chain, Envelopes, Headers, Idempotency & Step-up
│   └── 02-error-catalog-and-mapping.md                # Danh mục 30+ ErrorCode, HTTP Status & SQLSTATE mapping
├── modules/
│   ├── 01-common-and-security-core.md                 # Module common: Filters, AES-256-GCM, HMAC, Argon2id, Error Handler
│   ├── 02-identity-and-auth.md                        # Module identity: Login, Lockout, Device MFA, Opaque Session, Step-up
│   ├── 03-customer-and-account.md                     # Module customer & account: Ownership, PII Masking, Lock/Debit/Credit Port
│   ├── 04-transfer-and-ledger.md                      # Module transfer & ledger: T0->T1->T2->T3, Idempotency, Double-entry, Reconcile
│   ├── 05-limit-and-admin-approval.md                 # Module limit & admin: Hạn mức ngày/lần, Maker-Checker 4 mắt
│   ├── 06-audit-and-traceability.md                   # Module audit: Action Catalog, C1-C4, SHA-256 Hash Chain, Export
│   └── 07-fraud-and-notification.md                   # Module fraud (Savepoint Fail-open) & notification (Transactional Outbox)
├── operations/
│   └── 01-config-observability-runbook.md             # Cấu hình .env, application.yml, JSON Log, Metrics & Runbooks
└── testing/
    └── 01-backend-test-matrix.md                      # Testcontainers PostgreSQL 16, Concurrency Harness & Demo D1-D5
```

### Bảng Trạng thái Chi tiết

| Mã tài liệu | Tệp tài liệu | Phạm vi cốt lõi | Trạng thái |
|---|---|---|---|
| `BE-ARCH-00` | [[digitalbanking-docs/docs-backend/00-architecture-and-conventions.md]] | Kiến trúc Modular Monolith (11 modules), ma trận quyền sở hữu bảng, mô hình `T0–T3`, chuẩn code Java/Spring | **Hoàn thành — v1.0** |
| `BE-DB-01` | [[digitalbanking-docs/docs-backend/database/01-schema-and-ddl-reference.md]] | DDL PostgreSQL 16 đầy đủ cho 20 bảng, PK/FK, Check, Partial Unique Index, Immutability Triggers | **Hoàn thành — v1.0** |
| `BE-DB-02` | [[digitalbanking-docs/docs-backend/database/02-locking-and-sql-patterns.md]] | Ma trận khóa chống Deadlock (`account`, `limit_usage`, `audit:chain`) & 16 câu lệnh SQL chuẩn (`A.1–A.16`) | **Hoàn thành — v1.0** |
| `BE-DB-03` | [[digitalbanking-docs/docs-backend/database/03-flyway-and-seed-strategy.md]] | Thứ tự Flyway `V1..V6`, tách biệt `banking_migrator` vs `banking_app`, dữ liệu Seed tổng hợp cho Demo/Test | **Hoàn thành — v1.0** |
| `BE-API-01` | [[digitalbanking-docs/docs-backend/api/01-rest-conventions-and-pipelines.md]] | Chuỗi Filter, Request/Response Envelope, Pagination, JCS Idempotency, luồng Step-up `428`, tổng hợp 30 APIs | **Hoàn thành — v1.0** |
| `BE-API-02` | [[digitalbanking-docs/docs-backend/api/02-error-catalog-and-mapping.md]] | Danh mục chuẩn hóa `ErrorCode`, HTTP Status, `retryable`, và bảng ánh xạ ngoại lệ SQLSTATE (`55P03`, `40P01`) | **Hoàn thành — v1.0** |
| `BE-MOD-01` | [[digitalbanking-docs/docs-backend/modules/01-common-and-security-core.md]] | Module `common`: `CorrelationAndRequestIdFilter`, `PiiCryptoService` (`AES-256-GCM`), `HmacService`, `GlobalExceptionHandler` | **Hoàn thành — v1.0** |
| `BE-MOD-02` | [[digitalbanking-docs/docs-backend/modules/02-identity-and-auth.md]] | Module `identity`: `API-AUT-01..06`, Opaque Session + CSRF, Lockout 5 lần/15m, Device MFA, Step-up OTP Port | **Hoàn thành — v1.0** |
| `BE-MOD-03` | [[digitalbanking-docs/docs-backend/modules/03-customer-and-account.md]] | Module `customer` & `account`: `API-CUS-01`, `API-ACC-01..02`, `API-ADM-01..02`, `AccountBalancePort` (`FOR UPDATE`) | **Hoàn thành — v1.0** |
| `BE-MOD-04` | [[digitalbanking-docs/docs-backend/modules/04-transfer-and-ledger.md]] | Module `transfer` & `ledger`: `API-TRF-01..02`, `API-TXN-01..02`, điều phối `T0->T1->T2->T3`, Reconciliation Job | **Hoàn thành — v1.0** |
| `BE-MOD-05` | [[digitalbanking-docs/docs-backend/modules/05-limit-and-admin-approval.md]] | Module `limit` & `admin`: `API-LIM-01..03`, `API-ROL-01..02`, `API-APR-01..02`, khóa `limit_usage`, Maker-Checker | **Hoàn thành — v1.0** |
| `BE-MOD-06` | [[digitalbanking-docs/docs-backend/modules/06-audit-and-traceability.md]] | Module `audit`: `API-AUD-01..03`, phân loại `C1–C4`, PII Allowlist, chuỗi băm `SHA-256` dưới advisory lock | **Hoàn thành — v1.0** |
| `BE-MOD-07` | [[digitalbanking-docs/docs-backend/modules/07-fraud-and-notification.md]] | Module `fraud` (`API-FRD-01..02`, 3 rules trong `T2` với Savepoint Fail-open) & `notification` (Outbox Worker `T3`) | **Hoàn thành — v1.0** |
| `BE-OPS-01` | [[digitalbanking-docs/docs-backend/operations/01-config-observability-runbook.md]] | Cấu hình `.env`, `application.yml` đầy đủ, JSON Log, Micrometer Metrics, Scheduled Jobs & Runbooks `R-CON`/`R-AUD` | **Hoàn thành — v1.0** |
| `BE-TST-01` | [[digitalbanking-docs/docs-backend/testing/01-backend-test-matrix.md]] | Testcontainers PostgreSQL 16, `ConcurrentTestHarness`, `FinancialInvariantAssertions` & kịch bản Demo `D1–D5` | **Hoàn thành — v1.0** |

---

## 3. Hướng dẫn Đọc & Gắn Ngữ cảnh cho Coding Agent (`Context Attachment Guide`)

Để đạt hiệu quả cao nhất và tiết kiệm Context Window khi giao việc cho **Coding Agent** (như Claude Code, Cursor, Antigravity), hãy đính kèm đúng các tệp theo từng tác vụ dưới đây:

| Nhiệm vụ Giao cho Coding Agent | Các tệp cần đính kèm vào Context (`@file`) |
|---|---|
| **1. Khởi tạo Project, Cấu hình Spring Boot, Flyway Migrations & Seed Data** | - `00-architecture-and-conventions.md`<br>- `database/01-schema-and-ddl-reference.md`<br>- `database/03-flyway-and-seed-strategy.md`<br>- `operations/01-config-observability-runbook.md` |
| **2. Xây dựng Nền tảng Common, Security Filter Chain, Crypto & Error Handling** | - `00-architecture-and-conventions.md`<br>- `api/01-rest-conventions-and-pipelines.md`<br>- `api/02-error-catalog-and-mapping.md`<br>- `modules/01-common-and-security-core.md` |
| **3. Hiện thực Module `identity` (Login, Session, CSRF, MFA, Step-up OTP)** | - `00-architecture-and-conventions.md`<br>- `modules/01-common-and-security-core.md`<br>- `modules/02-identity-and-auth.md` |
| **4. Hiện thực Module `customer` & `account` (Profile, Accounts, Locking Ports)** | - `00-architecture-and-conventions.md`<br>- `database/02-locking-and-sql-patterns.md`<br>- `modules/03-customer-and-account.md` |
| **5. Hiện thực Module `audit` (Hash Chain Writer, Query, Export, Verification Job)** | - `00-architecture-and-conventions.md`<br>- `modules/06-audit-and-traceability.md` |
| **6. Hiện thực Module `limit` & `admin` (Daily Limits, Maker-Checker Approvals)** | - `00-architecture-and-conventions.md`<br>- `database/02-locking-and-sql-patterns.md`<br>- `modules/05-limit-and-admin-approval.md` |
| **7. Hiện thực Module `fraud` & `notification` (In-process Rules & Outbox Worker)** | - `00-architecture-and-conventions.md`<br>- `modules/07-fraud-and-notification.md` |
| **8. Hiện thực Trái tim Chuyển tiền (`transfer` & `ledger`: `T0 -> T1 -> T2 -> T3`)** | - `00-architecture-and-conventions.md`<br>- `database/02-locking-and-sql-patterns.md`<br>- `modules/04-transfer-and-ledger.md`<br>- `modules/06-audit-and-traceability.md` |
| **9. Viết Integration Tests, Concurrency Tests & Chuẩn bị Bằng chứng Demo `D1–D5`** | - `00-architecture-and-conventions.md`<br>- `testing/01-backend-test-matrix.md`<br>- *(Kèm tệp module đang cần viết test)* |

---

## 4. Liên kết Nguồn Chính thức (`Source of Truth` trong `docs-common/`)

| Mục đích | Tài liệu nguồn |
|---|---|
| Yêu cầu hệ thống (`FR`, `NFR`, `AC`) | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Phân tích & Thiết kế Cơ sở dữ liệu | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Ngăn xếp Công nghệ | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| Kiến trúc Hệ thống tổng thể | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Hợp đồng Ngữ nghĩa API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Hợp đồng OpenAPI 3.1 (Máy đọc) | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| Thiết kế chuyên sâu Audit & Traceability | [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] |
| Thiết kế chuyên sâu Consistency & Concurrency | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] |
| Thiết kế chuyên sâu Security | [[digitalbanking-docs/docs-common/design/04. security.md]] |
| README gốc của kho tài liệu | [[digitalbanking-docs/README.md]] |
