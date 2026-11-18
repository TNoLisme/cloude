# Docs Common (`docs-common/`)

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `DOC-COMMON-INDEX`
> **Phiên bản:** 1.0
> **Phạm vi:** Tài liệu yêu cầu, kiến trúc, cơ sở dữ liệu, API, kiểm thử và thiết kế chuyên sâu
> **Cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành

## 1. Mục đích

`docs-common/` là nguồn sự thật chính thức (`Single Source of Truth`) cho toàn bộ các đặc tả dùng chung của dự án **Digital Banking MVP**: bối cảnh dự án, yêu cầu nghiệp vụ, đặc tả hệ thống, thiết kế cơ sở dữ liệu, ngăn xếp công nghệ, kiến trúc hệ thống, hợp đồng API, hạ tầng triển khai, kế hoạch kiểm thử và 5 tài liệu thiết kế kỹ thuật chuyên sâu.

**Hướng dẫn đọc nhanh:**
- Bắt đầu từ `01. Introduction.md` để nắm bối cảnh, phạm vi và quy ước định danh.
- Đọc `02. BRD.md` cho yêu cầu nghiệp vụ và `03. SRS.md` cho hành vi chi tiết của hệ thống.
- Đọc `04`–`07` để nắm mô hình dữ liệu, công nghệ, kiến trúc và hợp đồng REST API.
- Đọc `design/01–05` cho các cơ chế chuyên sâu về kiểm toán, đồng thời/nhất quán, phát hiện gian lận, bảo mật và hợp đồng giao tiếp nội bộ giữa 11 module Backend.
- Đọc `08`–`11` cho hạ tầng triển khai, kế hoạch kiểm thử, lộ trình 8 tuần và phân công nhân sự.

## 2. Danh mục tài liệu

### 2.1. Bộ tài liệu đặc tả chính (`01` – `11`)

| # | Tài liệu | Nội dung chính | Vai trò | Trạng thái |
|---|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/01. Introduction.md]] | Bối cảnh, mục tiêu, phạm vi, stakeholder, glossary, quy ước ID | Nền tảng dự án | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/02. BRD.md]] | Business requirements, quy trình nghiệp vụ, business rules, tham số chuẩn | Nguồn yêu cầu nghiệp vụ | Hoàn thành — v1.0 |
| 03 | [[digitalbanking-docs/docs-common/03. SRS.md]] | Functional/non-functional requirements, use case, acceptance criteria | Nguồn hành vi hệ thống | Hoàn thành — v1.0 |
| 04 | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] | ERD, schema 16 bảng, ràng buộc, indexing, phân quyền và vòng đời dữ liệu | Nguồn mô hình dữ liệu | Hoàn thành — v1.0 |
| 05 | [[digitalbanking-docs/docs-common/05. Technical stack.md]] | Lựa chọn công nghệ, phiên bản baseline, cấu hình bảo mật và rủi ro | Nguồn công nghệ | Hoàn thành — v1.0 |
| 06 | [[digitalbanking-docs/docs-common/06. System architecture.md]] | Sơ đồ C4, luồng runtime `T0–T3`, topology triển khai, ADR | Nguồn kiến trúc | Hoàn thành — v1.0 |
| 07 | [[digitalbanking-docs/docs-common/07. API contracts.md]] | Ngữ nghĩa REST API, danh mục mã lỗi RFC 9457, idempotency, step-up | Nguồn hợp đồng API | Hoàn thành — v1.0 |
| 08 | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] | Hạ tầng Docker/Nginx/PostgreSQL, CI/CD, backup/DR, runbook vận hành | Nguồn hạ tầng & vận hành | Hoàn thành — v1.0 |
| 09 | [[digitalbanking-docs/docs-common/09. Test plan.md]] | Chiến lược kiểm thử, kịch bản `D1–D5`, ma trận truy vết `AC/NFR` | Nguồn chiến lược kiểm thử | Hoàn thành — v1.0 |
| 10 | [[digitalbanking-docs/docs-common/10 Master timelines.md]] | Lộ trình 8 tuần, milestone, WBS, RACI và cổng chất lượng | Nguồn kế hoạch tổng thể | Hoàn thành — v1.0 |
| 11 | [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] | Cơ cấu nhân sự, phân công owner 11 module Backend, Frontend và hạ tầng | Nguồn phân công nhân sự | Hoàn thành — v1.0 |
| — | [[digitalbanking-docs/docs-common/Requirement Common.md]] | Đề bài tóm tắt các yêu cầu trọng yếu ban đầu | Tham khảo gốc | Tham khảo |

### 2.2. Bộ tài liệu thiết kế chuyên sâu (`design/01` – `05`)

| # | Tài liệu | Mã tài liệu | Phạm vi thiết kế | Trạng thái |
|---|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] | `DESIGN-AUD-01` | Nhật ký kiểm toán bất biến, chuỗi băm SHA-256, correlation ID, retention 5 năm | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | `DESIGN-CON-02` | Ranh giới giao dịch `T0–T3`, idempotency, thứ tự khóa toàn cục, phục hồi lỗi | Hoàn thành — v1.0 |
| 03 | [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] | `DESIGN-FRD-03` | Quy tắc phát hiện gian lận (`LARGE_VALUE`, `HIGH_FREQUENCY`), cô lập savepoint, thẩm định cờ | Hoàn thành — v1.0 |
| 04 | [[digitalbanking-docs/docs-common/design/04. security.md]] | `DESIGN-SEC-04` | Threat model STRIDE, Opaque Session + CSRF, Argon2id, FIDO2, RBAC/Maker-Checker, AES-GCM | Hoàn thành — v1.0 |
| 05 | [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] | `DESIGN-MOD-05` | Hợp đồng giao tiếp nội bộ (`<provider>.api`), transaction propagation và kiểm thử 11 module | Hoàn thành — v1.0 |

### 2.3. Nhật ký công việc giai đoạn phân tích & thiết kế (`work/`)

| Thư mục / Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/docs-common/work/README.md]] | Hướng dẫn quản lý nhật ký công việc theo tuần | Hoàn thành — v1.0 |
| [[digitalbanking-docs/docs-common/work/week1/README.md]] | Tổng hợp kết quả Tuần 1 (Phân tích yêu cầu, BRD, SRS, DB, Stack, Kiến trúc) | Hoàn thành — v1.0 |
| [[digitalbanking-docs/docs-common/work/week2/README.md]] | Tổng hợp kết quả Tuần 2 (API Contracts, Design `01–05`, OpenAPI, Test & Deployment Plan) | Hoàn thành — v1.0 |

## 3. Thứ tự đọc và phụ thuộc giữa các tài liệu

### 3.1. Luồng đọc chuẩn

`01 → 02 → 03 → 04 → 05 → 06 → 07 → design/01 → design/02 → design/03 → design/04 → design/05 → 08 → 09 → 10 → 11`

### 3.2. Ma trận phụ thuộc

| Tài liệu | Tài liệu đầu vào | Cung cấp đầu vào cho |
|---|---|---|
| `01. Introduction` | `Requirement Common` | `02`, `03`, `10` |
| `02. BRD` | `01` | `03`, `09`, `10` |
| `03. SRS` | `01`, `02` | `04`, `06`, `07`, `09`, `design/01–05` |
| `04. Database design & analysis` | `03` | `06`, `design/01–05`, `docs-backend` |
| `05. Technical stack` | `03` | `06`, `08`, `09`, `docs-backend`, `docs-frontend` |
| `06. System architecture` | `03`, `04`, `05` | `07`, `08`, `design/01–05`, `docs-backend` |
| `07. API contracts` | `03`, `06` | `openapi/`, `design/04–05`, `docs-backend`, `docs-frontend`, `09` |
| `08. Deployment infrastructure` | `05`, `06` | `09`, `docs-backend/deployment/` |
| `09. Test plan` | `02`, `03`, `07`, `design/01–05` | Nghiệm thu `D1–D5`, `10` |
| `10 Master timelines` | `01`, `02`, `03`, `09` | `11`, điều phối thực thi |
| `11. Resource & Staffing Plan` | `06`, `08`, `09`, `10` | Phân công nhân sự và ghép tích hợp |

## 4. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Bắt đầu đọc tổng quan | [[digitalbanking-docs/docs-common/01. Introduction.md]] |
| Yêu cầu nghiệp vụ | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Đặc tả hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Mô hình cơ sở dữ liệu | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Hợp đồng REST API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Kiến trúc hệ thống | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Thiết kế chuyên sâu & Hợp đồng 11 module | [[digitalbanking-docs/docs-common/design/README.md]] |
| README gốc của kho tài liệu | [[digitalbanking-docs/README.md]] |
