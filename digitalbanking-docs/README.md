# Digital Banking Documentation

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `DOC-ROOT-INDEX`
> **Phiên bản:** 1.0
> **Vai trò:** Hướng dẫn đọc, cập nhật và quản lý bộ tài liệu kỹ thuật
> **Cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành

## 1. Mục đích

Kho tài liệu này tập hợp toàn bộ yêu cầu nghiệp vụ, đặc tả hệ thống, thiết kế cơ sở dữ liệu, kiến trúc, hợp đồng API, thiết kế chuyên sâu và tài liệu triển khai của hệ thống **Digital Banking MVP**.

Tài liệu chỉ mục này giúp:
- Xác định tài liệu nào là **nguồn chính thức**, tài liệu nào dùng để tham khảo hoặc lưu trữ lịch sử.
- Tra cứu **trạng thái hiện tại** của từng tài liệu.
- Chọn đúng **thứ tự đọc** theo vai trò trong dự án.
- Duy trì tính nhất quán và truy vết (`traceability`) xuyên suốt giữa yêu cầu, thiết kế và mã nguồn.

## 2. Nguyên tắc nguồn chính thức

| Thư mục | Vai trò | Nguồn chính thức? |
|---|---|---|
| `docs-common/` | Yêu cầu, kiến trúc, dữ liệu, API, thiết kế chuyên sâu (`design/01–05`) | **Có** |
| `openapi/` | Hợp đồng API máy đọc (`OpenAPI 3.1`) | **Có** (đồng bộ ngữ nghĩa với `07. API contracts.md`) |
| `docs-backend/` | Tài liệu kiến trúc, cấu hình và chi tiết 11 module Backend | **Có** (trong phạm vi hiện thực Backend) |
| `docs-frontend/` | Tài liệu kiến trúc, màn hình và đặc tả tính năng Frontend | **Có** (trong phạm vi hiện thực Frontend) |
| `docs-draft/` | Bản nháp và bản lưu lịch sử rà soát | Không |
| `docs-references/` | Tài liệu tham khảo kỹ thuật bên ngoài | Không |

**Thứ tự ưu tiên khi đối chiếu:**
1. **Yêu cầu nghiệp vụ và hành vi hệ thống:** `02. BRD.md` và `03. SRS.md`.
2. **Mô hình dữ liệu, kiến trúc và hợp đồng API:** `04. Database design & analysis.md`, `05. Technical stack.md`, `06. System architecture.md`, `07. API contracts.md` và `openapi/digital-banking-v1.yaml`.
3. **Thiết kế chuyên sâu và giao tiếp nội bộ:** `docs-common/design/01–05` và `DECISIONS.md`.
4. **Tài liệu triển khai chi tiết:** `docs-backend/` và `docs-frontend/`.

## 3. Cấu trúc thư mục

```text
digitalbanking-docs/
├── README.md
├── openapi/
│   ├── README.md
│   └── digital-banking-v1.yaml
├── docs-common/
│   ├── README.md
│   ├── 01. Introduction.md
│   ├── 02. BRD.md
│   ├── 03. SRS.md
│   ├── 04. Database design & analysis.md
│   ├── 05. Technical stack.md
│   ├── 06. System architecture.md
│   ├── 07. API contracts.md
│   ├── 08. Deployment infrastructure.md
│   ├── 09. Test plan.md
│   ├── 10 Master timelines.md
│   ├── 11. Resource & Staffing Plan.md
│   ├── Requirement Common.md
│   ├── design/
│   │   ├── README.md
│   │   ├── 01. audit-traceability.md
│   │   ├── 02. consistency-concurrency.md
│   │   ├── 03. fraud-detection-rules.md
│   │   ├── 04. security.md
│   │   └── 05. Modules contract.md
│   └── work/
│       ├── README.md
│       ├── week1/README.md
│       └── week2/README.md
├── docs-backend/
│   ├── README.md
│   ├── 00. architecture & conventions.md
│   ├── deployment/
│   ├── modules/
│   └── workofteam/
├── docs-frontend/
│   ├── README.md
│   ├── management/
│   ├── standards/
│   ├── features/
│   ├── design/
│   ├── guides/
│   ├── decisions/
│   ├── templates/
│   └── evidence/
├── docs-draft/
│   └── README.md
└── docs-references/
    └── README.md
```

## 4. Trạng thái tài liệu

### 4.1. Quy ước trạng thái

| Trạng thái | Ý nghĩa |
|---|---|
| **Hoàn thành** | Đã chuẩn hoá đầy đủ nội dung kỹ thuật và đồng bộ với baseline dự án |
| **Đang phát triển** | Đang mở rộng song song với quá trình hiện thực mã nguồn |
| **Lưu trữ** | Bản nháp lịch sử, chỉ dùng để đối chiếu quá trình |
| **Tham khảo** | Tài liệu kiến thức nền tảng hoặc đề bài gốc |

### 4.2. Tài liệu chung (`docs-common`)

| # | Tài liệu | Nội dung chính | Trạng thái |
|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/01. Introduction.md]] | Bối cảnh, mục tiêu, phạm vi, stakeholder, quy ước tài liệu | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/02. BRD.md]] | Yêu cầu nghiệp vụ, quy trình, business rules, tham số baseline | Hoàn thành — v1.0 |
| 03 | [[digitalbanking-docs/docs-common/03. SRS.md]] | Yêu cầu chức năng/phi chức năng, use case, acceptance criteria | Hoàn thành — v1.0 |
| 04 | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] | ERD, schema, ràng buộc, indexing, vòng đời dữ liệu | Hoàn thành — v1.0 |
| 05 | [[digitalbanking-docs/docs-common/05. Technical stack.md]] | Lựa chọn công nghệ, phiên bản baseline, kiểm soát rủi ro | Hoàn thành — v1.0 |
| 06 | [[digitalbanking-docs/docs-common/06. System architecture.md]] | Kiến trúc hệ thống, C4, luồng runtime, deployment topology, ADR | Hoàn thành — v1.0 |
| 07 | [[digitalbanking-docs/docs-common/07. API contracts.md]] | Hợp đồng ngữ nghĩa API, mô hình lỗi, idempotency, step-up | Hoàn thành — v1.0 |
| 08 | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] | Hạ tầng triển khai, CI/CD, môi trường, backup/DR, runbook | Hoàn thành — v1.0 |
| 09 | [[digitalbanking-docs/docs-common/09. Test plan.md]] | Chiến lược kiểm thử, kịch bản `D1–D5`, dữ liệu và tiêu chí nghiệm thu | Hoàn thành — v1.0 |
| 10 | [[digitalbanking-docs/docs-common/10 Master timelines.md]] | Kế hoạch tổng thể 8 tuần, milestone, phân công WBS/RACI | Hoàn thành — v1.0 |
| 11 | [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] | Nhân sự, phân công 11 module Backend, Frontend và hạ tầng | Hoàn thành — v1.0 |
| — | [[digitalbanking-docs/docs-common/Requirement Common.md]] | Đề bài/yêu cầu cốt lõi ban đầu | Tham khảo |

### 4.3. Thiết kế chuyên sâu (`docs-common/design`)

| # | Tài liệu | Phạm vi | Trạng thái |
|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] | Audit trail bất biến, chuỗi băm SHA-256, correlation ID, retention | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | Ranh giới giao dịch `T0–T3`, idempotency, thứ tự khóa, phục hồi lỗi | Hoàn thành — v1.0 |
| 03 | [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] | Quy tắc phát hiện gian lận, ngưỡng cấu hình, thẩm định cờ, fail-open | Hoàn thành — v1.0 |
| 04 | [[digitalbanking-docs/docs-common/design/04. security.md]] | Threat model STRIDE, authn/authz, mã hóa PII, bảo vệ API | Hoàn thành — v1.0 |
| 05 | [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] | Hợp đồng giao tiếp nội bộ, port/DTO và kiểm thử giữa 11 module | Hoàn thành — v1.0 |

### 4.4. Tài liệu triển khai, hợp đồng máy đọc và công việc

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] | Hợp đồng OpenAPI 3.1 (máy đọc) | Hoàn thành — v1.1.0 |
| [[digitalbanking-docs/docs-backend/README.md]] | Chuẩn kiến trúc, cấu hình triển khai và tài liệu 11 module Backend | Hoàn thành — v1.0 |
| [[digitalbanking-docs/docs-frontend/README.md]] | Phạm vi, tracker, 8 đặc tả tính năng và thiết kế màn hình Frontend | Hoàn thành — v1.2 |
| [[digitalbanking-docs/docs-common/work/README.md]] | Kế hoạch và nhật ký bàn giao theo tuần (`week1`, `week2`) | Hoàn thành — v1.0 |

### 4.5. Lưu trữ và tham khảo

| Thư mục | Vai trò | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/docs-draft/README.md]] | Bản nháp và lịch sử rà soát giai đoạn đầu | Lưu trữ |
| [[digitalbanking-docs/docs-references/README.md]] | Tài liệu tham khảo bên ngoài | Tham khảo |

## 5. Thứ tự đọc theo vai trò

| Vai trò | Thứ tự đọc khuyến nghị |
|---|---|
| Business Analyst / Product Owner | `01` → `02` → `03` → `09` → `10` |
| System Architect | `01` → `03` → `05` → `06` → `design/01–05` → `08` |
| Backend Engineer | `03` → `04` → `05` → `06` → `07` → `design/01–05` → `docs-backend/` → `OpenAPI` |
| Frontend Engineer | `02` → `03` → `07` → `OpenAPI` → `docs-frontend/` |
| QA / Test Engineer | `02` → `03` → `07` → `09` → `design/01–05` |
| DevOps / SRE | `05` → `06` → `08` → `docs-backend/deployment/` |
| Security / Auditor | `02` → `03` → `07` → `design/01, 04` → `09` |

## 6. Quy ước tài liệu

### 6.1. Đặt tên tệp và mã tài liệu

- Tài liệu chung (`docs-common`): `NN. Tên tài liệu.md`.
- Thiết kế chuyên sâu (`docs-common/design`): `NN. kebab-case.md`, có mã tài liệu dạng `DESIGN-<DOMAIN>-NN`.
- Mỗi tài liệu bắt đầu bằng khối metadata:
  ```markdown
  > **Dự án:** Digital Banking MVP
  > **Mã tài liệu:** ...
  > **Phiên bản:** 1.0
  > **Ngày:** 18/11/2026
  > **Trạng thái:** Hoàn thành
  > **Owner:** ...
  > **Reviewer:** ...
  ```

### 6.2. Liên kết và truy vết (`Traceability`)

- Sử dụng wikilink hoặc đường dẫn tương đối chuẩn Markdown để liên kết giữa các tài liệu.
- Mọi yêu cầu, quyết định thiết kế và kịch bản kiểm thử đều truy vết tới mã `BR-*`, `FR-*`, `NFR-*`, `AC-*` và `DEC-*` tương ứng.
- Khi có quyết định kỹ thuật hoặc thay đổi giao tiếp giữa các module, cập nhật đồng bộ tại `DECISIONS.md` và `docs-common/design/05. Modules contract.md`.

## 7. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Đọc tổng quan dự án | [[digitalbanking-docs/docs-common/01. Introduction.md]] |
| Tra cứu yêu cầu nghiệp vụ | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Tra cứu đặc tả hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Tra cứu thiết kế cơ sở dữ liệu | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Tra cứu hợp đồng API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Xem kiến trúc hệ thống | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Xem hợp đồng giao tiếp nội bộ 11 module | [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] |
| Xem hợp đồng OpenAPI 3.1 | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| Xem tài liệu Backend | [[digitalbanking-docs/docs-backend/README.md]] |
