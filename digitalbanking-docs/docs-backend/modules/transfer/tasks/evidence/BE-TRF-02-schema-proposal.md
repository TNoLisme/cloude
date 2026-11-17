# BE-TRF-02 — Đề xuất vị trí lưu TTL và recovery/fence

> **Lưu trữ lịch sử:** đề xuất dưới đây được viết trước khi người điều hành ủy quyền quyết định thiết kế. [DEC-TRF-013](../../../../../../DECISIONS.md) đã chọn vị trí/cột và cập nhật `04` §4.4; SQL candidate nằm ở `digitalbanking-backend/src/main/resources/db/transfer-candidate/V2__transfer.sql` ngoài Flyway location production. Những đoạn bên dưới nói phương án chưa được chọn phản ánh thời điểm lập đề xuất. DBA/provider/Ops review và evidence integration vẫn còn thiếu.

> **Đề xuất chưa duyệt, không phải schema, migration hay sign-off hiện có.** Mục đích là cung cấp phương án cụ thể để Transfer, DBA/Architect, Ops và các owner liên quan review. Các tên/kiểu/check/thuật toán dưới đây là lựa chọn đề xuất, không có nghĩa đã được quyết định hay chứng minh an toàn. Không triển khai migration, bật worker hoặc đóng task dựa riêng vào tài liệu này.

Nguồn: [BE-TRF-02 §2, §5–8](../BE-TRF-02.md), [schema matrix](BE-TRF-02-schema-matrix.md), [Database design 04](../../../../../docs-common/04.%20Database%20design%20%26%20analysis.md) §4.4, §7.7, §9.6, [Modules contract 05](../../../../../docs-common/design/05.%20Modules%20contract.md) §8, §10.1, §13–14 và [DECISIONS](../../../../../../DECISIONS.md) DEC-TRF-003, 005, 006, 008, 010. `04` hiện chưa định nghĩa đầy đủ vị trí/kiểu snapshot, fence/recovery; các thay đổi vật lý phải được review và đồng bộ `04` trước khi tạo migration theo task.

## 1. Phương án đề xuất

Giữ quyền sở hữu trong hai bảng Transfer đã có trong baseline. Các cột TTL đặt tại `banking.idempotency_record`, cùng vòng đời với response replay; các cột fence/recovery đặt tại `banking.transfer`, tồn tại độc lập với việc purge idempotency terminal. Không tạo bảng Customer/Identity/Account/Limit hoặc một bảng production riêng trong đề xuất này. Nhược điểm: thông tin TTL tại idempotency bị mất khi purge, và các cột điều khiển làm bảng transfer rộng hơn; nếu Ops/Compliance cần truy vết lâu hơn, owner cần chọn nơi lưu evidence bền khác trước khi duyệt. Không đưa dữ liệu nhạy cảm, idempotency key hoặc payload vào các cột điều khiển.

### 1.1. Snapshot TTL trên `idempotency_record`

| Cột đề xuất | Kiểu / null | Invariant đề xuất |
|---|---|---|
| `ttl_t1_seconds` | BIGINT NOT NULL | `>= 86400`; giá trị snapshot tại T1, không thay đổi sau T1 |
| `ttl_t1_config_version` | VARCHAR(128) NOT NULL | định danh phiên bản config không rỗng, xác định được nguồn cấu hình |
| `ttl_terminal_seconds` | BIGINT NULL | `>= 86400` khi có; bắt buộc khi terminal |
| `ttl_terminal_config_version` | VARCHAR(128) NULL | không rỗng khi có; bắt buộc cùng `ttl_terminal_seconds` khi terminal |

T1 đọc một snapshot cấu hình hợp lệ, lưu hai giá trị T1, đặt `expires_at = created_at + TTL_T1`. Transaction terminal đọc **một snapshot mới**, kiểm hợp lệ/fail-closed rồi atomically lưu hai giá trị terminal, response gốc đã mask, status, `completed_at` và `expires_at = max(expires_at, completed_at + TTL_terminal)`. Cột terminal ban đầu NULL và chỉ được gán khi terminal; check yêu cầu cặp terminal cùng NULL khi nonterminal, cùng khác NULL khi terminal là **đề xuất thêm**, cần DBA/Transfer review so với baseline. Giới hạn tối đa TTL và phép cộng timestamp phải kiểm trước khi ghi; không tự giả định con số trần, chờ Ops quyết định. Dùng timestamp DB/UTC theo DEC-TRF-008 và không coi timestamp là thời điểm commit. Purge chỉ terminal quá hạn sau lock/recheck; record còn tồn tại vẫn replay/conflict, không purge nonterminal. Chọn giây nguyên, version dạng chuỗi và retention của snapshot là giả định cần xác nhận; config/version phải đọc nhất quán từ cùng nguồn snapshot, lỗi đọc hoặc version thiếu không được coi là hợp lệ.

### 1.2. Fence và retry trên `transfer`

| Cột đề xuất | Kiểu / null | Invariant và mục đích đề xuất |
|---|---|---|
| `attempt_epoch` | BIGINT NOT NULL DEFAULT 0 | không âm, tăng đơn điệu khi claim/rearm; không bao giờ quay lại/reuse, overflow fail-closed |
| `attempt_token` | UUID NULL | token mới cho mỗi claim; không log token |
| `attempt_owner` | VARCHAR(128) NULL | định danh worker/attempt không chứa PII; cùng có/không với token và lease |
| `attempt_lease_expires_at` | TIMESTAMPTZ NULL | hạn claim theo DB primary; hết hạn không chứng minh rollback |
| `attempt_state` | VARCHAR(24) NOT NULL DEFAULT `'IDLE'` | đề xuất `IDLE`, `CLAIMED`, `FAILURE_PENDING`, `MANUAL`, `DONE`; semantics cần review |
| `recovery_attempt_count` | INTEGER NOT NULL DEFAULT 0 | không âm; chỉ tăng khi recovery claim thành công, không tăng khi lock bận/skip |
| `recovery_first_attempt_at` | TIMESTAMPTZ NULL | đặt khi claim recovery đầu tiên, bền qua restart |
| `recovery_next_attempt_at` | TIMESTAMPTZ NULL | mốc eligibility sau retry; không dùng làm chứng cứ an toàn money |

Claim và token/owner/lease chỉ hợp lệ cùng nhau ở `CLAIMED`/`FAILURE_PENDING`; `IDLE`/`MANUAL`/`DONE` không giữ claim active. Count = 0 ⇒ first-attempt NULL; count > 0 ⇒ first-attempt khác NULL. Các check này chỉ bảo vệ hình dạng dữ liệu: thứ tự epoch, quyền claim, giới hạn retry và race cần transaction/CAS và test thật. `FAILURE_PENDING` là dấu dừng xử lý thường, không tự chứng minh rollback hoặc failure: bằng chứng tương ứng phải được xác thực/ghi qua giao thức được review. Đề xuất **không lưu raw exception, thông tin tài khoản hoặc payload**; nơi lưu và format bằng chứng failure có thể kiểm chứng được vẫn cần Transfer/Account/DBA/Ops chốt. Nếu thiếu nơi lưu chứng cứ đáng tin cậy, không dùng Tfail tự động.

Một attempt đầu tiên chạy T2 cũng phải có claim/fence bền trước money write; counter trên chỉ đếm recovery scheduler. Đề xuất T1 tạo transfer `IDLE`, sau đó claim ở transaction ngắn trước T2; crash giữa T1 và claim giữ PENDING cho recovery. `attempt_state` và transfer status có vai trò khác nhau: chỉ transfer status là trạng thái nghiệp vụ, `DONE` không thay thế terminal. Có thể thay các cột bằng bảng `transfer_attempt` riêng nếu cần lịch sử audit đầy đủ hoặc giảm contention, nhưng phải duyệt ownership, FK/retention và lock order; không mặc nhiên bổ sung bảng mới.

## 2. Giao thức đề xuất và xử lý lỗi

1. Đọc candidate/context trên primary không khóa, sau đó lấy **guard customer → lock/re-read transfer**; kiểm context, PENDING, điều kiện retry, epoch/state và claim theo contract `05`. Nếu lock bận thì skip, không tính attempt. Trong cùng transaction tăng epoch, phát token mới, đặt lease, ghi attempt count/first/next thích hợp; commit trước khi xử lý. Lease duration và cách cấp token là câu hỏi review; không suy an toàn từ token ngẫu nhiên nếu CAS/lock không đúng.
2. T2, Tfail và recovery ở transaction riêng lấy lại guard → transfer/fence, kiểm cùng epoch + token + owner + claim còn hợp lệ trước mọi money/terminal write, giữ lock thích hợp đến commit/rollback. T2 tiếp tục account theo ID tăng → limit customer/account → business flush → audit cuối. Các cập nhật điều khiển/CAS cần hoàn tất trước audit cuối; không business SQL hoặc deferred flush sau audit lock. Worker stale, claim bị thay thế hoặc CAS 0 dòng phải dừng và rollback toàn transaction. Phải chứng minh stale worker không thể ghi qua provider và lock order không deadlock; không coi TTL/lease là mutex thay DB lock.
3. T2 savepoint rollback được chứng minh và transaction còn usable: có thể finalize FAILED trong cùng T2 theo DEC-TRF-003. Sau abort, Tfail chỉ khi rollback đã xác minh, failure/evidence đủ và fence hợp lệ; atomic FAILED + response/idempotency + reservation release + audit/outbox theo contract. Commit chưa rõ, evidence thiếu/lệch, DB lỗi hay fence không chứng minh được: đọc primary, giữ trạng thái thực tế, đưa về xử lý manual/alert theo quy trình được duyệt; không suy từ vắng ledger hoặc lease hết hạn, không tự ghi FAILED, debit lại hay post lại ledger.
4. Recovery schedule: sau khởi động và mỗi 60 giây, PENDING ≥300 giây; batch/cursor hữu hạn, tối đa 5 lần hoặc 15 phút từ lần đầu, tùy đến trước; backoff 30/60/120/240 giây + jitter (DEC-TRF-010). Attempt phải được ghi bền trước xử lý. Hết giới hạn chuyển manual/alert; rearm chỉ bởi Ops có quyền, audit và giữ epoch tăng, không sửa ledger. Việc rearm reset count/first-attempt theo thế hệ nào và lưu lịch sử trước reset là câu hỏi review, không tự chọn. Đủ evidence per-transfer theo DEC-TRF-006 mới được atomic reconciliation; thiếu proof giữ nguyên.

## 3. Thứ tự migration ứng viên

1. DBA và owner xác nhận thực tế Flyway history, provider PK/FK/delete policy và version duy nhất. Identity (`app_user`, `auth_challenge`), Customer (`customer`, sau prerequisite Identity) và Account (`account`, sau Customer) cung cấp migration của chính họ; không đoán version hoặc tạo bảng thay provider. Limit guard/reservation và Audit/Identity transaction contracts phải sẵn sàng trước integration T1/T2.
2. Sau provider FK prerequisites và review, migration Transfer forward-only tạo baseline `transfer`/`idempotency_record`, checks, FK RESTRICT, indexes và trigger theo `04` và [matrix](BE-TRF-02-schema-matrix.md). Snapshot/fence có thể đặt trong cùng migration **chỉ sau review và đồng bộ `04`**; nếu baseline đã áp dụng, thêm migration forward-only riêng. Không sửa V1 hoặc migration đã áp dụng.
3. Nếu có dữ liệu transfer trước migration bổ sung, DBA cần lập kế hoạch backfill dựa trên provenance thực; **không bịa snapshot/version hoặc bằng chứng fence**. Không thể chứng minh dữ liệu cũ thì dừng/đưa manual, không bật tự động. Kiểm clean và upgrade trên PostgreSQL 16 thật, Flyway validate, Hibernate validate, catalog, rollback/forward repair hoặc restore theo runbook và bản sao dữ liệu; test constraint, CAS/race, crash, stale worker, unknown commit, TTL change/purge và provider rollback. Fake không thay test provider/transaction thật.

## 4. Các câu hỏi để review

| Owner cần review | Câu hỏi và quyết định cần ghi |
|---|---|
| Transfer, DBA, Ops, Security | Chấp nhận lưu TTL trong record có thể purge không; `seconds`/version string, định nghĩa version, giới hạn TTL, config snapshot atomic và yêu cầu lưu evidence lâu dài là gì? |
| Transfer, DBA/Architect, Ops | Cột trong `transfer` hay bảng attempt riêng; state machine, lease duration, loại token/owner, index chọn candidate, các check, rearm và retention lịch sử? |
| Transfer, Account, Ledger, Limit, Audit, DBA | Cấu trúc và nguồn evidence để xác nhận rollback/no-money và failure; transaction/fence/CAS nào ngăn stale worker ghi qua mọi provider, bao gồm crash và commit chưa rõ? |
| Identity, Customer, Account, Transfer, DBA | Migration/PK/FK thật, quy tắc xóa, version duy nhất và thứ tự merge; dữ liệu predecessor và backfill hợp lệ? |
| Limit, Identity, Transfer, QA, Security | Guard/reservation, consume và audit transaction/lock order, time source và vector race/rollback nào được provider xác nhận? |
| DBA/Architect, BA/QA, Transfer | Đồng bộ bất nhất trong `04`: §2.7 C-04 nói record trước T1 còn §4.4.3/§7.7.1 nói cùng T1; §5.1 nói record bắt buộc khi transfer tồn tại còn §4.4.3/§9.6 cho purge terminal. Xác nhận cách sửa nguồn, không tự suy sign-off. |

Khi có quyết định của owner, ghi người quyết định/ngày/phạm vi trong `DECISIONS.md`, cập nhật `04` và `05` nếu hợp đồng giao tiếp thay đổi, rồi thực hiện migration và test. Cho đến lúc đó, đề xuất và các AC liên quan vẫn **Blocked**.
