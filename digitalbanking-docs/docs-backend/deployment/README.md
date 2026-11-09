# Backend Deployment & Runtime Operations (`docs-backend/deployment/`)

> **Dự án:** Digital Banking MVP (Single-Bank, Core Simulated)
> **Mã tài liệu:** `BE-DEP-00` — chỉ mục hướng dẫn
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Index/Guidance — không phải nguồn chuẩn
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]], [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/06. System architecture.md]], [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/docs-common/design/04. security.md]]
> **Thay thế:** v1.0 — nội dung cấu hình đã được chuyển sang `configuration/` và đối chiếu lại với `docs-common/`

> [!warning]
> `docs-backend/` là tài liệu **dẫn xuất**. Khi có khác biệt, `docs-common/`, `openapi/digital-banking-v1.yaml` và các quyết định đã được review là nguồn thắng. Không dùng README này làm căn cứ cấu hình runtime.

---

## 1. Mục đích

Thư mục `deployment/` mô tả cách Backend Spring Boot và PostgreSQL 16 được cấu hình, migration, giám sát, sao lưu và vận hành trong phạm vi `local`, `test` và `demo`. README này chỉ đóng vai trò **chỉ mục và hướng dẫn đọc**; mọi nội dung cấu hình, lệnh vận hành, ngưỡng và quy trình chi tiết nằm trong `configuration/`.

Phạm vi này không bao gồm production hardening, PCI-DSS/ISO certification, core banking thật, KMS/Vault production, Kubernetes hoặc multi-region DR.

---

## 2. Thứ tự đọc

1. **README này** — nắm phạm vi, thứ tự ưu tiên nguồn và quy ước trạng thái.
2. [[digitalbanking-docs/docs-backend/deployment/configuration/README.md]] — danh mục, các điểm đã sửa và xung đột nguồn đang mở.
3. [[digitalbanking-docs/docs-backend/deployment/configuration/01-environment-and-secrets.md]] — môi trường, ranh giới mạng, `.env`, khóa và tài khoản DB.
4. [[digitalbanking-docs/docs-backend/deployment/configuration/02-application-config.md]] — `application.yml`, giá trị mặc định và quy tắc fail-fast.
5. [[digitalbanking-docs/docs-backend/deployment/configuration/03-database-migration-and-backup.md]] — Flyway, least privilege, backup/restore/DR.
6. [[digitalbanking-docs/docs-backend/deployment/configuration/04-observability-and-alerting.md]] — health, log, metrics, alerts.
7. [[digitalbanking-docs/docs-backend/deployment/configuration/05-release-and-rollback.md]] — release gates và rollback.
8. [[digitalbanking-docs/docs-backend/deployment/configuration/06-incident-runbooks.md]] — runbooks `R-CON-01..08` và `R-AUD-01..05`.

---

## 3. Bản đồ tài liệu

| Mã | Tài liệu | Nội dung | Dùng khi |
|---|---|---|---|
| `BE-DEP-00` | `README.md` | Chỉ mục, thứ tự đọc, quy ước trạng thái | Bắt đầu đọc hoặc cập nhật tài liệu |
| `BE-DEP-CFG-00` | `configuration/README.md` | Danh mục, nguyên tắc cấu hình, điểm đã sửa, xung đột nguồn | Cần biết tài liệu nào đang có thẩm quyền |
| `BE-DEP-CFG-01` | `configuration/01-environment-and-secrets.md` | Environment matrix, topology, `.env`, khóa, DB roles | Thiết lập môi trường hoặc xử lý bí mật |
| `BE-DEP-CFG-02` | `configuration/02-application-config.md` | `application.yml`, tham số, validation khởi động | Viết/kiểm tra cấu hình ứng dụng |
| `BE-DEP-CFG-03` | `configuration/03-database-migration-and-backup.md` | Forward-only migration, grants, backup/restore/DR | Release có thay đổi schema hoặc diễn tập phục hồi |
| `BE-DEP-CFG-04` | `configuration/04-observability-and-alerting.md` | Health contract, log, metrics, alert, runbook mapping | Vận hành, demo, xử lý cảnh báo |
| `BE-DEP-CFG-05` | `configuration/05-release-and-rollback.md` | Release flow, 8 gates, evidence, rollback | Chuẩn bị phát hành/demo |
| `BE-DEP-CFG-06` | `configuration/06-incident-runbooks.md` | Quy trình xử lý sự cố `R-CON-*`, `R-AUD-*` | Có sự cố hoặc cảnh báo |

---

## 4. Thứ tự ưu tiên nguồn

1. **BRD/SRS** — yêu cầu nghiệp vụ, `FR`, `NFR`, `AC`.
2. **API contracts và OpenAPI** — `07. API contracts.md`, `openapi/digital-banking-v1.yaml`.
3. **Kiến trúc và thiết kế** — `04` database, `05` technical stack, `06` architecture, `design/01..04`.
4. **Tài liệu backend dẫn xuất** — các file trong `docs-backend/deployment/configuration/`.
5. **Ghi chú/draft** — chỉ dùng tham khảo, không dùng làm căn cứ nghiệm thu.

Khi tài liệu nguồn không trả lời được một giá trị cụ thể, ghi **Open** hoặc **Proposed**; không tự biến giá trị minh họa thành chuẩn.

---

## 5. Quy ước trạng thái

| Nhãn | Ý nghĩa |
|---|---|
| **Confirmed** | Được nêu trực tiếp trong tài liệu nguồn. |
| **Proposed** | Giá trị/control được nguồn ghi là đề xuất, chưa được chốt hoặc đo. |
| **Open** | Chưa có quyết định; phải chốt trước khi dùng làm baseline. |
| **Planned** | Kế hoạch cần thực hiện; chưa phải bằng chứng runtime. |
| **Blocked** | Chưa thể kiểm chứng do thiếu môi trường/dữ liệu/dependency. |
| **Not verified** | Chưa có evidence được phép trong phạm vi docs-only. |

Không dùng `PASS`, `VERIFIED` hoặc `PRODUCTION-READY` nếu chưa có bằng chứng runtime tương ứng.

---

## 6. Quy tắc cập nhật

1. README này **không** chứa giá trị cấu hình, câu lệnh migration, ngưỡng alert hoặc quy trình runbook chi tiết; đưa nội dung đó vào `configuration/`.
2. Cập nhật tài liệu nguồn trong `docs-common/` trước. Nếu backend cần khác, ghi rõ lý do, phạm vi và ID xung đột.
3. Không đưa mật khẩu, khóa thật, token, OTP, PII hoặc giá trị `.env` thật vào tài liệu, log, báo cáo test hay ảnh chụp.
4. Mỗi giá trị hoặc câu lệnh phải có nguồn hoặc nhãn trạng thái. Không trình bày giá trị minh họa như baseline.
5. Không tuyên bố đã chạy thành công nếu chưa có evidence (command, environment, expected, actual, timestamp).
6. Tên file migration, version dependency và câu lệnh implementation chính xác là phạm vi implementation; tài liệu chỉ mô tả hợp đồng và thứ tự.
7. Thay đổi ảnh hưởng tiền, phân quyền, retention hoặc fraud phải qua review và được audit.

---

## 7. Phạm vi và giới hạn

- **Trong phạm vi:** `local`, `test`, `demo`; topology logic; cấu hình, bí mật, migration, backup/restore, observability, release/rollback, runbooks.
- **Ngoài phạm vi:** production hardening, chứng nhận tuân thủ, giao dịch tiền thật, provider-specific IaC, SSO federation, KMS/Vault production, Kubernetes, multi-region DR.
- Tài liệu này **không xác nhận** runtime deployment đã chạy thành công.

---

## 8. Lịch sử thay đổi

| Phiên bản | Ngày | Mô tả |
|---|---|---|
| v1.0 | 09/11/2026 | Bản triển khai đầu tiên, gộp cấu hình và vận hành trong một README. |
| v2.0 | 09/11/2026 | Chuyển nội dung cấu hình sang `configuration/`; README chỉ còn chỉ mục/hướng dẫn; đối chiếu và sửa các điểm sai so với `docs-common/`. |
