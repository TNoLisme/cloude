# Docs Common

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Tài liệu yêu cầu, kiến trúc và thiết kế chung
> **Cập nhật:** 12/11/2026

## 1. Mục đích

`docs-common/` là nguồn chính thức cho các nội dung dùng chung của Digital Banking MVP: bối cảnh dự án, yêu cầu nghiệp vụ, đặc tả hệ thống, dữ liệu, công nghệ, kiến trúc, API và các thiết kế kỹ thuật chuyên sâu.

Quy ước đọc:

- Bắt đầu từ `01. Introduction` nếu chưa biết bối cảnh.
- Dùng `02. BRD` cho yêu cầu nghiệp vụ và `03. SRS` cho hành vi hệ thống.
- Dùng `04`–`07` khi cần chi tiết kỹ thuật đã chốt.
- Dùng `design/` cho các quyết định kỹ thuật chuyên sâu.
- Dùng `work/` cho kế hoạch và bằng chứng thực thi theo tuần.

## 2. Tài liệu nào chứa gì

| Tài liệu | Nội dung chính | Vai trò |
|---|---|---|
| [[digitalbanking-docs/docs-common/01. Introduction.md]] | Bối cảnh, mục tiêu, phạm vi, stakeholder, glossary, quy ước ID | Nền tảng |
| [[digitalbanking-docs/docs-common/02. BRD.md]] | Business requirements, quy trình, business rules, success criteria | Nguồn yêu cầu nghiệp vụ |
| [[digitalbanking-docs/docs-common/03. SRS.md]] | Functional/non-functional requirements, use case, acceptance criteria, traceability | Nguồn hành vi hệ thống |
| [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] | ERD, schema, ràng buộc, indexing, dữ liệu và vòng đời | Nguồn mô hình dữ liệu |
| [[digitalbanking-docs/docs-common/05. Technical stack.md]] | Lựa chọn công nghệ, phiên bản baseline, rủi ro | Nguồn công nghệ |
| [[digitalbanking-docs/docs-common/06. System architecture.md]] | C4, runtime, integration, deployment topology, ADR | Nguồn kiến trúc |
| [[digitalbanking-docs/docs-common/07. API contracts.md]] | API semantics, error model, idempotency, step-up, traceability | Nguồn ngữ nghĩa API |
| [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] | Hạ tầng triển khai, CI/CD, môi trường, backup/DR, runbook | Nguồn hạ tầng & vận hành |
| [[digitalbanking-docs/docs-common/09. Test plan.md]] | Chiến lược kiểm thử, kịch bản, dữ liệu, bằng chứng | Nguồn chiến lược kiểm thử |
| [[digitalbanking-docs/docs-common/10. Master timelines.md]] | Kế hoạch tổng thể, milestone, phân công, tiến độ | Nguồn kế hoạch tổng thể |
| [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] | Nhân sự, owner 11 module, phối hợp và kế hoạch hạ tầng | Nguồn phân công nhân sự |
| [[digitalbanking-docs/docs-common/Requirement Common.md]] | Đề bài/yêu cầu tóm tắt ban đầu | Tham khảo |

## 3. Trạng thái tài liệu

### 3.1. Tài liệu chính

| # | Tài liệu | Trạng thái |
|---|---|---|
| 01 | Introduction | Hoàn thành |
| 02 | BRD | Hoàn thành |
| 03 | SRS | Hoàn thành |
| 04 | Database Design & Analysis | Hoàn thành |
| 05 | Technical Stack | Hoàn thành |
| 06 | System Architecture | Hoàn thành |
| 07 | API Contracts | Hoàn thành |
| 08 | Deployment Infrastructure | Hoàn thành |
| 09 | Test Plan | Hoàn thành |
| 10 | Master Timelines | Hoàn thành |
| 11 | Resource & Staffing Plan | Hoàn thành |

### 3.2. Thiết kế chuyên sâu

| # | Tài liệu | Trạng thái |
|---|---|---|
| 01 | [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] | Hoàn thành — v1.0 |
| 02 | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | Hoàn thành — v1.0 |
| 03 | [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] | Hoàn thành — v1.0 |
| 04 | [[digitalbanking-docs/docs-common/design/04. security.md]] | Hoàn thành — v1.0 |

### 3.3. Công việc theo tuần

| Tuần | Trạng thái |
|---|---|
| [[digitalbanking-docs/docs-common/work/week1/README.md]] | Đang viết |
| [[digitalbanking-docs/docs-common/work/week2/README.md]] | Đang viết |

## 4. Thứ tự đọc và phụ thuộc

### 4.1. Thứ tự đọc mặc định

`01 → 02 → 03 → 04 → 05 → 06 → 07 → design/01 → design/02 → design/03 → design/04`

Có thể đọc song song:

- `04` và `05` sau khi đọc `03`.
- `06` sau `05` và trước `07`.
- `design/01` và `design/02` sau `07`.
- `design/03` và `design/04` sau `design/02`.

### 4.2. Phụ thuộc chính

| Tài liệu | Đọc trước | Dùng làm đầu vào cho |
|---|---|---|
| 01. Introduction | — | 02, 03, 10 |
| 02. BRD | 01 | 03, 09 |
| 03. SRS | 01, 02 | 04, 06, 07, 09, design/01–04 |
| 04. Database design & analysis | 03 | 06, design/01–03 |
| 05. Technical stack | 03 | 06, 08, 09 |
| 06. System architecture | 03, 04, 05 | 07, 08, design/01–04 |
| 07. API contracts | 03, 06 | OpenAPI, docs-backend, docs-frontend, 09 |
| 08. Deployment infrastructure | 05, 06 | 09, vận hành |
| 09. Test plan | 02, 03, design/01–04 | Nghiệm thu, 10 |
| 10. Master timelines | 01, 02, 03 | Điều phối thực thi |
| 11. Resource & Staffing Plan | 06, 08, 09, 10 | Phân công và theo dõi nguồn lực |

## 5. Thư mục con

### 5.1. `design/`

Chứa các thiết kế kỹ thuật chuyên sâu, nối yêu cầu trong `03. SRS` với triển khai:

- Audit & Traceability.
- Consistency & Concurrency.
- Fraud Detection Rules.
- Security.

Xem hướng dẫn chi tiết tại [[digitalbanking-docs/docs-common/design/README.md]].

### 5.2. `work/`

Chứa kế hoạch, kết quả và bằng chứng công việc theo tuần. Không dùng để lưu đặc tả kỹ thuật; mọi đặc tả phải nằm trong tài liệu chính thức hoặc `design/`.

Xem hướng dẫn tại [[digitalbanking-docs/docs-common/work/README.md]].

## 6. Quy ước khi thêm hoặc sửa tài liệu

1. **Đúng nguồn:** xác định nội dung thuộc tài liệu nào; không tạo tài liệu mới nếu chỉ là bản sao của tài liệu đã có.
2. **Đúng số hiệu:** tài liệu chính thức dùng `NN. Tên tài liệu.md`; thiết kế chuyên sâu dùng `NN. kebab-case.md`.
3. **Đúng metadata:** thêm khối `Dự án / Mã tài liệu / Phiên bản / Ngày / Trạng thái / Owner / Reviewer` ở đầu tài liệu.
4. **Đúng traceability:** mọi yêu cầu, quyết định và kịch bản kiểm thử phải liên kết tới `BR/FR/NFR/AC` liên quan.
5. **Đúng trạng thái:** dùng `Hoàn thành`, `Draft — chờ review`, `Đang viết`; tài liệu chưa có nội dung phải ghi rõ **Đang viết**.
6. **Cập nhật README:** sau khi thêm/đổi trạng thái tài liệu, cập nhật bảng trong README này và README gốc của `digitalbanking-docs/`.

## 7. Lưu ý đồng bộ

- Toàn bộ tài liệu chính (`01`–`11`) và thiết kế chuyên sâu (`design/01`–`04`) trong `docs-common/` đã **Hoàn thành** và là nguồn chuẩn (Single Source of Truth) cho toàn dự án.
- Bảng `Document Map` trong `01. Introduction` (mục 7.2) đang hoán đổi thứ tự `05. Technical stack` và `06. System architecture` so với tên tệp thực tế. Khi tra cứu, ưu tiên tên tệp.
- Một số tài liệu dùng tên rút gọn khi tham chiếu (ví dụ `04. Database analysis` hoặc `10. Master timelines.md`); tên tệp thực tế trên đĩa là `04. Database design & analysis.md` và `10 Master timelines.md`.

## 8. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Bắt đầu đọc | [[digitalbanking-docs/docs-common/01. Introduction.md]] |
| Yêu cầu nghiệp vụ | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Đặc tả hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Hợp đồng API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Kiến trúc | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Thiết kế chuyên sâu | [[digitalbanking-docs/docs-common/design/README.md]] |
| Kế hoạch công việc | [[digitalbanking-docs/docs-common/work/README.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |
