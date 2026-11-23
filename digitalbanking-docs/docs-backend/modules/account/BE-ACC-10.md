# BE-ACC-10 — Opening provenance / reconciliation

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-10
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** **BLOCKED — contract** (không có thay đổi code/migration trong task này)
> **Owner:** Đạt (Account) — phối hợp Phương (Ledger) + DBA
> **Reviewer:** Chưa có — cần owner Ledger + DBA + BA
> **Branch:** `datht-backend-rework`
> **Nguồn tham chiếu:** `docs-common/04. Database design & analysis.md` §4.3.1 (`account`), §4.4.2 (`ledger_entry`); `docs-common/design/02. consistency-concurrency.md` §10.1–§10.3 (reconciliation transfer), invariant I-CON-13; `docs-common/design/05. Modules contract.md` §7; [BE-ACC-01](BE-ACC-01.md) ACC-DR-06; [BE-ACC-02](BE-ACC-02.md) §B (V8 không có cột opening); [BE-ACC-07](BE-ACC-07.md) §F; [DEC-ACC-009](../../../../DECISIONS.md)

---

## A. Phạm vi task (theo kế hoạch)

| Hạng mục | Yêu cầu kế hoạch | Trạng thái |
|---|---|---|
| Provenance của opening balance | Lưu nguồn gốc số dư ban đầu, truy vết được, không sửa lặng lẽ | **BLOCKED** |
| Quan hệ opening event ↔ Ledger | Opening phải có bút toán/record đối chiếu được | **BLOCKED** |
| Idempotency khi mở tài khoản | Retry không tạo provenance/opening trùng | **BLOCKED** (provisioning ACC-DR-04 cũng BLOCKED) |
| Reconciliation | `ledger_balance = opening + Σcredits − Σdebits`, alert khi lệch/không xác minh được | **BLOCKED** |
| Auditability | Event C2/audit cho mở tài khoản và reconciliation | **BLOCKED** (durable audit ACC-DR-15) |

---

## B. Hiện trạng đã kiểm tra (18/11/2026)

| Hạng mục | Bằng chứng | Kết luận |
|---|---|---|
| Cột provenance trong `banking.account` | `V8__create_account_table.sql` có đúng 12 cột `04` §4.3.1; comment dòng 12 ghi rõ "no opening-balance/provider column (ACC-DR-06 still blocked)" | **Không có** cột opening/provenance |
| Bảng/ledger runtime | `digitalbanking-backend/src/main/java/com/simulatedbank/banking/ledger/**` chỉ có `.gitkeep`; `docs-backend/modules/ledger/README.md` chỉ có tiêu đề | **Không có** entity/port/API Ledger |
| Migration Ledger | `V1`–`V8` không tạo `transfer`/`ledger_entry`/`idempotency_record` | **Không có** schema Ledger để đối chiếu |
| Contract Ledger | Chưa có `ledger.api`, chưa có operation/record/hợp đồng transaction nào trong repo | **Không có** provider/consumer contract |
| Reconciliation trong `design/02` §10 | Chỉ định nghĩa reconciliation cho `transfer` PENDING (`reconcile(transferId)`, ledger 2 entry + audit completion) | **Không** định nghĩa reconcile opening balance/account baseline |
| Invariant `ledger_balance` | `04` §4.3.1/§4.4.2 và `_overview/03-account.md` nêu `ledger_balance` materialized từ ledger, nhưng không có projection/công thức chốt với opening | **Chưa đủ** để implement |
| Provisioning | ACC-DR-04 BLOCKED: chưa có operation tạo account/sinh số/opening | Không có write path để gắn provenance |

Kết luận: **contract vẫn thiếu**. Không có cơ sở để thêm cột/bảng, API Ledger, công thức reconcile hay job nào mà không tự phát minh hợp đồng tài chính.

---

## C. Quyết định hợp đồng còn thiếu (phải chốt trước khi implement)

| # | Quyết định | Vì sao chặn BE-ACC-10 | Owner |
|---|---|---|---|
| 1 | **Mô hình provenance**: cột `opening_balance`/`opening_reference` trên `banking.account`, hay bảng append-only `account_opening`/`opening_balance_record`, hay chỉ dựa vào `ledger_entry` loại `OPENING`? | `04` §4.3.1 không có cột; thêm cột/bảng là thay đổi schema canonical phải qua `04` + migration mới | Ledger + DBA + BA |
| 2 | **Ai ghi opening**: Account (khi provision) hay Ledger (bút toán opening) hay orchestrator? Transaction boundary? | Chưa có provisioning contract (ACC-DR-04); hai module đều chưa có write path | Account + Ledger |
| 3 | **Ánh xạ sang `ledger_entry`**: `04` §4.4.2 yêu cầu `transfer_id NOT NULL` và `entry_seq IN (1,2)` — opening không có transfer. Cần entry type mới, `transfer_id` nullable, hay bảng riêng? | Không thể insert opening entry vào schema hiện tại; tự nới constraint là thay đổi tài chính chưa duyệt | Ledger + DBA |
| 4 | **Idempotency của opening**: key/scope (account number? provision request? customer+currency?), unique constraint, replay/conflict semantics | Không có contract provisioning/idempotency nào của Account; Transfer idempotency không tái sử dụng được | BA + Account + DBA |
| 5 | **Công thức & phạm vi reconcile**: `ledger_balance = opening + Σcredits − Σdebits`? Tolerance (phải `= 0`)? Theo account/kỳ/ngày? Snapshot nào? | `design/02` §10 chỉ reconcile transfer, không định nghĩa baseline opening; không thể chọn công thức tùy ý | BA + Ledger + DBA |
| 6 | **Hành vi khi lệch/không xác minh được**: alert/incident, không tự sửa; job interval/lease/`SKIP LOCKED`; runbook; ai nhận alert | Chưa có contract/runbook; `design/02` §10.4 chỉ có runbook transfer | Ops + BA + Ledger |
| 7 | **Read/API exposure**: có endpoint/port trả provenance/reconciliation không, quyền nào, DTO nào? | `07`/OpenAPI không có endpoint; không được tự thêm route | BA + Security |
| 8 | **Audit action**: tên action C1/C2 (`ACCOUNT_OPENED`, `OPENING_BALANCE_RECORDED`, `ACCOUNT_RECONCILED`?), payload allowlist, fail-closed? | Audit module chưa có runtime (ACC-DR-15); catalog chưa có action này | Audit + BA |

---

## D. Phương án khi dependency thiếu (đang áp dụng)

1. **Không** thêm cột/bảng/migration vào `V8` hoặc migration mới; **không** sửa `V1`–`V8`.
2. **Không** tạo `ledger.api`, entity, repository hay bảng `ledger_entry` thay owner Ledger.
3. **Không** implement job reconciliation, projection hay alert khi chưa có công thức/runbook được duyệt.
4. **Không** seed opening balance giả trong test hay production; `ledger_balance` hiện chỉ thay đổi qua money path `AccountBalance` (BE-ACC-07).
5. Giữ `ledger_balance >= 0` và `available_balance <= ledger_balance` (CHECK `V8`) làm invariant cuối; không dùng reconcile để bỏ qua constraint.
6. Khi các quyết định ở §C được chốt: cập nhật `04`/`design/02`/`design/05` + OpenAPI trước, sau đó implement theo thứ tự schema → provenance write (gắn provisioning) → Ledger contract → reconcile job/read model → test PostgreSQL (retry, duplicate, lệch, thiếu baseline).

---

## E. Test & verification

| Hạng mục | Kết quả |
|---|---|
| Code/migration mới | **Không có** (đúng trạng thái BLOCKED) |
| Test mới | **Không có** — không có hành vi nào để kiểm chứng |
| Rà soát schema hiện tại | `V1`–`V8`: không có opening/provenance/ledger table |
| Rà soát module Ledger | package `ledger` rỗng (chỉ `.gitkeep`), README trống |
| Rủi ro nếu implement sớm | sai công thức tài chính, mở write path chưa duyệt, trùng bút toán khi retry, che giấu lệch số dư |

---

## F. Acceptance criteria status

| Tiêu chí | Trạng thái |
|---|---|
| Provenance persistence đúng hợp đồng | **BLOCKED** (§C #1–#4) |
| Idempotent opening, không duplicate khi retry | **BLOCKED** (ACC-DR-04 + §C #4) |
| Liên kết opening ↔ Ledger | **BLOCKED** (§C #3, Ledger runtime rỗng) |
| Reconciliation chính xác + alert | **BLOCKED** (§C #5–#6) |
| Test retry/duplicate/failure/inconsistent | **BLOCKED** (không có implementation) |
| Không tự cân đối số lệch | **PASS** — không có code nào sửa số dư ngoài money path đã duyệt |

Kết luận: `BE-ACC-10` giữ **BLOCKED — contract**. Không có phần nào đủ điều kiện để đánh dấu PARTIAL vì mọi thành phần đều phụ thuộc mô hình provenance/Ledger chưa được duyệt.
