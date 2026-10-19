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
| 5 | [Timelines.md](./5.%20Timelines.md) | Kế hoạch 5 tuần với milestone cụ thể |

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
| 1 | Foundation: schema, state machine, recipient resolve | 🔄 Đang triển khai |
| 2 | Small transfer path (≤ 5M) end-to-end | ⏳ Kế hoạch |
| 3 | Large transfer path (> 5M) + OTP confirm | ⏳ Kế hoạch |
| 4 | Concurrency, idempotency, failure scenarios | ⏳ Kế hoạch |
| 5 | History/query, risk integration, acceptance | ⏳ Kế hoạch |
