# Backend Documentation (`docs-backend/`)

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Tài liệu triển khai, tổng hợp và chi tiết 11 module Backend
> **Trạng thái thư mục:** Hoàn thành bộ khung triển khai & 11 thư mục module — Sẵn sàng cho các thành viên mở rộng chi tiết theo phân công
> **Cập nhật:** 09/11/2026

---

## 1. Mục đích

`docs-backend/` chứa tài liệu **triển khai và thiết kế chi tiết Backend** được trích xuất và chuẩn hóa từ nguồn chính thức `docs-common/` và `openapi/digital-banking-v1.yaml`.

- Java source và runtime project nằm tại `digitalbanking-backend/`.
- `deployment/README.md` hướng dẫn triển khai/vận hành; `deployment/configuration.md` là nguồn cấu hình tham chiếu.
- `00. architecture & conventions.md` quy định chuẩn kiến trúc Backend.
- `modules/_overview/` tóm tắt 11 module; `modules/<module-name>/` chứa tài liệu module, không chứa Java source.
- `workofteam/` lưu công việc chung Backend và report liên quan.

---

## 2. Cấu trúc Thư mục `docs-backend/`

```text
docs-backend/
├── README.md                                   # Chỉ mục tổng thể tài liệu Backend
├── deployment/
│   ├── README.md                               # [BE-DEP-01] Hướng dẫn triển khai, release gate, backup/restore, rollback & runbooks
│   └── configuration.md                        # [BE-DEP-02] Cấu hình môi trường, application.yml, DB/Flyway & observability
└── modules/
    ├── _overview/                              # [BE-MOD-COMMON] Thư mục tham chiếu tổng hợp 11 module
    │   ├── README.md                           # Chỉ mục chung, quy ước kiến trúc & sơ đồ phối hợp T0-T3
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
    ├── admin/README.md                         # Thư mục chi tiết Module admin (Đạt & Thịnh)
    └── common/README.md                        # Thư mục chi tiết Module common hạ tầng dùng chung (Phương)
```

---

## 3. Danh mục & Phân công 11 Module Backend

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
| Mô hình dữ liệu & Ràng buộc DB | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Ngăn xếp công nghệ | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| Kiến trúc hệ thống & Ranh giới 11 module | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Hợp đồng ngữ nghĩa API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Hạ tầng triển khai, Observability & Runbook | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] |
| Kế hoạch kiểm thử & Nghiệm thu `D1–D5` | [[digitalbanking-docs/docs-common/09. Test plan.md]] |
| Kế hoạch tổng thể 8 tuần | [[digitalbanking-docs/docs-common/10 Master timelines.md]] |
| Phân công nhân sự 11 module | [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] |
| Thiết kế chuyên sâu (`Audit`, `Concurrency`, `Fraud`, `Security`) | [[digitalbanking-docs/docs-common/design/README.md]] |
| Hợp đồng OpenAPI 3.1 (Máy đọc) | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
