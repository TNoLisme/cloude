# Cấu hình Triển khai Backend (`docs-backend/deployment/configuration/`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-00` — chỉ mục cấu hình
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Baseline đã đối chiếu `docs-common/` — chưa có bằng chứng runtime
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]], [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/06. System architecture.md]], [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]], [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]], [[digitalbanking-docs/docs-common/design/04. security.md]]

> [!important]
> Các tài liệu trong thư mục này là **bản dẫn xuất** từ `docs-common/`. Chúng không thay thế nguồn chuẩn và không xác nhận runtime đã được triển khai. Khi có xung đột, nguồn chuẩn trong `docs-common/` thắng; xung đột chưa giải quyết được ghi thành **Open** thay vì tự chọn một giá trị.

---

## 1. Mục đích

Thư mục `configuration/` chứa toàn bộ nội dung cấu hình và vận hành đã được chuyển ra khỏi `deployment/README.md`, gồm:

- Môi trường, ranh giới mạng, biến môi trường, bí mật, khóa mã hóa và tài khoản DB.
- `application.yml` baseline và quy tắc validation khi khởi động.
- Chính sách Flyway, phân quyền DB, backup/restore và DR.
- Health contract, structured logging, metrics và alert.
- Release gates, rollback và evidence package.
- Runbooks xử lý sự cố.

---

## 2. Danh mục tài liệu

| Mã | Tài liệu | Nội dung chính |
|---|---|---|
| `BE-DEP-CFG-01` | [[digitalbanking-docs/docs-backend/deployment/configuration/01-environment-and-secrets.md]] | Environment matrix, topology, network boundary, `.env`, khóa, DB roles. |
| `BE-DEP-CFG-02` | [[digitalbanking-docs/docs-backend/deployment/configuration/02-application-config.md]] | `application.yml`, giá trị baseline, fail-fast validation. |
| `BE-DEP-CFG-03` | [[digitalbanking-docs/docs-backend/deployment/configuration/03-database-migration-and-backup.md]] | Forward-only migration, least privilege, backup/restore/DR. |
| `BE-DEP-CFG-04` | [[digitalbanking-docs/docs-backend/deployment/configuration/04-observability-and-alerting.md]] | Health, log, metrics, alert và mapping runbook. |
| `BE-DEP-CFG-05` | [[digitalbanking-docs/docs-backend/deployment/configuration/05-release-and-rollback.md]] | Release flow, 8 gates, evidence, rollback. |
| `BE-DEP-CFG-06` | [[digitalbanking-docs/docs-backend/deployment/configuration/06-incident-runbooks.md]] | `R-CON-01..08`, `R-AUD-01..05`. |

---

## 3. Cách đọc và thẩm quyền

1. Đọc tài liệu nguồn tương ứng trong `docs-common/` trước.
2. Đối chiếu tài liệu cấu hình backend với nguồn đó.
3. Nếu tài liệu backend và nguồn khác nhau, **nguồn thắng**; ghi lại khác biệt và trạng thái.
4. Chỉ dùng giá trị **Confirmed** làm baseline. Giá trị **Proposed/Open** phải được chốt và review trước khi dùng cho demo hoặc nghiệm thu.

Nhãn trạng thái dùng thống nhất: **Confirmed**, **Proposed**, **Open**, **Planned**, **Blocked**, **Not verified**. Không dùng `PASS`/`VERIFIED` khi chưa có evidence.

---

## 4. Các điểm đã sửa so với README v1.0

| # | Nội dung cũ | Bản đã sửa | Căn cứ |
|---:|---|---|---|
| 1 | `Spring Boot 3.3` | Spring Boot 4.x, fallback 3.5.x; Java 21; Gradle Kotlin DSL | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| 2 | Dùng `./mvnw` cho Flyway | Dùng Gradle Kotlin DSL; kiểm tra migration qua startup validation, task Flyway Gradle (nếu được cấu hình) hoặc `flyway_schema_history` | `05`; `08` §8.1 |
| 3 | Khẳng định `V1..V6` là tên file chuẩn | `08` chỉ chốt **thứ tự 12 bước**; tên file/version là phạm vi implementation. Tách audit/notification/retention/legal hold thành bước riêng, FKs/triggers/indexes ở các bước sau | `08` §8.1 |
| 4 | OTP TTL chung `120s` | OTP challenge chung `300s` (5 phút); OTP recovery `120s`; tối đa 5 lần, single-use | `05`; `design/04`; `design/03` |
| 5 | Lockout đăng nhập `5 lần / 15 phút` như chuẩn | Tách lockout đăng nhập và lockout PIN. Thời lượng lockout đăng nhập đang xung đột 10 phút vs 15 phút → **Open** | `03`, `06`, `07` (10 phút) vs `design/04` (15 phút); PIN: `design/03` (15 phút) |
| 6 | Step-up đơn lẻ `5.000.000 VND` như chuẩn | Ngưỡng step-up đơn lẻ đang xung đột 5 triệu vs 10 triệu → **Open**; daily 20 triệu và cap 50 triệu giữ nguyên | `design/03` (5 triệu) vs `design/04` (10 triệu) |
| 7 | `app.transfer.single-step-up-threshold-vnd` | Dùng namespace `app.security.step-up.*` | `design/04` |
| 8 | `/api/v1/health` trả `{"status":"UP"}` tối giản | Tách `/actuator/health` (probe, tối giản) và `/api/v1/health` (`API-HLT-01`, Admin/Auditor, có `checkedAt`, `version`, component) | [[digitalbanking-docs/docs-common/07. API contracts.md]]; `08` §9.1 |
| 9 | Cho phép `/actuator/info` công khai | Chỉ công khai health; `info` chỉ công khai khi đã redacted; `metrics`/`prometheus` phải được bảo vệ | `05`; `08` §3.3 |
| 10 | Hikari thiếu `idle-timeout` | Bổ sung `idle-timeout: 600000`; giữ 20/5/3000/1800000/10000 | `design/02` |
| 11 | Giá trị job được trình bày như baseline | Toàn bộ interval job là **Proposed/Open**; chỉ batch 50/lease 30s có tham chiếu module 09 | `08`; module 09 |
| 12 | Grant rộng rồi revoke | Nêu rõ least privilege, `banking_app` không phải owner; chỉ `SELECT, INSERT` trên `ledger_entry`/`audit_log` | `design/04`; `design/01` |
| 13 | `./mvnw flyway:info` | Kiểm tra qua startup validation, Flyway Gradle task (nếu có) hoặc query `flyway_schema_history` | `05`; `08` §8.1 |
| 14 | Runbook thiếu `R-CON-05`, `R-AUD-03..05` | Bổ sung đủ `R-CON-01..08` và `R-AUD-01..05` | `08` §10 |
| 15 | RTO/RPO trình bày như đã đạt | RTO 30 phút / RPO 5 phút là **Proposed**, chưa verified | `08` §8.4 |
| 16 | 5 release gates | Dùng đủ 8 gates của `08` §7.2 | `08` §7.2 |

---

## 5. Xung đột và điểm mở

| ID | Nội dung | Trạng thái | Cách xử lý tạm thời |
|---|---|---|---|
| `CFG-OPEN-01` | Thời lượng lockout đăng nhập: 10 phút (`03`/`06`/`07`) vs 15 phút (`design/04`) | **Open** | Không chốt trong tài liệu; dùng biến môi trường và ghi rõ giá trị đang dùng khi test. |
| `CFG-OPEN-02` | Ngưỡng step-up đơn lẻ: 5 triệu (`design/03`) vs 10 triệu (`design/04`) | **Open** | Không chốt; daily 20 triệu và cap 50 triệu vẫn Confirmed. |
| `CFG-OPEN-03` | Interval của reconciliation/outbox/audit verify/retention job | **Open** | Giá trị trong `02-application-config.md` chỉ là khởi điểm Proposed. |
| `CFG-OPEN-04` | Ngưỡng alert chưa được phê duyệt | **Open** | Dùng ngưỡng Proposed của `06`/`design/02`; không tuyên bố đã hiệu lực. |
| `CFG-OPEN-05` | Tên file/version migration chính xác | **Open** | Theo thứ tự 12 bước; tên file là phạm vi implementation. |
| `CFG-OPEN-06` | RTO 30 phút / RPO 5 phút | **Proposed / Not verified** | Phải đo bằng diễn tập restore trước khi công bố. |
| `CFG-OPEN-07` | Key lifecycle/rotation và KMS/Vault production | **Open** | Ngoài phạm vi MVP; ghi nhận key ID và quy trình rotation thủ công. |
| `CFG-OPEN-08` | OTP resend cooldown/count | **Open** | Không đưa vào baseline; chốt cùng `design/04`. |

---

## 6. Quy tắc cập nhật

1. Không đưa bí mật thật, giá trị `.env` thật, token, OTP hay PII vào bất kỳ tài liệu nào.
2. Mọi giá trị phải có nguồn (`Confirmed`) hoặc nhãn (`Proposed/Open`).
3. Thay đổi về tiền, phân quyền, retention hoặc fraud phải qua review và audit.
4. Khi nguồn `docs-common/` thay đổi, cập nhật tài liệu backend trong cùng thay đổi hoặc ghi rõ độ lệch.
5. Không thêm câu lệnh implementation chưa được kiểm chứng vào runbook như thể đã chạy thành công.

---

## 7. Lịch sử thay đổi

| Phiên bản | Ngày | Mô tả |
|---|---|---|
| v2.0 | 09/11/2026 | Tạo thư mục cấu hình; chuyển nội dung từ `deployment/README.md`; đối chiếu `docs-common/` và ghi nhận các điểm mở. |
