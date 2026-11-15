# BE-TRF-01 — Đề xuất phối hợp audit C2/C1 trong T2

**Trạng thái: Draft — chờ Audit, Fraud, Transfer và Architect review; chưa phải interface, policy hay implementation đã được duyệt.** Người theo dõi đề xuất: Transfer/Vũ; provider Audit/Fraud và QA cùng review. Không đánh dấu MOD-OI-05 hoặc TRF-01.6 hoàn tất chỉ dựa trên hồ sơ này.

## 1. Căn cứ và vấn đề

- [SRS](../../../../../docs-common/03.%20SRS.md) FR-034/AC-034 yêu cầu audit bền vững trước khi báo `COMPLETED`; lỗi audit không được tạo false success.
- [Thiết kế audit](../../../../../docs-common/design/01.%20audit-traceability.md) §§4.4–5.3 đặt C1/C2 trong transaction nghiệp vụ; `FRAUD_FLAG_CREATED` là C2; `TRANSFER_COMPLETED/FAILED` là C1; pha ghi audit ở cuối trước commit.
- [Modules contract](../../../../../docs-common/design/05.%20Modules%20contract.md) §11.1–11.2 và MOD-OI-05 đã mô tả `recordInTransaction` MANDATORY, fraud JOIN T2 và phương án gom event; API batch, kiểu dữ liệu, thứ tự và propagation thực tế chưa được chốt. Tài liệu này cụ thể hóa phương án để review, không khẳng định provider đã có hoặc phê duyệt interface.

Vấn đề cần giải quyết: fraud có thể tạo một hoặc nhiều flag trong T2. Ghi C2 ngay trong fraud có thể giữ audit chain lock xuyên phần còn lại của money path; trì hoãn C2 mà không bảo đảm event khớp flag đã lưu hoặc để lỗi audit bị fraud fail-open nuốt sẽ vi phạm atomicity. C1 và C2 phải cùng commit hoặc cùng rollback với thay đổi nghiệp vụ liên quan.

## 2. Phương án A — gom dữ kiện, gọi API đơn trong pha audit cuối

**Đề xuất để review:** tái sử dụng operation audit khái niệm `recordInTransaction(AuditEvent)` tại `05` §11.1; chưa giả định implementation có thể gọi lặp an toàn cho đến khi Audit xác nhận và test. Không cần giả định một batch API runtime mới.

1. Transfer claim transfer, Account/Limit lock theo thứ tự contract; Fraud đánh giá và ghi các flag thuộc owner Fraud trong T2. Fraud trả kết quả `ALLOW`/`BLOCK` hoặc `ENGINE_ERROR` cùng dữ kiện tối thiểu để tạo C2 cho **mỗi flag thực sự còn trong transaction**. Dữ kiện dự kiến gồm flag ID, transfer reference, rule code/version, severity, correlation và các trường allowlist cần thiết; kiểu, nullability, mapping sang `AuditEvent` và trách nhiệm validate cần Fraud/Audit/Transfer thống nhất. Không truyền entity/JPA, token, OTP, raw key hay PII không cần thiết.
2. Nếu engine lỗi thuộc diện fail-open, rollback phần engine về savepoint và loại bỏ toàn bộ dữ kiện C2 tương ứng flag đã rollback. Chỉ tiếp tục nếu transaction **và persistence context** thực sự còn sử dụng được; nếu không chứng minh được thì rollback toàn T2. Không bao lỗi audit C1/C2 hoặc invariant tiền trong phạm vi catch/fail-open. Ghi log/metric hoặc xử lý C4 theo contract mà không làm audit bắt buộc thành best-effort.
3. Hoàn tất nhánh nghiệp vụ: `ALLOW` tiếp tục money/ledger/usage; `BLOCK` xử lý nhánh từ chối theo policy. Hoàn tất thay đổi business, outbox, terminal status và idempotency response trước pha audit. Không gọi gateway/HTTP trong T2. Nếu transaction bị rollback thì dữ kiện lưu trong bộ nhớ bị bỏ, không phát ra event độc lập sau rollback.
4. Trong **pha audit cuối** của cùng PostgreSQL transaction, dựng/validate event theo allowlist và ghi một C2 cho mỗi flag còn tồn tại theo thứ tự ổn định được Audit/Fraud thống nhất, sau đó ghi C1 `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED`. Audit sở hữu canonicalization, chain và lock; sau khi lấy audit lock không thực hiện business DB operation hay gọi network. Audit bảo đảm mỗi lần append tiếp nối chain đúng, kể cả nhiều event trong cùng transaction. Không suy ra một câu SQL duy nhất là bắt buộc: “cuối” ở đây là pha audit cuối; Audit owner xác nhận cách diễn giải phù hợp `design/01` §4.4.
5. Bất kỳ lỗi validate, lock hay ghi C2/C1 bắt buộc phải lan ra để rollback toàn T2, gồm flag, balance, ledger, usage, outbox, terminal/idempotency response và mọi audit đã ghi trong T2. T1 đã commit vẫn giữ `PENDING`/audit CREATED. Không trả `COMPLETED`; retry cùng key theo contract. `FRAUD_ENGINE_ERROR` là C4, không thay thế C2/C1 bắt buộc.

**Cần xác nhận trước khi chọn:** số flag tối đa và thứ tự ổn định; ownership dữ kiện/sự kiện và kiểm tra đủ một C2/flag; cách dùng savepoint cùng persistence context; reentrant lock/chain của nhiều lời gọi `recordInTransaction`; mapping lỗi và test provider thật. Không tự coi danh sách dữ kiện đã được thêm vào `FraudDecision` hoặc audit batch đã tồn tại.

## 3. Phương án B — audit-owned batch

Thay bước 4 bằng operation batch do Audit sở hữu nhận danh sách `AuditEvent` đã sắp thứ tự, JOIN/MANDATORY T2; Audit validate toàn bộ, lấy chain lock và append C2 rồi C1 trong một pha, lỗi bất kỳ rollback toàn T2. Có thể giảm số lần qua interface và giúp Audit kiểm soát thứ tự. Đổi lại cần public API/DTO mới, quy định lỗi theo phần tử, giới hạn batch, tính nguyên tử và phối hợp version provider/consumer. Đây chỉ là **đề xuất thay thế**, không phải interface đã được duyệt. Audit/Fraud/Transfer/Architect quyết định giữa A và B; lựa chọn cần cập nhật hợp đồng `05`, version/PR và suite liên quan.

## 4. Vector review và integration

Các vector dưới đây là **Planned**, chưa được chạy hay owner duyệt. Dùng cùng dữ liệu synthetic cho fake và provider suite; dùng PostgreSQL/provider thật, connection độc lập và failure injection để chứng minh lock, savepoint, chain và rollback. Mỗi lần chạy lưu revision, lệnh, môi trường, fixture, expected/actual, trạng thái pass/fail/skip và report theo `09` §10.

| Case | Thiết lập / điểm lỗi | Oracle cần review |
|---|---|---|
| AUD-P-01 | Fraud ALLOW tạo nhiều flag; T2 thành công. | Mỗi flag có đúng một C2 liên kết đúng ID; có một C1 COMPLETED sau các C2; chain hợp lệ; balance, ledger, usage, outbox, terminal/idempotency và audit cùng commit. |
| AUD-P-02 | Fraud BLOCK tạo flag, policy fixture cho phép auto-block. | Flag và C2 cùng C1 FAILED commit theo oracle business được duyệt; không debit/credit, ledger hay increment usage. |
| AUD-P-03 | Inject lỗi C2 sau khi đã append ít nhất một C2; test cả validation và lỗi DB. | Toàn T2 rollback, bao gồm mọi audit T2 đã append; không flag, money, ledger, usage, outbox hoặc terminal từ T2; T1 còn PENDING; không false success. |
| AUD-P-04 | Inject lỗi C1 sau khi tất cả C2 đã append. | Mọi C2 trong T2 cũng rollback cùng toàn bộ business changes; T1 còn PENDING. Retry sau khi lỗi được khắc phục không tạo audit/ledger trùng. |
| AUD-P-05 | Inject lỗi engine sau khi đã ghi ít nhất một flag trong savepoint; rollback savepoint thành công. | Không còn flag hoặc C2 tương ứng phần đã rollback; transaction và persistence context dùng được; tiếp tục theo fail-open, C1 vẫn bắt buộc. Lặp lại với savepoint/transaction không phục hồi được: rollback T2, không cố commit. |
| AUD-P-06 | Lỗi audit C2/C1 được ném sau khi fraud kết thúc. | Lỗi không bị catch như engine error; T2 rollback và không báo success. |
| AUD-P-07 | Hai T2 dùng connection độc lập cùng ghi nhiều C2/C1; có barrier và timeout. | Chain không fork/mất event, thứ tự trong từng pha đúng phương án được duyệt, không deadlock không được xử lý; xác nhận business writes hoàn tất trước audit lock và không network dưới lock. |
| AUD-P-08 | Gọi audit critical không có transaction; inject lỗi C1 CREATED trong T1. | MANDATORY fail fast khi thiếu transaction; T1 audit fail rollback transfer, idempotency và challenge consume; không side effect tiền. |

`T-AUD-06/07` và `AC-034` là trace nguồn cho lỗi audit; các mã `AUD-P-*` chỉ là mã nội bộ của hồ sơ này, không phải kết quả test đã đăng ký trong `09`.

## 5. Nội dung yêu cầu owner review

- **Audit:** ownership và schema event, allowlist, serialization/hash chain, nhiều event cùng transaction, thứ tự và hành vi khi lỗi giữa các event.
- **Fraud:** dữ kiện cần trả cho từng flag thực sự tồn tại sau savepoint, scope fail-open, không để rollback savepoint sót event trong bộ nhớ hoặc entity stale.
- **Transfer + Architect:** ranh giới pha audit cuối, lock order, lựa chọn A/B, rollback và version/interface; không báo đã tích hợp chỉ vì fake pass.
- **QA:** duyệt oracle `AUD-P-01–08`, map vào test suite thật và lưu evidence; **Security:** kiểm allowlist/masking. Các phê duyệt, revision interface và kết quả test cần được liên kết trong hồ sơ bàn giao sau khi có thật.
