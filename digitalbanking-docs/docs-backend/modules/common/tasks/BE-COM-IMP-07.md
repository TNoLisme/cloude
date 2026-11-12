# BE-COM-IMP-07 — Tích hợp liên module và quality gate

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Blocked — phần common/quality gate đã kiểm; thiếu consumer nghiệp vụ W3/W4/W5; xem [verification](../report/BE-COM-IMP-07-verification.md)
> **Owner / reviewer:** Phương, phối hợp owner module liên quan / Chưa review
> **Mốc / phụ thuộc:** W3–W6; 01–06 và implementation các module tham gia từng luồng

## 1. Mục tiêu và phạm vi

Chứng minh các năng lực common hoạt động trong luồng ứng dụng thật, sửa lỗi common và điểm tích hợp cần thiết. W3 tích hợp identity/customer/account; W4 transfer/ledger/limit; W5 audit/fraud/notification/admin; W6 regression. Không nhận phần nghiệp vụ chưa xây của owner khác vào common; thiếu module ghi dependency. Không đợi task này mới viết unit/component test cho 01–06.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/03. SRS.md]] AC-022/023/024/030/032/034; [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§6, §10.
- [[digitalbanking-docs/docs-common/09. Test plan.md]] §2–§4, §7–§10; [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §3–§7.
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §4.6, §5.2, §5.6–5.7; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §7.10, §11, §14 và Appendix C.
- [[digitalbanking-docs/docs-backend/modules/common/README.md]] §3–§7 và report 01–06; chỉ đọc phần source/module tương ứng luồng đang kiểm tra.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-07-AC01 | Có mapping năng lực common → consumer → scenario/test → nguồn, bao phủ các nhóm W3/W4/W5; mọi phụ thuộc thiếu được ghi, không bỏ luồng để nhận Done | Ma trận integration và test/report thực; không tạo test ID nguồn trùng nghĩa |
| COM-IMP-07-AC02 | Correlation truyền đúng qua request/challenge/transfer/ledger/audit/notification và job liên quan; retry/step-up không làm đứt flow; crypto/JCS được consumer dùng đúng | API + log + dữ liệu PostgreSQL synthetic có liên kết; kiểm hash caller xây theo payload nguồn, không chỉ mock gọi utility |
| COM-IMP-07-AC03 | PII/secret được bảo vệ trong storage tương ứng, response, log, audit và notification; quyền/CSRF dùng thực tế và rate-limit bucket đúng customer | Positive/negative integration tests qua consumer thật, gồm nhiều session cùng customer; mask đúng không thay kiểm quyền |
| COM-IMP-07-AC04 | Lỗi lock/timeout/constraint/audit được trả đúng code/status/retry theo ngữ cảnh; error handler không làm đổi outcome hoặc mở transaction nghiệp vụ | PostgreSQL integration với transaction độc lập: NOWAIT/lock timeout, deadlock, unique idempotency và invariant; failure audit cùng nghiệp vụ rollback, audit security lỗi không bỏ từ chối truy cập; mock/H2 không chứng minh DB |
| COM-IMP-07-AC05 | Các consumer trả đúng contract khi success, validation, security, replay/conflict/in-progress và failure; không auto wrap success, không lộ lỗi nội bộ | Contract tests theo `07` và OpenAPI; test lệch YAML phải ghi/sửa theo nguồn ngữ nghĩa, không đổi expected để khớp bug |
| COM-IMP-07-AC06 | Regression phần common và tích hợp đạt, không còn Critical/High hoặc lỗi vi phạm AC trong phạm vi; dependency một chiều giữ nguyên; report không nhận nghiệm thu toàn bộ D1–D5 chỉ từ common | Kết quả suite, kiểm kiến trúc, defect closure/retest; các tiêu chí D1–D5 ngoài common dẫn evidence owner hoặc ghi rõ chưa được kiểm chứng |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-07-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md), có thể bổ sung evidence theo từng mốc; chỉ Done khi đủ AC. Bàn giao revision đã kiểm chứng, hạn chế và test cần chạy lại trên Cloud cho 08. Ngưỡng còn Đề xuất không tự trở thành policy release chỉ vì test pass.
