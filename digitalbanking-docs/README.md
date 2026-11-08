# Digital Banking Documentation

> **Dự án:** Digital Banking MVP
> **Vai trò:** Hướng dẫn đọc, cập nhật và quản lý bộ tài liệu
> **Cập nhật:** 03/11/2026

## 1. Mục đích

Kho tài liệu này tập hợp yêu cầu, thiết kế, hợp đồng API, tài liệu triển khai và tài liệu tham khảo của Digital Banking MVP.

README này giúp bạn:

- Xác định tài liệu nào là **nguồn chính thức**, tài liệu nào chỉ để tham khảo hoặc lưu trữ.
- Biết **trạng thái hiện tại** của từng tài liệu trước khi sử dụng.
- Chọn đúng **thứ tự đọc** theo vai trò.
- Cập nhật tài liệu đúng quy trình, tránh tạo bản trùng lặp hoặc mâu thuẫn.

## 2. Nguyên tắc nguồn chính thức

| Thư mục | Vai trò | Nguồn chính thức? |
|---|---|---|
| `docs-common/` | Yêu cầu, kiến trúc, API, thiết kế chuyên sâu | Có |
| `openapi/` | Hợp đồng API máy đọc | Có, nhưng phải khớp `07. API contracts` |
| `docs-backend/` | Tài liệu triển khai backend | Có, trong phạm vi implementation |
| `docs-frontend/` | Tài liệu triển khai frontend | Có, trong phạm vi implementation |
| `docs-draft/` | Bản nháp và bản lưu lịch sử | Không |
| `docs-references/` | Tài liệu tham khảo bên ngoài | Không |

Khi tài liệu mâu thuẫn, ưu tiên theo thứ tự:

1. Yêu cầu nghiệp vụ: `02. BRD` và `03. SRS`.
2. Hành vi API: `07. API contracts` (ngữ nghĩa) và `openapi/digital-banking-v1.yaml` (máy đọc).
3. Thiết kế kỹ thuật: `06. System architecture` và `docs-common/design/`.
4. Bản nháp/lịch sử: chỉ tra cứu, không dùng làm căn cứ triển khai.

## 3. Cấu trúc thư mục

```text
digitalbanking-docs/
├── README.md
├── openapi/
│   └── digital-banking-v1.yaml
├── docs-common/
│   ├── 01. Introduction.md
│   ├── 02. BRD.md
│   ├── 03. SRS.md
│   ├── 04. Database design & analysis.md
│   ├── 05. Technical stack.md
│   ├── 06. System architecture.md
│   ├── 07. API contracts.md
│   ├── 08. Deployment infrastructure.md
│   ├── 09. Test plan.md
│   ├── 10. Master timelines.md
│   ├── Requirement Common.md
│   ├── design/
│   │   ├── README.md
│   │   ├── 01. audit-traceability.md
│   │   ├── 02. consistency-concurrency.md
│   │   ├── 03. fraud-detection-rules.md
│   │   └── 04. security.md
│   └── work/
│       ├── README.md
│       ├── week1/README.md
│       └── week2/README.md
├── docs-backend/
│   └── README.md
├── docs-frontend/
│   └── README.md
├── docs-draft/
│   ├── README.md
│   ├── 01. Introduction.md
│   ├── 02. BRD.md
│   ├── 03. SRS.md
│   ├── 04. Database Design & Analysis.md
│   └── _reviews/
└── docs-references/
    ├── README.md
    └── ...
```

## 4. Trạng thái tài liệu

### 4.1. Quy ước trạng thái

| Trạng thái | Ý nghĩa |
|---|---|
| **Hoàn thành** | Đã viết đủ nội dung theo mục lục hiện tại; không đồng nghĩa đã sign-off |
| **Draft — chờ review** | Đã có nội dung, đang rà soát hoặc chờ chốt |
| **Đang viết** | Chưa có nội dung hoặc mới có khung; không dùng làm căn cứ triển khai/nghiệm thu |
| **Lưu trữ** | Bản cũ, chỉ tham khảo lịch sử |
| **Tham khảo** | Tài liệu nguồn ngoài, không thuộc bộ đặc tả |

### 4.2. Tài liệu chung (`docs-common`)

| # | Tài liệu | Nội dung chính | Trạng thái |
|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/01. Introduction.md]] | Bối cảnh, mục tiêu, phạm vi, stakeholder, quy ước tài liệu | Hoàn thành |
| 02 | [[digitalbanking-docs/docs-common/02. BRD.md]] | Yêu cầu nghiệp vụ, quy trình, business rules | Hoàn thành |
| 03 | [[digitalbanking-docs/docs-common/03. SRS.md]] | Yêu cầu chức năng/phi chức năng, use case, acceptance criteria | Hoàn thành |
| 04 | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] | ERD, schema, ràng buộc, indexing, dữ liệu | Hoàn thành |
| 05 | [[digitalbanking-docs/docs-common/05. Technical stack.md]] | Lựa chọn công nghệ, phiên bản baseline, rủi ro | Hoàn thành |
| 06 | [[digitalbanking-docs/docs-common/06. System architecture.md]] | Kiến trúc hệ thống, C4, runtime, deployment topology, ADR | Hoàn thành |
| 07 | [[digitalbanking-docs/docs-common/07. API contracts.md]] | Hợp đồng API ngữ nghĩa, lỗi, idempotency, step-up | Hoàn thành — cần chốt các giá trị "Đề xuất" |
| 08 | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] | Hạ tầng triển khai, CI/CD, môi trường, backup/DR, runbook | Draft — chờ review |
| 09 | [[digitalbanking-docs/docs-common/09. Test plan.md]] | Chiến lược kiểm thử, kịch bản, dữ liệu, bằng chứng nghiệm thu | Draft — chờ review |
| 10 | [[digitalbanking-docs/docs-common/10. Master timelines.md]] | Kế hoạch tổng thể, milestone, phân công, tiến độ | Đang viết |
| — | [[digitalbanking-docs/docs-common/Requirement Common.md]] | Đề bài/yêu cầu tóm tắt ban đầu | Tham khảo |

### 4.3. Thiết kế chuyên sâu (`docs-common/design`)

| # | Tài liệu | Phạm vi | Trạng thái |
|---|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] | Audit trail bất biến, hash chain, correlation ID, retention | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | Transaction boundary, idempotency, locking, failure recovery | Draft — chờ review |
| 03 | [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] | Rule gian lận, ngưỡng, flag/auto-block, fail-open | Đang viết |
| 04 | [[digitalbanking-docs/docs-common/design/04. security.md]] | Threat model, authn/authz, mã hóa, bảo mật dữ liệu | Hoàn thành — v1.0 |

### 4.4. Tài liệu triển khai, hợp đồng và công việc

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] | Hợp đồng OpenAPI 3.1 (máy đọc) | Đã có — v1.0.0; cần đối chiếu `07` |
| [[digitalbanking-docs/docs-backend/README.md]] | Tài liệu triển khai backend (15 tài liệu chuyên biệt) | Hoàn thành — v1.0 |
| [[digitalbanking-docs/docs-frontend/README.md]] | Tài liệu triển khai frontend | Đang viết |
| [[digitalbanking-docs/docs-common/work/README.md]] | Kế hoạch và nhật ký công việc theo tuần | Đang viết |
| [[digitalbanking-docs/docs-common/work/week1/README.md]] | Tổng hợp tuần 1 | Đang viết |
| [[digitalbanking-docs/docs-common/work/week2/README.md]] | Tổng hợp tuần 2 | Đang viết |

### 4.5. Lưu trữ và tham khảo

| Thư mục | Vai trò | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/docs-draft/README.md]] | Bản nháp và lịch sử rà soát | Lưu trữ |
| [[digitalbanking-docs/docs-references/README.md]] | Tài liệu tham khảo bên ngoài | Tham khảo |

## 5. Thứ tự đọc theo vai trò

| Vai trò | Thứ tự gợi ý |
|---|---|
| Business Analyst / Product Owner | 01 → 02 → 03 → 09 → 10 |
| System Architect | 01 → 03 → 05 → 06 → design/01–04 → 08 |
| Backend | 03 → 04 → 05 → 06 → 07 → design/01–04 → OpenAPI |
| Frontend | 02 → 03 → 07 → OpenAPI → `docs-frontend/` |
| QA / Test | 02 → 03 → 09 → design/01–04 |
| DevOps / SRE | 05 → 06 → 08 → design/01–02 |
| Security / Audit | 02 → 03 → 07 → design/01, 04 → 09 |

## 6. Quy ước tài liệu

### 6.1. Đặt tên tệp

- Tài liệu chính thức: `NN. Tên tài liệu.md` — số hai chữ số, dấu chấm và khoảng trắng.
- Thiết kế chuyên sâu: `NN. kebab-case.md`, có mã tài liệu dạng `DESIGN-<DOMAIN>-NN`.
- Công việc theo tuần: `work/weekN/README.md` và các tệp kế hoạch/bằng chứng trong cùng thư mục.
- Không đổi tên hoặc đánh số lại tài liệu đã baseline nếu chưa cập nhật toàn bộ liên kết và traceability.

### 6.2. Khối metadata đầu tài liệu

Mỗi tài liệu nên có khối metadata ngắn:

```markdown
> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** ...
> **Phiên bản:** ...
> **Ngày:** ...
> **Trạng thái:** Hoàn thành | Draft — chờ review | Đang viết
> **Owner:** ...
> **Reviewer:** ...
```

### 6.3. Liên kết và traceability

- Dùng wikilink để trỏ tới tài liệu khác trong vault.
- Mỗi yêu cầu, quyết định thiết kế và kịch bản kiểm thử phải truy vết được tới `BR/FR/NFR/AC` tương ứng.
- Khi thay đổi một tài liệu nguồn, rà soát các tài liệu tham chiếu tới nó và cập nhật liên kết/trạng thái.

### 6.4. Một nguồn sự thật

- Yêu cầu nghiệp vụ: `02. BRD`.
- Hành vi hệ thống: `03. SRS`.
- Mô hình dữ liệu: `04. Database design & analysis`.
- Công nghệ: `05. Technical stack`.
- Kiến trúc: `06. System architecture`.
- Ngữ nghĩa API: `07. API contracts`; hợp đồng máy đọc: `openapi/digital-banking-v1.yaml`.
- Thiết kế chuyên sâu: `docs-common/design/`.
- Không sao chép nguyên khối nội dung giữa các tài liệu; hãy liên kết và chỉ giữ phần đặc thù.

## 7. Quy trình cập nhật tài liệu

1. Xác định tài liệu nào là nguồn chính thức cho nội dung cần sửa.
2. Với nội dung mới/chưa chốt, tạo bản nháp trong `docs-draft/` hoặc ghi rõ trạng thái **Đang viết**.
3. Review với các vai trò liên quan; cập nhật trạng thái thành **Draft — chờ review** khi nội dung đã đủ.
4. Khi được chấp nhận, cập nhật tài liệu chính thức trong `docs-common/`, ghi Change Log và đổi trạng thái thành **Hoàn thành**.
5. Cập nhật bảng trạng thái trong README này và README của thư mục liên quan.
6. Kiểm tra lại liên kết, traceability và hợp đồng OpenAPI trước khi kết thúc.

## 8. Tài liệu đang viết và việc tiếp theo

- `08. Deployment infrastructure` — hạ tầng triển khai, CI/CD, môi trường, backup/DR, runbook.
- `09. Test plan` — chiến lược kiểm thử và ma trận kiểm chứng `BR/FR/NFR/AC`.
- `10. Master timelines` — kế hoạch tổng thể, milestone và phân công.
- `design/03. fraud-detection-rules` — rule gian lận và luồng flag/auto-block.
- `design/04. security` — threat model và các biện pháp bảo mật.
- `docs-backend/`, `docs-frontend/` — tài liệu triển khai theo nền tảng.
- `docs-common/work/week1`, `week2` — kế hoạch và bằng chứng công việc theo tuần.
- `design/02. consistency-concurrency` — đang ở trạng thái **Draft — chờ review**.

## 9. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Đọc tổng quan dự án | [[digitalbanking-docs/docs-common/01. Introduction.md]] |
| Tra yêu cầu nghiệp vụ | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Tra yêu cầu hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Tra hợp đồng API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Xem kiến trúc | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Xem thiết kế chuyên sâu | [[digitalbanking-docs/docs-common/design/README.md]] |
| Xem hợp đồng OpenAPI | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| Xem kế hoạch công việc | [[digitalbanking-docs/docs-common/work/README.md]] |
| Xem tài liệu tham khảo | [[digitalbanking-docs/docs-references/README.md]] |
