# BE-COM-IMP-04 — Crypto, canonicalization và masking

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Done — implementer verified; chờ review; [verification](../report/BE-COM-IMP-04-verification.md)
> **Owner / reviewer:** Phương / Chưa review
> **Mốc / phụ thuộc:** W3; task 01

## 1. Mục tiêu và phạm vi

Cung cấp primitive AES-GCM, HMAC/SHA, so sánh hằng thời gian, JCS và masking cho các owner. Chuẩn hóa dữ liệu nghiệp vụ, xây payload transfer/audit, persistence và vòng đời OTP/session thuộc module gọi. Không thay password hashing bằng SHA/HMAC. Rotation nhiều khóa ngoài implementation task này cho đến khi OQ-15/OI-COM-05 có thiết kế được chốt; vẫn ghi gap trong report.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4 (cột enc/hash/last4), §9, §11.2 OQ-15; [[digitalbanking-docs/docs-common/07. API contracts.md]] §2.6, §5.2.
- [[digitalbanking-docs/docs-common/design/04. security.md]] §6–§7; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6, §7.2–7.3, Appendix B.
- [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]] §1.5, §2.3, §7.2 (COM-CRY/JCS/PII); các đề xuất biên input/chữ ký không tự trở thành contract nghiệp vụ.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-04-AC01 | AES-256-GCM tạo blob IV 12B + ciphertext + tag 16B, IV ngẫu nhiên mới mỗi lần; decrypt xác thực đầy đủ | UTF-8 roundtrip, mã hóa cùng input nhiều lần, wrong key/tampered/truncated blob bị từ chối, không plaintext một phần |
| COM-IMP-04-AC02 | Khóa AES/HMAC tách biệt, lấy ngoài source; Base64/độ dài 32 byte được kiểm tra; thiếu/sai/placeholder không hợp lệ bị fail-fast theo môi trường nguồn yêu cầu | Startup tests positive/negative; kiểm log/toString/error/endpoint vận hành không lộ khóa; không hard-code khóa thật |
| COM-IMP-04-AC03 | HMAC/SHA đúng bytes/encoding/hex lowercase; utility không tự sửa input nghiệp vụ; so sánh secret/hash dùng primitive constant-time | Known-answer tests với expected từ nguồn độc lập implementation; kiểm cả leading zero và equal/unequal; review primitive, không lấy đo thời gian unit test làm bằng chứng constant-time |
| COM-IMP-04-AC04 | JCS đầy đủ RFC 8785: UTF-16 key order, number/escape đúng chuẩn, giữ array order/null, không tự normalize Unicode; input ngoài miền JCS/duplicate key không bị âm thầm chấp nhận | Vectors có nguồn/expected độc lập, Unicode/number/escape/negative cases; duplicate key kiểm tại parser trước khi mất thông tin |
| COM-IMP-04-AC05 | Masking theo nguồn: account `**** **** 1234`, phone/email và các trường còn lại theo policy tương ứng; không fallback trả nguyên PII khi input lỗi | Tests từng loại dữ liệu và input thiếu/sai; policy chưa chốt được ghi rõ, không tự phơi dữ liệu hoặc sáng tác quy tắc public |
| COM-IMP-04-AC06 | Utility dùng được cho payload transfer/audit do caller cung cấp mà không phụ thuộc business module; hash tương thích ví dụ/vector nguồn | Component tests với payload synthetic cố định; không tự tạo request_hash/record_hash bằng payload nghiệp vụ ẩn trong common |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-04-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Bàn giao input/output, cách dùng và giới hạn cho identity/customer/account/transfer/audit; evidence persistence và PII xuyên kênh thuộc 07. Không nhận đã đáp ứng toàn bộ encryption-at-rest hoặc rotation từ utility tests.
