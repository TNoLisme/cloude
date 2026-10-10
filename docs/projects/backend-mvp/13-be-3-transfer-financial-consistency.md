# BE-3 — Transfer Failure, Concurrency, Idempotency

**Người phụ trách:** BE-3  
**Reviewer:** BE-A (Auth/Account boundary), BE-B (Evidence & Integration)  
**Tiến độ chung:** Cập nhật tại [progress.md](../../progress.md)  
**Kỹ thuật làm chủ để bảo vệ đồ án:**  
1. `Idempotency-Key Pattern`: Khóa chống lặp giao dịch (bằng hash payload & redis/db key), mất mạng gửi lại cùng key trả kết quả cũ, không trừ tiền 2 lần.
2. `Strong Transactional Consistency (ACID Rollback)`: Quản lý giao dịch trừ tiền/cộng tiền trong 1 boundary an toàn, giả lập đứt gánh tự rollback 100%.
3. `Deadlock Prevention (Sorted Resource Locking)`: Khóa tài khoản theo thứ tự sắp xếp `min(id), max(id)` khi 2 người chuyển tiền chéo nhau đồng thời.
4. `Non-negative Balance Constraint`: Ngăn chặn rút âm tuyệt đối khi 2 lệnh rút tiền đồng thời vượt quá số dư.

---

## 1. Vai trò

BE-3 own `transfer/**`. Bài toán khó nhất team: rollback, timeout retry, overdraft, same-key race, opposite lock, duplicate confirm, block-vs-confirm. Chỉ dùng API boundary module khác, không sửa impl họ.

## 2. Phạm vi

### Được sửa

```text
be/src/main/java/com/bank/simulator/transfer/**
be/src/test/java/com/bank/simulator/transfer/**
be/src/test/java/com/bank/simulator/TransferUiApiRegressionPostgresTest.java (tạo mới, BE-3 own)
docs/projects/backend-mvp/evidence/BE3-*.md
```

### Không được sửa

- `identity/**`, `account/**`, `audit/**`, `risk/**`. Cần đổi thì proposal.
- `frontend/**`, `tests/load/**`, `.github/**`.
- Migration cũ, OpenAPI (chỉ proposal).

Không refactor package/architecture cho giống docs. Giữ impl, chốt boundary đủ test.

## 3. Đầu vào

1. `transfer/**`: create/confirm, lockPair, debit/credit, idempotency key + hash, OTP consume, state machine.
2. Lock API BE-2 publishes as `BE2-lock-api.md`. Contract is an input dependency; BE-3 can implement tests against signature before BE-2 finishes implementation. If signature changes, stop affected integration test and agree versioned change with BE-2.
3. PIN/OTP boundary BE-1. Disposable PostgreSQL support BE-4.
4. `docs/.../05-transfers.md` + OpenAPI transfer codes.

## 4. Dependencies and contracts

| Work item | Depends on | Required handoff | If dependency missing |
|---|---|---|---|
| Transfer unit tests and local implementation | Frozen OpenAPI + current transfer API | Existing DTO/error/state contract | Continue unit tests; record mismatch, do not change contract unapproved |
| PostgreSQL concurrency tests | BE-4 `DisposablePostgresTestSupport` | isolated PG fixture/reset/lifecycle API | Keep unit tests moving; mark PG proof blocked, do not create private container |
| Account locking and block race | BE-2 account lock/status contract | method signature, lock order `id ASC`, eligible-state definition | BE-3 may author tests against agreed contract draft; do not patch `account/**` |
| OTP confirm race | BE-1 OTP consume contract | consume-once result and transaction boundary | Test transfer state behavior with contract fixture; report mismatch to BE-1 |
| FE handoff / unknown outcome | Frozen OpenAPI + final error/state matrix | stable codes, key retention and reconcile endpoint | FE/BE may run contract gate; full E2E waits for handoff |

BE-2 owns seed concurrency; BE-3 owns transfer concurrency. `UiApiRegressionPostgresTest.java` is legacy and frozen. BE-2 and BE-3 use separate regression test classes.

## 5. Quy trình thực hiện

### Bước 0 — Vẽ state + boundary (Tiên quyết — Chưa code)

- Vẽ: `AWAITING_OTP → COMPLETED / EXPIRED / FAILED`. Ghi chuyển nào cho phép, terminal không quay lui.
- Ghi: PIN check trước money tx, large create không debit/reserve, confirm lock trong tx, risk sau commit, không network call trong money tx.
- Ghi MISMATCH nếu code khác docs/OpenAPI, báo BE-4. Output vào `BE3-rollback-timeout.md` mục hiện trạng.

### Bước 1 — Rollback + timeout (Làm ngay, độc lập)

1. Tạo test-only injector:
```text
TransferFailureInjector.afterDebitBeforeCredit()
prod = no-op, test profile = throw
Không if(testMode) trong business code
Không bật ngoài test profile
```
2. Test rollback: disposable PG → seed source → transfer → debit chạy → injector throw trước credit → rollback.
Assert: source/dest unchanged, không COMPLETED, không idempotency success, không audit commit, không risk flag từ rollback.
3. Test timeout-after-commit: commit → delay/suppress response → retry đúng body + K1 → original result, debit/credit đúng 1 lần. Không tạo K2 tự động.
4. Output `BE3-rollback-timeout.md` + log.

### Bước 2 — Concurrency tiền (Trọng tâm P0 — Sau khi setup PG)

Dùng CountDownLatch + ExecutorService, Future timeout, shutdown finally. Không sleep sync, không retry vô hạn. Bắt buộc PostgreSQL/Testcontainers qua `DisposablePostgresTestSupport.java` do BE-4 own; mock/H2 không chứng minh row lock.

| ID | Setup | Kỳ vọng |
|---|---|---|
| CON-01 | Source 1M, 2×600k concurrent | 1 commit 409 còn lại, final source 400k, dest +600k, không âm |
| CON-02 | N request cùng actor/key/payload | 1 transfer, 1 debit/credit, replay trả original; khác payload → 409 |
| CON-03 | A→B + B→A concurrent | cùng lock API order id ASC, bounded timeout, không deadlock lâu dài |

Mỗi case: seed known balance → latch start đồng thời → join timeout → assert balance + row counts + state. Ghi balance trước/sau + counts vào `BE3-concurrency.md`.

Lệnh:
```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*TransferServiceTest,*TransferServiceLifecycleTest,*TransferControllerMvcTest,*Recipient*Test" test
mvn -q -Dtest="*Transfer*PostgresTest,TransferUiApiRegressionPostgresTest" test
```

### Bước 3 — OTP/block race (Phối hợp BE-2)

- Duplicate confirm: 1 COMPLETED, 1 debit/credit, OTP consume 1 lần, call sau replay/conflict đúng contract.
- Block-vs-confirm (làm cùng BE-2): tạo AWAITING_OTP → race block vs confirm → confirm lock lại + revalidate dưới lock → ineligible FAILED đúng code, balance unchanged, OTP invalidate, terminal không ghi đè.
- Review boundary checklist mục Bước 0, tick từng dòng pass/fail.
- Output `BE3-otp-race.md` + FE handoff status/error matrix.

### Bước 4 — 5M boundary + terminal guard (Hoàn thiện ma trận lỗi)

- 5M đúng → small path, 5M+1 → OTP path. Sai path là fail.
- Terminal COMPLETED/FAILED/EXPIRED không chuyển ngược. Test cố ghi đè phải fail.
- Ghi error matrix gửi FE/BE: insufficient/eligibility/currency/Idempotency-Replayed/timeout=unknown/detail reconcile/không cancel-resend.

## 6. Lệnh nghiệm thu

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*TransferServiceTest,*TransferServiceLifecycleTest,*TransferControllerMvcTest,*Recipient*Test" test
mvn -q -Dtest="*Transfer*PostgresTest,TransferUiApiRegressionPostgresTest" test
cd ..
git diff --check
```

Testcontainers fail → ghi exact error, phân loại env/tool/code, không thay shared PG, không ghi pass.

## 7. Tiêu chí đạt

- [ ] Rollback pass: balances unchanged, không tác dụng phụ.
- [ ] Timeout retry 1 mutation, trả original.
- [ ] Overdraft không âm, đúng 409.
- [ ] Same-key 1 resource, replay đúng.
- [ ] Opposite không deadlock lâu dài.
- [ ] Duplicate confirm 1 debit.
- [ ] Block-vs-confirm đúng state.
- [ ] Terminal không quay lui.
- [ ] Dùng DisposablePostgresTestSupport của BE-4, không container riêng.

## 8. Nghiệm thu

- BE-2 check lock/order dùng đúng API chốt.
- BE-1 check PIN/OTP boundary không vi phạm.
- BE-4 chạy lại lệnh mục 6, so counts.
- Fail nếu: H2/mock cho lock test, sleep-sync flaky, injector lọt prod, terminal ghi đè.

## 9. Bàn giao

Tạo `evidence/BE3-rollback-timeout.md`, `BE3-concurrency.md`, `BE3-otp-race.md`.
Cho BE-1: PIN/OTP boundary thực tế, wrong-attempt commit.
Cho BE-2: lock/order validation, snapshot, block race setup, lock metric.
Cho BE-4: injector design, PG output, invariant summary, flaky note.
Cho FE/BE: state machine, 5M boundary, header/body/key/retry, timeout unknown, OTP attempt/expiry, error matrix, mailbox + dataset. Không optimistic, không cancel/resend.

## 10. Cấm

Không sửa module khác. Không k6/CI/FE. Không `down -v`/`DROP`/`TRUNCATE`. 1 PR 1 scenario.
