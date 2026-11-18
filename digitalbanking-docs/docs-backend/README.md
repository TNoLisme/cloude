# Backend Documentation (`docs-backend/`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DOC-INDEX`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Phạm vi:** Tài liệu kiến trúc, cấu hình triển khai, chỉ mục tổng hợp và thiết kế chi tiết 11 module Backend
> **Trạng thái:** Hoàn thành chuẩn hóa Baseline v1.1

---

## 1. Mục đích

`docs-backend/` chứa tài liệu **kiến trúc, cấu hình triển khai và thiết kế chi tiết Backend** được chuẩn hóa từ nguồn chính thức `docs-common/`, `openapi/digital-banking-v1.yaml` và `DECISIONS.md`.

- Mã nguồn Java và dự án runtime nằm tại `digitalbanking-backend/`.
- `00. architecture & conventions.md` quy định chuẩn kiến trúc, phân lớp, giao dịch, khóa, bảo mật và quy ước mã nguồn Backend.
- `deployment/README.md` hướng dẫn triển khai/vận hành; `deployment/configuration.md` là nguồn cấu hình tham chiếu; `deployment/common-readiness.md` ghi nhận danh mục kiểm tra sẵn sàng và runbook ứng dụng.
- `modules/_overview/` tóm tắt 11 module theo cấu trúc 5 mục đồng nhất; `modules/<module-name>/` chứa tài liệu thiết kế chi tiết, danh mục công việc (`tasks/`) và báo cáo kiểm chứng của từng module.
- `workofteam/` lưu công việc nền tảng dùng chung và quy trình phối hợp của nhóm Backend.

---

## 2. Cấu trúc Thư mục `docs-backend/`

```text
docs-backend/
├── README.md                                   # Chỉ mục tổng thể tài liệu Backend
├── 00. architecture & conventions.md           # [BE-ARCH-00] Chuẩn kiến trúc, phân lớp, giao dịch, bảo mật & quy ước Backend
├── deployment/
│   ├── README.md                               # [BE-DEP-01] Hướng dẫn triển khai, release gate, backup/restore, rollback & runbooks
│   ├── configuration.md                        # [BE-DEP-02] Cấu hình môi trường, application.yml, DB/Flyway V1-V10 & observability
│   └── common-readiness.md                     # [BE-COM-OPS-08] Checklist sẵn sàng ứng dụng, quản lý secret & vận hành gói JAR
├── workofteam/
│   ├── README.md                               # Chỉ mục công việc chung của nhóm Backend
│   ├── BE-FND-01-backend-base-project.md       # Báo cáo dựng khung dự án Backend nền tảng
│   ├── BE-FND-01-verification-report.md        # Báo cáo kiểm chứng BE-FND-01
│   └── thinh-backend-workflow.md               # Quy trình phối hợp và thứ tự triển khai nhóm module Audit/Fraud/Notification/Admin
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
    ├── identity/                               # Module 01: identity / auth (Đạt) — README.md, BE-ID-DOC-01.md, BE-ID-IMP-02..13.md, BE-ID-TEST-01..03.md, tasks/
    ├── customer/                               # Module 02: customer (Đạt) — README.md
    ├── account/                                # Module 03: account (Đạt) — README.md
    ├── transfer/                               # Module 04: transfer (Vũ) — README.md, design.md, tasks/ (BE-TRF-01..07, evidence/, ops/)
    ├── ledger/                                 # Module 05: ledger (Phương) — README.md, design.md, tasks/ (BE-LED-01..03, evidence/)
    ├── limit/                                  # Module 06: limit (Vũ) — README.md
    ├── audit/                                  # Module 07: audit (Thịnh) — README.md, BE-AUD-01..03-*.md
    ├── fraud/                                  # Module 08: fraud (Thịnh) — README.md, BE-FRD-01-*.md, BE-AI-01-*.md
    ├── notification/                           # Module 09: notification (Thịnh) — README.md, BE-NOT-01-*.md
    ├── admin/                                  # Module 10: admin (Đạt & Thịnh) — README.md, BE-ADM-01-*.md
    └── common/                                 # Module 11: common (Phương) — README.md, TKCT.md, CURRENT-STATE.md, tasks/, report/
```

---

## 3. Danh mục, Phân công & Trạng thái Thực thi 11 Module Backend

| # | Thư mục Module | File Tham chiếu Tóm tắt | Owner chính | Mốc tập trung | Trạng thái triển khai (`18/11/2026`) |
|---:|---|---|---|---:|---|
| 1 | [[digitalbanking-docs/docs-backend/modules/identity/README.md]] | `modules/_overview/01-identity-auth.md` | **Đạt** | W3 | **Đã triển khai core/REST** (75 classes, migrations `V2`–`V4`, 597 tests; chờ ghép `AuditModuleApi`/`OtpGateway`/`CustomerQuery`) |
| 2 | [[digitalbanking-docs/docs-backend/modules/customer/README.md]] | `modules/_overview/02-customer.md` | **Đạt** | W3 | **Đang phát triển / Skeleton** (Đã chốt hợp đồng `CustomerQuery` & schema `04` §4.2.2) |
| 3 | [[digitalbanking-docs/docs-backend/modules/account/README.md]] | `modules/_overview/03-account.md` | **Đạt** | W3–W4 | **Đang phát triển / Skeleton** (Đã chốt hợp đồng `AccountQuery`, `AccountBalance` & schema `04` §4.3.1) |
| 4 | [[digitalbanking-docs/docs-backend/modules/transfer/README.md]] | `modules/_overview/04-transfer.md` | **Vũ** | W4 | **Đã triển khai component/candidate, chờ ghép tích hợp** (`BE-TRF-01..07`, 163 tests Transfer; chờ ghép provider thật) |
| 5 | [[digitalbanking-docs/docs-backend/modules/ledger/README.md]] | `modules/_overview/05-ledger.md` | **Phương** | W4 | **Đã triển khai component/candidate, chờ ghép tích hợp** (`BE-LED-01..03`, 162 tests Ledger + ArchUnit; chờ ghép `T2` & `V8`) |
| 6 | [[digitalbanking-docs/docs-backend/modules/limit/README.md]] | `modules/_overview/06-limit.md` | **Vũ** | W4–W5 | **Đang phát triển / Skeleton** (Đã chốt hợp đồng guard/reservation `DEC-TRF-008` & schema `04` §4.5) |
| 7 | [[digitalbanking-docs/docs-backend/modules/audit/README.md]] | `modules/_overview/07-audit.md` | **Thịnh** | W3–W5 | **Đã triển khai component/candidate, chờ ghép tích hợp** (`BE-AUD-01/02` writer, query, chain verifier — 171 tests; chờ mở public port & REST) |
| 8 | [[digitalbanking-docs/docs-backend/modules/fraud/README.md]] | `modules/_overview/08-fraud.md` | **Thịnh** | W5 | **Đã triển khai component/candidate, chờ ghép tích hợp** (`BE-FRD-01` evaluator, JPA/JDBC persistence, review service — 45 tests) |
| 9 | [[digitalbanking-docs/docs-backend/modules/notification/README.md]] | `modules/_overview/09-notification.md` | **Thịnh** | W4–W5 | **Đã triển khai component/candidate, chờ ghép tích hợp** (`BE-NOT-01` outbox service, `SKIP LOCKED` worker, simulator — 72 tests) |
| 10 | [[digitalbanking-docs/docs-backend/modules/admin/README.md]] | `modules/_overview/10-admin.md` | **Đạt** *(Thịnh đồng phát triển)* | W5 | **Đang phát triển / Skeleton** (Đã chốt hợp đồng Maker-Checker, `IdentityRoleCommand` & `BE-ADM-01`) |
| 11 | [[digitalbanking-docs/docs-backend/modules/common/README.md]] | `modules/_overview/11-common.md` | **Phương** | W2–W3 (duy trì) | **Hoàn thành** (30 Java files trên 5 sub-packages: `config`, `context`, `crypto`, `error`, `logging`) |

---

## 4. Liên kết Nguồn Chính thức (`docs-common/` & `DECISIONS.md`)

| Mục đích | Tài liệu nguồn |
|---|---|
| Sổ tay quyết định kiến trúc & kỹ thuật (`DEC-*`) | [[DECISIONS.md]] |
| Yêu cầu hệ thống (`FR`, `NFR`, `AC`) | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Mô hình dữ liệu & Ràng buộc DB | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Ngăn xếp công nghệ | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| Kiến trúc hệ thống & Ranh giới 11 module | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Hợp đồng ngữ nghĩa API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Hạ tầng triển khai, Observability & Runbook | [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] |
| Kế hoạch kiểm thử & Nghiệm thu `D1–D5` | [[digitalbanking-docs/docs-common/09. Test plan.md]] |
| Kế hoạch tổng thể 8 tuần | [[digitalbanking-docs/docs-common/10 Master timelines.md]] |
| Phân công nhân sự 11 module | [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] |
| Thiết kế chuyên sâu (`Audit`, `Concurrency`, `Fraud`, `Security`, `Modules contract`) | [[digitalbanking-docs/docs-common/design/README.md]] |
| Hợp đồng OpenAPI 3.1 (Máy đọc) | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
