# Backend Documentation (`docs-backend/`)

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Tài liệu triển khai, tổng hợp và chi tiết 11 module Backend
> **Trạng thái thư mục:** Hoàn thành bộ khung triển khai và 11 thư mục module — Sẵn sàng cho các thành viên mở rộng chi tiết theo phân công
> **Cập nhật:** 09/11/2026

---

## 1. Mục đích

`docs-backend/` chứa tài liệu **triển khai và thiết kế chi tiết Backend** được trích xuất và chuẩn hóa từ nguồn chính thức `docs-common/` và `openapi/digital-banking-v1.yaml`:

0. **Tài liệu nền tảng (`BE-ARC-00`, `BE-ARC-00A`):**
   - [`00. Architecture and Conventions.md`](./00.%20Architecture%20and%20Conventions.md) — cổng vào duy nhất cho kỹ sư backend: kiến trúc modular monolith, 11 module, 5 tầng package, ranh giới transaction, thứ tự khóa, idempotency, quy ước dữ liệu, API, bảo mật.
   - [`00A. Backend Standards Reference.md`](./00A.%20Backend%20Standards%20Reference.md) — tham chiếu chi tiết về audit, logging, metric, kiểm thử, cấu hình, triển khai, quy ước tài liệu, ADR, vấn đề mở và phụ lục.
1. **`deployment/` (`BE-DEP-01`):** Tài liệu triển khai, cấu hình runtime (`.env`, `application.yml`), chính sách Flyway migration (`V1..V6`), phân quyền DB (`banking_migrator` vs `banking_app`), sao lưu/phục hồi, observability (Health, JSON Logs, Micrometer Metrics, Alerts) và sổ tay xử lý sự cố (Incident Runbooks) tham chiếu từ [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]].
2. **`modules/_overview/` (`BE-MOD-COMMON`):** Thư mục tham chiếu tổng hợp toàn bộ **11 module backend**, gồm:
   - 1 file chỉ mục chung (`modules/_overview/README.md`) quy định cấu trúc package 5 lớp, quy tắc ranh giới dữ liệu (`Single Write-Owner`), thứ tự khóa chống Deadlock và sơ đồ phối hợp xuyên module (`T0 -> T1 -> T2 -> T3`).
   - **11 file tóm tắt riêng biệt (`01` đến `11`)** tóm tắt mục tiêu, bảng DB sở hữu, REST API, Internal Ports và danh sách công việc của từng module theo đúng phân công trong [[digitalbanking-docs/docs-common/11. Resource and Staffing Plan.md]].
3. **`modules/<module-name>/`:** 11 thư mục riêng biệt tương ứng với 11 module Java của hệ thống. Mỗi thư mục có file `README.md` mô tả ngắn, ranh giới dữ liệu, liên kết tham chiếu và là nơi từng thành viên phụ trách (**Vũ, Phương, Đạt, Thịnh**) sẽ bổ sung thiết kế chi tiết (class, DTO, SQL, test cases).

---

## 2. Cấu trúc Thư mục `docs-backend/`

```text
docs-backend/
├── README.md                                   # Chỉ mục tổng thể tài liệu Backend
├── 00. Architecture and Conventions.md         # [BE-ARC-00] Kiến trúc và quy ước nền tảng
├── 00A. Backend Standards Reference.md         # [BE-ARC-00A] Tham chiếu quy chuẩn chi tiết
├── deployment/
│   └── README.md                               # [BE-DEP-01] Triển khai, cấu hình, migration, observability và runbooks
└── modules/
    ├── _overview/                              # [BE-MOD-COMMON] Thư mục tham chiếu tổng hợp 11 module
    │   ├── README.md                           # Chỉ mục chung, quy ước kiến trúc và sơ đồ phối hợp T0-T3
    │   ├── 01-identity-auth.md                 # Tóm tắt Module 01: identity / auth (Owner: Đạt)
    │   ├── 02-customer.md                      # Tóm tắt Module 02: customer (Owner: Đạt)
    │   ├── 03-account.md                       # Tóm tắt Module 03: account (Owner: Đạt)
    │   ├── 04-transfer.md                      # Tóm tắt Module 04: transfer (Owner: Vũ)
    │   ├── 05-ledger.md                        # Tóm tắt Module 05: ledger (Owner: Phương)
    │   ├── 06-limit.md                         # Tóm tắt Module 06: limit (Owner: Vũ)
    │   ├── 07-audit.md                         # Tóm tắt Module 07: audit (Owner: Thịnh)
    │   ├── 08-fraud.md                         # Tóm tắt Module 08: fraud (Owner: Thịnh)
    │   ├── 09-notification.md                  # Tóm tắt Module 09: notification (Owner: Thịnh)
    │   ├── 10-admin.md                         # Tóm tắt Module 10: admin (Owner: Đạt, Thịnh đồng phát triển)
    │   └── 11-common.md                        # Tóm tắt Module 11: common (Owner: Phương)
    │
    ├── identity/README.md                      # Thư mục chi tiết Module identity / auth (Đạt)
    ├── customer/README.md                      # Thư mục chi tiết Module customer (Đạt)
    ├── account/README.md                       # Thư mục chi tiết Module account (Đạt)
    ├── transfer/README.md                      # Thư mục chi tiết Module transfer (Vũ)
    ├── ledger/README.md                        # Thư mục chi tiết Module ledger (Phương)
    ├── limit/README.md                         # Thư mục chi tiết Module limit (Vũ)
    ├── audit/README.md                         # Thư mục chi tiết Module audit (Thịnh)
    ├── fraud/README.md                         # Thư mục chi tiết Module fraud (Thịnh)
    ├── notification/README.md                  # Thư mục chi tiết Module notification (Thịnh)
    ├── admin/README.md                         # Thư mục chi tiết Module admin (Đạt và Thịnh)
    └── common/README.md                        # Thư mục chi tiết Module common hạ tầng dùng chung (Phương)
```

---

## 3. Danh mục và Phân công 11 Module Backend

| # | Thư mục Module | File Tham chiếu Tóm tắt | Owner chính | Mốc tập trung |
|---:|---|---|---|---:|
| 1 | [[digitalbanking-docs/docs-backend/modules/identity/README.md]] | `modules/_overview/01-identity-auth.md` | **Đạt** | W3 |
| 2 | [[digitalbanking-docs/docs-backend/modules/customer/README.md]] | `modules/_overview/02-customer.md` | **Đạt** | W3 |
| 3 | [[digitalbanking-docs/docs-backend/modules/account/README.md]] | `modules/_overview/03-account.md` | **Đạt** | W3–W4 |
| 4 | [[digitalbanking-docs/docs-backend/modules/transfer/README.md]] | `modules/_overview/04-transfer.md` | **Vũ** | W4 |
| 5 | [[digitalbanking-docs/docs-backend/modules/ledger/README.md]] | `modules/_overview/05-ledger.md` | **Phương** | W4 |
| 6 | [[digitalbanking-docs/docs-backend/modules/limit/README.md]] | `modules/_overview/06-limit.md` | **Vũ** | W4–W5 |
| 7 | [[digitalbanking-docs/docs-backend/modules/audit/README.md]] | `modules/_overview/07-audit.md` | **Thịnh** | W3–W5 |
| 8 | [[digitalbanking-docs/docs-backend/modules/fraud/README.md]] | `modules/_overview/08-fraud.md` | **Thịnh** | W5 |
| 9 | [[digitalbanking-docs/docs-backend/modules/notification/README.md]] | `modules/_overview/09-notification.md` | **Thịnh** | W4–W5 |
| 10 | [[digitalbanking-docs/docs-backend/modules/admin/README.md]] | `modules/_overview/10-admin.md` | **Đạt** *(Thịnh đồng phát triển)* | W5 |
| 11 | [[digitalbanking-docs/docs-backend/modules/common/README.md]] | `modules/_overview/11-common.md` | **Phương** | W2–W3 (duy trì) |

---

## 4. Liên kết Nguồn Chính thức (`docs-common/`)

| Mục đích | Tài liệu nguồn |
|---|---|
| Yêu cầu hệ thống (`FR`, `NFR`, `AC`) | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Mô hình dữ liệu và Ràng buộc DB | [[digitalbanking-docs/docs-common/04. Database design %26 analysis.md]] |
| Ngăn xếp công nghệ | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| Kiến trúc hệ thống và Ranh giới 11 module | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Hợp đồng ngữ nghĩa API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Hạ tầng triển khai, Observability và Runbook | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] |
| Kế hoạch kiểm thử và Nghiệm thu `D1–D5` | [[digitalbanking-docs/docs-common/09. Test plan.md]] |
| Kế hoạch tổng thể 8 tuần | [[digitalbanking-docs/docs-common/10 Master timelines.md]] |
| Phân công nhân sự 11 module | [[digitalbanking-docs/docs-common/11. Resource and Staffing Plan.md]] |
| Thiết kế chuyên sâu (`Audit`, `Concurrency`, `Fraud`, `Security`) | [[digitalbanking-docs/docs-common/design/README.md]] |
| Hợp đồng OpenAPI 3.1 (Máy đọc) | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
