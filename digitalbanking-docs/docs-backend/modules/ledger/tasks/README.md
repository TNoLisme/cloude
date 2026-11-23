# Kế hoạch và checklist triển khai `ledger`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-MOD-LEDGER-02
> **Phiên bản:** v1.1 — 17/11/2026
> **Trạng thái:** Draft — chờ review; checklist phản ánh công việc chưa nghiệm thu
> **Owner:** Phương; **Reviewer:** Transfer, Account, DB, API, Audit, Security, QA, Ops

Nguồn và workflow: [thiết kế Ledger](../design.md), `03` FR-015–022/027–030/034/038/039, `04` §4.4.2, `07` §8.4, `09`, `design/02`, `design/05` §9/§13.4. P0 là điều kiện an toàn hoặc nghiệm thu MVP; P1 là việc vận hành/bổ sung, không miễn yêu cầu Must. Task chỉ hoàn thành khi mọi AC có evidence và provider/reviewer liên quan xác nhận; fake chỉ test contract, không thay integration.

| Trạng thái | ID / ưu tiên   | Công việc                                | Phụ thuộc                                            |
| ---------- | -------------- | ---------------------------------------- | ---------------------------------------------------- |
| Review     | [BE-LED-01](BE-LED-01.md) / P0 | Chốt boundary, hợp đồng và quyết định mở | Transfer, Account, API, DB, Security, QA             |
| Candidate  | [BE-LED-02](BE-LED-02.md) / P0 | Migration, DB constraints và append-only | 01; Account/Transfer migration, DB roles             |
| Candidate  | [BE-LED-03](BE-LED-03.md) / P0 | Posting port, domain và persistence    | 01–02; Account/Transfer contract                     |
| [ ]        | BE-LED-04 / P0 | Ghép T2, concurrency và failure          | 03; Transfer, Account, Limit, Audit thật             |
| [ ]        | BE-LED-05 / P0 | Phối hợp History API do Transfer sở hữu  | 01; Account projection, Identity/Common, API         |
| [ ]        | BE-LED-06 / P0 | Evidence và recovery/reconciliation      | 02–04; Transfer/Account/Limit/Audit, opening balance |
| [ ]        | BE-LED-07 / P0 | Security, audit và observability         | 03–06; Identity, Audit, Security, Ops                |
| [ ]        | BE-LED-08 / P0 | Kiểm thử integration D1–D3               | 02–07; PostgreSQL và providers thật                  |
| [ ]        | BE-LED-09 / P0 | Nghiệm thu, D5 và bàn giao               | 01–08; QA, Security, Ops, môi trường                 |

Đường găng: 01 → 02 → 03 → 04 → 06/08 → 09; 05 và thiết kế test làm song song khi contract cho phép. Các mục sau mô tả AC kiểm chứng được, không khẳng định đã có code.

## [BE-LED-01 — Contract và quyết định](BE-LED-01.md)

**Mô tả:** review ownership, `postDoubleEntry`/`findEvidence`, transaction/error, read composition, staff scope, schema drift overview và seed/opening balance. Phối hợp MOD-OI-08, MOD-OI-02–04 và DEC-TRF-003/004/006/014.

**AC:** [ ] Ma trận provider/consumer, DTO, scope, lỗi và JOIN được owner review; [ ] quyết định read owner, pagination và nguồn số dư mở sổ có trace và cập nhật nguồn contract tương ứng; [ ] rủi ro chưa chốt có owner, gate và phương án test-only fake. **Evidence:** DEC-LED-001–003, baseline `05` §9/§12.3/§13.4 và [kết quả fixture](evidence/BE-LED-01.md); owner review còn chờ.

## [BE-LED-02 — Database](BE-LED-02.md)

**Mô tả:** tạo forward-only migration `ledger_entry` theo `04` §4.4.2 sau schema owner khác; phối hợp số version, FK và DB role; bảo vệ append-only.

Chi tiết schema, hardening runtime/trigger, gate duplicate V2 và prerequisites, oracle LED-DB-01–12, evidence và bàn giao nằm trong [đặc tả BE-LED-02](BE-LED-02.md). SQL candidate và [evidence PostgreSQL](evidence/BE-LED-02.md) đã có. Chain production và Hibernate validation chờ migration owner và mapping BE-LED-03.

**AC:** [ ] PostgreSQL kiểm PK/FK, seq/type, amount, currency, balance, unique và index; [ ] app role UPDATE/DELETE/TRUNCATE bị từ chối theo ACL; trigger chặn ba thao tác độc lập với ACL; [ ] fresh/upgrade Flyway và schema validate đạt, không sửa migration đã phát hành; [ ] chứng minh constraint không được trình bày như proof đủ cặp. **Evidence:** migration review và PostgreSQL test/role output.

## [BE-LED-03 — Posting](BE-LED-03.md)

**Mô tả:** implement domain value objects, provider API immutable, service và adapter persistence theo package-by-feature; dùng `BigDecimal`/VND, không export entity.

Chi tiết DTO/validation, MANDATORY, mapping, flush/read-back/kiểm cặp, lỗi rollback, LED-POST-01–14 và gate provider thật nằm trong [đặc tả BE-LED-03](BE-LED-03.md). Provider đã triển khai theo DEC-LED-005 (api/domain/application/adapter, read-back native projection, taxonomy lỗi qua Common); 103 test mới PASS trên PostgreSQL 16 candidate, xem [evidence](evidence/BE-LED-03.md). Chain production, ghép T2 thật và nghiệm thu vẫn Blocked.

**AC:** [x] MANDATORY fail khi không có T2; source/destination khác nhau, amount/scale/currency và snapshots hợp lệ; [x] ghi đúng DEBIT seq 1/CREDIT seq 2, cùng amount, đúng balance sau Account và correlation; [x] duplicate/partial/error throw rollback, không coi là replay; [x] rollback sau mỗi insert không còn entry (candidate evidence). **Evidence:** unit/contract và PostgreSQL tests LED-POST-01–14; code review và integration provider thật còn chờ.

## BE-LED-04 — T2 và đồng thời

**Mô tả:** ghép Transfer orchestration với Account/Ledger/Limit/Audit cùng transaction, theo lock order guard → transfer/fence → account ASC → usage → audit cuối; không network dưới lock.

**AC:** [ ] PostgreSQL nhiều connection chứng minh cùng nguồn không âm, A→B/B→A, status/balance đổi sau preflight và bảo toàn tổng; [ ] audit/credit/ledger failure rollback toàn money, usage, entries và terminal; [ ] savepoint/Tfail chỉ dùng khi protocol, rollback và fence được chứng minh; [ ] timeout/deadlock retry toàn transaction mới có giới hạn, unknown commit không tự chạy lại. **Evidence:** integration/failure injection và lock trace provider thật.

## BE-LED-05 — Transaction history

**Mô tả:** Transfer xây API-TXN-01/02 theo `07` §8.4 và DEC-LED-002; Ledger phối hợp review boundary. Query tối ưu theo scope và phân trang, reuse Common security/error.

**AC:** [ ] List đủ PENDING/COMPLETED/FAILED, filter/time/direction và INTERNAL đúng, một transfer một dòng; [ ] detail và list trả DTO/Problem, masking và pagination đúng contract; [ ] customer không xem người khác; staff chỉ trong scope, filter/sort được validate, counts không leak; [ ] contract OpenAPI, no-store, IDOR, permission, status và account filter được test. **Evidence:** HTTP/contract/security tests với Identity/Account/Transfer thật.

## BE-LED-06 — Evidence và đối soát

**Mô tả:** cung cấp `findEvidence` đầy đủ từng entry và xây truy vấn/check vận hành theo contract đã review; phối hợp Transfer recovery, Account baseline và Ops.

**AC:** [ ] Phân biệt vắng, đủ, thiếu, trùng, lệch và DB error; kiểm seq/type/account/amount/currency/balanceAfter/correlation liên kết; [ ] đối soát balance dựa baseline opening đã duyệt và snapshot nhất quán, không bỏ qua seed; [ ] incident alert, không tự sửa/xóa/post lại; [ ] recovery chỉ complete khi evidence Account/Limit/Audit/response đủ, giữ nguyên/alert nếu thiếu hoặc unknown commit. **Evidence:** fixtures sai lệch, PostgreSQL recovery tests, runbook/owner review.

## BE-LED-07 — Security và vận hành

**Mô tả:** dùng session/permission, scope, audit C1/C3, correlation, structured logs và metrics sẵn có; triển khai alerts và runbook.

**AC:** [ ] Không lộ full account, key, secrets, PII hoặc số dư counterparty; [ ] deny audit và C1 failure theo contract, C1 rollback T2; [ ] metrics/alerts cho partial pair, mismatch, PENDING và lỗi recovery có test; [ ] không có fake production hoặc ghi chéo bảng. **Evidence:** security review, log tests và diễn tập Ops.

## BE-LED-08 — Kiểm thử tích hợp

**Mô tả:** test contract và invariant với PostgreSQL thật, provider thật; tái sử dụng common/test infrastructure, fake theo `05` chỉ khi phụ thuộc chưa sẵn sàng.

**AC:** [ ] D1 race, tổng tiền, balance, pair và limit; [ ] D2 same key, payload khác, actor khác, replay, crash không ghi kép; [ ] D3 lỗi từng bước, audit, partial, unknown commit, hai recovery worker/fence, evidence đủ/thiếu/lệch; [ ] mỗi kịch bản assert cả status, balances, entries, audit, idempotency và outbox theo policy. **Evidence:** kết quả có version/command, PostgreSQL logs và coverage matrix theo `09`; fake không đóng integration gate.

## BE-LED-09 — Nghiệm thu và bàn giao

**Mô tả:** chạy suite được yêu cầu, D5 và diễn tập vận hành; rà migration, tài liệu, alert và unresolved decisions cùng các owner.

**AC:** [ ] D1–D3 và các acceptance liên quan có evidence provider thật; [ ] D5 20 RPS × 10 phút theo `09` được đo và báo cả invariant/latency/error; [ ] migration clean/upgrade, runbook reconciliation/recovery, security và review QA/Ops hoàn tất; [ ] open blocker được giải quyết hoặc ghi release gate rõ ràng. **Evidence:** báo cáo test, reviewer sign-off và runbook.
