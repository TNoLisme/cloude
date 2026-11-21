# BE-INT-07 — Tích hợp Fraud và Audit vào Transfer T2

> **Trạng thái:** Đã triển khai · 19/11/2026 · [Kiểm chứng PostgreSQL](BE-INT-07-verification.md)
>
> **Thực hiện và quyết định:** người điều hành tích hợp theo DEC-INT-003
>
> **Đầu vào:** BE-INT-06 đã xác minh Account/Ledger/Limit, durable claim, terminal replay và C1 với Fraud ALLOW fixture. [Báo cáo PostgreSQL](BE-INT-06-verification.md)

## 1. Mục tiêu và hiện trạng

Thay Fraud fixture bằng provider thật trên PostgreSQL và chứng minh cờ gian lận, Audit C2/C1 và kết quả T2 commit nguyên tử. Kiểm chứng C3 cho từ chối quyền và xung đột idempotency trong luồng tạo Transfer, kể cả khi durable Audit lỗi. Phạm vi bàn giao gồm POST Transfer và hợp đồng nội bộ. REST tra cứu/thẩm định Fraud, delivery OTP/Notification, recovery worker/Tfail và nghiệm thu phát hành thuộc các đầu việc riêng. [Kế hoạch tích hợp](README.md)

| Thành phần | Điểm bắt đầu trước BE-INT-07 |
|---|---|
| Fraud | Có `FraudRuleEvaluator`, persistence/review component và schema canonical V15. Còn thiếu `fraud.api.FraudEvaluation` nối T2, nguồn đếm intent, cấu hình rule mặc định trên DB mới và dữ liệu policy chặn. V15 cũng thiếu partial unique index cho rule active theo thiết kế DB. |
| Transfer | `TransferSettlementCandidate` đã có trình tự C2 theo flag ID rồi C1 và nhánh savepoint ở mức candidate. `TransferSettlementAdapter` còn gọi seam `TransferSettlementHandoff.Fraud` để đánh giá và ghi C2. Tách mapping Audit sang `TransferAuditAdapter` và thay seam bằng adapter gọi public API của Fraud. |
| Audit | `AuditModuleApi.recordInTransaction` và `recordStandalone` đã có writer thật. `FRAUD_FLAG_CREATED` đã có trong catalog nhưng thiếu `ruleVersion` ở allowlist theo action. `recordStandalone` hiện nhận C3, vì vậy dùng structured log/metric cho C4 `FRAUD_ENGINE_ERROR`. |

Nguồn thực hiện gồm [Fraud design §§4–6, 8, 10–11](../../../docs-common/design/03.%20fraud-detection-rules.md), [Consistency §5.3](../../../docs-common/design/02.%20consistency-concurrency.md), [Audit design §§4–6](../../../docs-common/design/01.%20audit-traceability.md), [API contracts §8.3](../../../docs-common/07.%20API%20contracts.md) và [Modules contract §§11.1–11.2, 13.4](../../../docs-common/design/05.%20Modules%20contract.md).

## 2. Hợp đồng và thứ tự triển khai

1. **Chuẩn bị schema và rule.** Giữ checksum V1–V20. Dùng migration tiến cho index `uq_fraud_rule_active`, kiểm tra khoảng hiệu lực hợp lệ, chính sách `auto_block` mặc định false và projection lịch sử tối thiểu mô tả trong Modules contract. V21 kiểm dữ liệu cũ có phiên bản active trùng trước khi áp constraint. Đọc rule enabled thỏa `effective_from <= terminalAt < effective_to` (mốc cuối vắng nghĩa là mở). Nếu có nhiều phiên bản cùng hiệu lực cho một code, báo lỗi cấu hình. Bootstrap có kiểm soát hai rule mặc định bằng actor seed hợp lệ và kiểm tra readiness trên DB mới/DB nâng cấp. Không ghi đè cấu hình đã được quản lý hoặc dùng ID actor giả. [Database design §4.7](../../../docs-common/04.%20Database%20design%20%26%20analysis.md)
2. **Nối Fraud JOIN T2 (TRFC-FR-01).** Nhận DTO provider-owned gồm transfer/customer/account IDs, amount/currency, `terminalAt` lấy một lần từ primary và correlation gốc. Sau guard → transfer/fence → Account ASC → Limit CUSTOMER/ACCOUNT và validation hard limit, đọc config và đếm intent, đánh giá in-process, ghi `fraud_flag(OPEN)` qua JDBC cùng connection T2. Dùng evaluator hiện có và sửa phần đếm theo cửa sổ từng rule, lưu phiên bản và dữ kiện đã qua allowlist. Trả descriptor cho các flag vừa insert gồm flag ID, rule code/version và severity. Transfer lấy reference từ intent để ghi Audit. Unique key là `(transfer_id, rule_code, rule_version)`. Unexpected duplicate là invariant failure. Terminal replay trả snapshot cũ và bỏ qua Fraud.
3. **Cô lập engine.** Đặt JDBC savepoint trên cùng connection ngay trước lần đọc/ghi Fraud đầu tiên. Khi lỗi kỹ thuật thuộc Fraud có thể phục hồi, rollback tới savepoint, bỏ descriptor dở dang, xác nhận connection/transaction và JPA context usable, release savepoint rồi mới trả `ENGINE_ERROR` với danh sách rỗng. Dùng JDBC cho toàn bộ work trong savepoint để tránh ORM giữ entity đã rollback. Nếu dùng Spring `MANDATORY`, phải hoàn tất phân loại và xử lý lỗi phục hồi bên trong boundary đó trước khi exception đi qua interceptor. Savepoint lỗi, transaction rollback-only, mất kết nối, invariant sai hoặc lỗi cần retry toàn T2 đi qua nhánh lỗi hệ thống hiện có. Sau rollback an toàn, ghi C4 `FRAUD_ENGINE_ERROR` bằng log/metric best-effort với category, fallback ALLOW và trace IDs. Giới hạn nhãn metric theo outcome/rule/severity và không gắn ID động. Tiếp tục kiểm soát tiền và giữ thời hạn T2.
4. **Ghi Audit ở cuối T2 (TRFC-AU-01).** Flush số dư/ledger, Limit, flag, terminal response/TTL và outbox trước lần lấy audit chain lock. Transfer gọi `recordInTransaction` cho mỗi C2 `FRAUD_FLAG_CREATED` theo flag ID ASC rồi đúng một C1 `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED`, cùng transaction. Dùng actor SYSTEM/entity FRAUD_FLAG cho C2, `flagId` làm entity ID, `transferReference`, `ruleCode`, `ruleVersion`, `severity`, `status=OPEN` trong state, cùng correlation gốc và request ID của attempt. Đồng bộ catalog, validator và projection Audit cho `ruleVersion`. C1 FAILED giữ outcome FAILURE và `failureCode` allowlisted. Rà trigger/constraint/hook ở commit và ghi nhận SQL theo thứ tự để chứng minh không phát sinh business SQL, deferred writes hay network sau audit lock. C2/C1 lỗi rollback T2 và map 500 `AUDIT_WRITE_FAILED`; trả 201/422 chỉ sau commit. Giữ xác minh primary khi commit ACK không rõ.
5. **Kiểm C3 riêng (TRFC-AU-02).** Tái sử dụng `SecurityDenyAudit` và `TransferAuditAdapter` với `recordStandalone` (REQUIRES_NEW). 403 dùng `SECURITY_ACCESS_DENIED`; xung đột actor/customer/hash dùng 409 `IDEMPOTENCY_CONFLICT`, Audit `SECURITY_IDEMPOTENCY_CONFLICT` và entity ID `UNKNOWN`. Fault tại begin/append/commit hoặc provider trả empty/throw vẫn giữ đúng HTTP/body. Ghi `SECURITY_AUDIT_FALLBACK` với actor masked, action, outcome và correlation/request ID. Dùng metric/alert C3 hiện hành, kiểm một lần ghi nhận fallback cho mỗi lần ghi thất bại. Chỉ nhận metadata allowlisted, loại raw key/hash, session/OTP, số tài khoản đầy đủ và nội dung tự do.

## 3. Rule và kết quả T2

| Tình huống | Kết quả cần chứng minh |
|---|---|
| `LARGE_VALUE_TRANSFER` | So sánh BigDecimal `amount >= threshold_amount` (mặc định 10.000.000 VND). Match mặc định tạo cờ HIGH và ALLOW. |
| `HIGH_FREQUENCY_TRANSFER` | Mặc định `count > 5` trong `(terminalAt − 600s, terminalAt]`. Đếm mỗi intent T1 đã commit của customer đúng một lần qua index V13, gồm PENDING/COMPLETED/FAILED và intent hiện tại nếu trong cửa sổ. Một khách hàng khác, replay hay 428 không thêm lượt. Lưu count, ngưỡng, độ dài và mốc cửa sổ cùng version. Flag MEDIUM và ALLOW. |
| `UNUSUAL_PATTERN_TRANSFER` | Tắt mặc định. Cấu hình phiên bản bật rule có thể chọn tổ hợp `amount >= threshold_amount` và `count > threshold_count` trong cửa sổ đã cấu hình. Chỉ policy rõ ràng cho rule này mới cho phép auto-block. |
| ALLOW, có hoặc không cờ | Tiếp tục kiểm soát tiền. Settlement thành công trả 201 với debit/credit, ledger, Limit effect, outbox, C2 cho cờ còn hiệu lực và C1 COMPLETED. Khi rollback money savepoint an toàn dẫn đến FAILED, cờ Fraud được ghi trước money savepoint vẫn còn và đi cùng C2/C1 FAILED. |
| BLOCK | Commit FAILED, HTTP 422 `FRAUD_BLOCKED` cùng reference/status/failureCode theo API. Giải phóng reservation, không debit/credit/ledger/usage effect, giữ flag và C2/C1 FAILED, lưu terminal body và outbox theo policy. Retry cùng key trả lại status/body gốc. |
| ENGINE_ERROR đã phục hồi | Không còn flag/C2 từ lần đánh giá lỗi; C4 best-effort. T2 tiếp tục và phát hành kết quả thực tế theo money path. Khi rollback savepoint không an toàn, T2 abort, intent/claim đã commit vẫn là cơ sở xử lý tiếp. |
| C2 hoặc C1 lỗi | Rollback cả flag, các audit row đã append, tiền, ledger, usage/reservation, outbox, terminal status và terminal replay body. Transfer giữ PENDING, HTTP 500 `AUDIT_WRITE_FAILED`. |

## 4. Ma trận kiểm chứng PostgreSQL

Chạy trong Spring context với PostgreSQL 16, role `banking_app`, cùng datasource/transaction manager với BE-INT-06. Dùng kết nối độc lập để kiểm trạng thái đã commit. Mở rộng fixture HTTP từ BE-INT-05/06 và dùng fault injection tại điểm SQL/commit cần kiểm chứng. Mock module chưa sẵn sàng theo contract, gồm gateway và policy/mapping notification thuộc BE-INT-08; dùng outbox provider thật khi kiểm atomicity.

| Vector | Kịch bản và bằng chứng |
|---|---|
| INT07-01 · Schema và rule | Fresh/upgrade V20 → migration mới, Flyway validate, seed hợp lệ và không ghi đè version cũ. Kiểm threshold T−0,01/T/T+0,01, 4/5/6 intent, biên trái loại/biên phải tính, khách hàng khác, PENDING/COMPLETED/FAILED, intent hiện tại bị trễ, 428/replay và cửa sổ qua nửa đêm. Kiểm cutover, hai cửa sổ khác nhau và UNUSUAL mặc định tắt. |
| INT07-02 · Kết quả và replay | HTTP ALLOW không cờ/một cờ/nhiều cờ, BLOCK và money savepoint credit trả 0 sau khi đã có flag. Kiểm số dư khả dụng/sổ cái, ledger, usage/effect, reservation, outbox, status/body và số cờ theo phiên bản. Replay cả 201/422, kiểm số dòng không tăng và không gọi lại engine. |
| INT07-03 · Engine rollback | Gây lỗi PostgreSQL sau khi đã insert cờ đầu, chứng minh cờ bị rollback và cùng transaction còn dùng được để ghi tiền, ledger và C1; không C2 thừa. Kiểm log/metric C4, cả khi telemetry lỗi. Tiêm rollback-only hoặc lỗi khôi phục savepoint và kiểm T2 abort. |
| INT07-04 · C2/C1 rollback và hash chain | Để ít nhất một C2 append thành công rồi làm lỗi C2 kế tiếp; thử riêng lỗi C1 sau toàn bộ C2, gồm BLOCK. Dùng kết nối độc lập kiểm tất cả bảng và 500 `AUDIT_WRITE_FAILED`. Kiểm trace SQL pha flush/lock/commit, flag ID ASC → C1, verify `prev_hash`/`record_hash` bằng Audit verifier và kiểm không có event mồ côi. |
| INT07-05 · Race | Chạy đồng thời T1/T2 cùng customer để kiểm guard, count và uniqueness. Chạy T2 của hai customer trên hai connection để kiểm audit chain nối đúng và không xen event giữa nhóm C2→C1 của cùng T2. Kiểm invariants Account/Ledger/Limit và replay sau race. |
| INT07-06 · C3 fallback | HTTP 403 ownership trên POST và 409 key conflict khác actor/hash. Kiểm standalone record bền, safe reason và không lộ kết quả cũ. Gây lỗi Audit và xác nhận cùng deny response, fallback log/metric/alert signal, không ghi thêm transfer, cờ, ledger hoặc outbox. |

Đối chiếu `T-FRAUD-01`, `T-FRAUD-B01–06/B08`, `T-AUD-06`, `AC-026` (phát hiện trong T2) và `AC-034`. Chọn `--tests` cho class/method tích hợp mới và regression T0/T1, T2, Fraud/Audit, migration hoặc boundary bị ảnh hưởng. Ghi lý do chọn theo DEC-INT-007. [Test plan §§7, 10](../../../docs-common/09.%20Test%20plan.md)

## 5. Điều kiện đóng

- Provider Fraud thật được gọi qua `fraud.api`, kiểm fail fast ngoài T2 và chứng minh JOIN trên PostgreSQL. Cấu hình tích hợp chỉ cấp adapter thật khi có provider và rule bootstrap sẵn sàng.
- Lưu `BE-INT-07-verification.md` và artifact trong `evidence/BE-INT-07/` với revision hoặc source manifest, môi trường, lệnh/lý do lọc, expected/actual từng vector, JUnit/log sạch dữ liệu nhạy cảm và bằng chứng SQL/chain. Cập nhật MOD-OI-05 và phần Fraud của MOD-OI-06 theo kết quả thực tế.
- BE-INT-08 và BE-INT-10 tiếp tục cung cấp evidence cho production activation. D5 ở gate phát hành kiểm 20 RPS trong 10 phút, p95 ≤ 2s, p99 ≤ 5s, lỗi < 1% và invariants sau tải. [Kế hoạch tích hợp](README.md)
