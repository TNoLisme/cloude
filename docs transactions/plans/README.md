# Plans — Mục lục & Quy ước

Thư mục `plans/` chứa toàn bộ tài liệu thiết kế của module **Transactions (Transfer)**.
Độc lập, không kế thừa format của `docs_draft`.

---

## Files

| # | File | Nội dung |
|---|---|---|
| 0 | [introduction.md](./0.%20introduction.md) | Tổng quan module, vai trò, scope, nguyên tắc thiết kế |
| 1 | [ERD.md](./1.%20ERD.md) | Schema DB cho module transfer + quan hệ với module khác |
| 2 | [API docs.md](./2.%20API%20docs.md) | Contract HTTP đầy đủ: endpoint, request/response, error codes |
| 3 | [Architecture.md](./3.%20Architecture.md) | Kiến trúc nội bộ, state machine, concurrency strategy |
| 4 | [Deployment Infrastructure.md](./4.%20Deployment%20Infrastructure.md) | Infra, observability, CI/CD, security controls |
| 5 | [Timelines.md](./5.%20Timelines.md) | Kế hoạch 3 tuần (2 backend) với milestone cụ thể |
| 6 | [Business.md](./6.%20Business.md) | Nghiệp vụ: actor, use cases, business rules, ma trận trạng thái, invariants |
| 7 | [Advanced Business.md](./7.%20Advanced%20Business.md) | Kịch bản nâng cao: concurrency, race, partial failure, crash, expiry, idempotency nâng cao |

---

## Quy ước

- **Ngôn ngữ:** Tiếng Việt cho narrative; tiếng Anh cho code, schema, API, tên kỹ thuật.
- **Tài liệu draft:** Chỉ đọc để lấy interface/contract ghép nối. Không sửa `docs_draft/`.
- **OpenAPI:** `contracts/openapi.yaml` là normative authority cho mọi HTTP path/schema/status.
- **Module boundary:** Transfer không inject repository của module khác. Giao tiếp chỉ qua module API.
- **Money:** `BigDecimal` + `NUMERIC(19,0)`. Không dùng float/double.

---

## Trạng thái

| Tuần | Milestone | Status |
|---|---|---|
| 1 | Foundation: schema, state machine, recipient resolve, small transfer (≤ 5M) | 🔄 Đang triển khai |
| 2 | Large transfer (> 5M) + OTP confirm + concurrency CON-01..03 | ⏳ Kế hoạch |
| 3 | Races CON-04/05, failure injection, risk, acceptance, handover | ⏳ Kế hoạch |
