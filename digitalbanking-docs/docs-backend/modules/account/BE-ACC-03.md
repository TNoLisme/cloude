# BE-ACC-03 — Account Entity / Crypto / Blind index / Repository

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-ACC-03
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 17/11/2026
> **Trạng thái:** **COMPLETED** (phần lưu trữ/crypto/read repository) — write/provisioning vẫn **BLOCKED** theo ACC-DR-04
> **Owner:** Đạt (Account)
> **Reviewer:** Chưa sign-off — cần Security và DBA
> **Branch:** `datht-backend-rework`, không tạo/không switch branch, không commit/push
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.3.1; [[digitalbanking-docs/docs-common/07. API contracts.md]] §7.2.7–7.2.8; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §7.1; [[digitalbanking-docs/docs-common/design/04. security.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §8.4; [[BE-ACC-01]] §B/§D; [[BE-ACC-02]] §C; [DEC-ACC-001](../../../../DECISIONS.md); [DEC-ACC-003](../../../../DECISIONS.md)

---

## A. Phạm vi và trạng thái đầu vào

| Hạng mục | Trước task | Sau task |
|---|---|---|
| Account production source | Chỉ có `account.api` (BE-ACC-01); `application/domain/adapter` rỗng | `domain` (status, account number), `application` (read port + protection port), `adapter/out` (entity, repository, codec, reader) |
| Schema | `V8__create_account_table.sql` (BE-ACC-02) | Không đổi migration; entity map đúng `V8`, Hibernate `validate` pass |
| Write path | Chưa có | Vẫn **không mở**: account number generation/provisioning là ACC-DR-04 BLOCKED |
| Task status | PLANNED | **COMPLETED** (storage/crypto/read repository), write side BLOCKED |

Không sửa `V1`–`V8`, không thêm cột/index, không sửa entity/module Customer, Identity, Common.

---

## B. Quyết định triển khai (DEC-ACC-003)

| # | Quyết định | Căn cứ | Hệ quả |
|---|---|---|---|
| 1 | `AccountEntity` map đúng 12 cột `V8`; enum `@Enumerated(STRING)`; `CHAR(n)` dùng `@JdbcTypeCode(SqlTypes.CHAR)`; `NUMERIC(19,2)` → `BigDecimal`; `TIMESTAMPTZ` → `Instant`; **không** `@Version` | `04` §4.3.1; BE-ACC-02 §C/§F; ACC-DR-07 | Hibernate `ddl-auto=validate` pass trên Flyway schema; không optimistic locking |
| 2 | Chỉ dùng Common crypto: `PiiCryptoService` (AES-256-GCM, IV 12B mỗi lần) + `HmacService` (HMAC-SHA256); không framework thứ hai | BE-DAT-06/07; `CryptoConfig` tách AES/HMAC key, từ chối placeholder ngoài profile dev/test | Không có khóa trong source; lỗi crypto fail closed |
| 3 | Blind index = HMAC-SHA256 lowercase hex của **đúng** chuỗi chữ số đã normalize; không dùng SHA-256 unkeyed | `ck_account_no_hash_hex`; OpenAPI `DestinationAccountNumber`; design/05 §7.1 | Deterministic lookup qua `uq_account_no_hash`; test chứng minh khác SHA-256 |
| 4 | Normalize **strict** `^[0-9]{6,20}$`: không trim, không bỏ số 0 đầu, không nhận Unicode digit | OpenAPI; BE-ACC-01 §C | Hai biểu diễn khác nhau không thể trỏ cùng account; input sai → `VALIDATION_ERROR` |
| 5 | Repository chỉ có 3 truy vấn đọc: theo ID, theo blind index, theo customer (+ optional status), thứ tự `account_id ASC` | `design/05` §7.1; `V8` index | Không có truy vấn toàn bảng/filter tự do; không bypass ownership |
| 6 | **Không** mở write/provisioning operation dù entity có constructor nội bộ | ACC-DR-04 BLOCKED | Không có API tạo tài khoản, không sinh số, không seed balance |
| 7 | `ProtectedAccountNumber(encrypted, blindIndex, last4)` sinh trong một lần, defensive copy, `toString` redacted | Chống lệch hash/last4 và rò plaintext | Persistence luôn nhận cặp giá trị nhất quán |

### B.1. Vẫn BLOCKED, không suy diễn

| Decision | Nội dung | Ảnh hưởng task này |
|---|---|---|
| ACC-DR-04 | Thuật toán sinh số tài khoản/prefix/checksum + provisioning idempotency | Không có `AccountProvisioningService`/API; generator chưa tồn tại |
| ACC-DR-06 | Opening balance provenance | Không seed/không cột opening |
| ACC-DR-12 | Status transition + C2 audit | Entity không có method đổi status |
| ACC-DR-15 | Durable audit | Không liên quan persistence account |

---

## C. Entity và mapping

**File:** `digitalbanking-backend/src/main/java/com/simulatedbank/banking/account/adapter/out/AccountEntity.java`

| Field Java | Column | Kiểu DB | Ghi chú |
|---|---|---|---|
| `accountId` (Long, `IDENTITY`) | `account_id` | `BIGINT` PK | ra HTTP là decimal string (BE-ACC-05) |
| `customerId` (Long, `updatable=false`) | `customer_id` | `BIGINT` FK | không cascade; không expose |
| `accountNumberEncrypted` (`byte[]`, `updatable=false`) | `account_no_enc` | `BYTEA` | AES-256-GCM blob; `getter` clone |
| `accountNumberHash` (`String`, `updatable=false`) | `account_no_hash` | `CHAR(64)` | HMAC-SHA256 hex; unique |
| `accountNumberLast4` (`String`, `updatable=false`) | `account_no_last4` | `CHAR(4)` | mask `**** **** <last4>` |
| `currency` (`String`) | `currency` | `CHAR(3)` | default/CHECK `VND` |
| `status` (`AccountStatus`, STRING) | `status` | `VARCHAR(16)` | `ACTIVE|LOCKED|CLOSED` |
| `availableBalance` (`BigDecimal`) | `available_balance` | `NUMERIC(19,2)` | CHECK `>= 0`, `<= ledger` |
| `ledgerBalance` (`BigDecimal`) | `ledger_balance` | `NUMERIC(19,2)` | CHECK `>= 0` |
| `closedAt` (`Instant`) | `closed_at` | `TIMESTAMPTZ NULL` | bắt buộc khi `CLOSED` |
| `createdAt`/`updatedAt` (`Instant`) | `created_at`/`updated_at` | `TIMESTAMPTZ` | `@CreationTimestamp`/`@UpdateTimestamp` |

`toString()` trả `AccountEntity[redacted]`; getter ciphertext trả bản copy. Entity không được trả qua `account.api`/REST (ArchUnit).

**Domain:** `domain/AccountStatus.java` (3 giá trị canonical) và `domain/AccountNumber.java` (validate strict, `last4()`, `toString` redacted). `account.domain` không chứa framework, không import module khác.

---

## D. Crypto và blind index

**Port:** `application/AccountNumberProtection.java` — `protect`, `blindIndex`, `decrypt`, `mask`.

**Adapter:** `adapter/out/CommonAccountNumberProtectionAdapter.java` — ủy quyền duy nhất cho `PiiCryptoService`/`HmacService`/`PiiMaskingUtils`.

| Bất biến | Cách thực hiện | Test |
|---|---|---|
| Plaintext không lưu DB | `account_no_enc` chỉ chứa `IV‖CT‖Tag`; test quét bytea không chứa chuỗi số | `AccountPersistencePostgreSqlIntegrationTest#persistsAesGcmCiphertextHmacBlindIndexAndApprovedDefaults` |
| IV mới mỗi lần | `PiiCryptoService.encrypt` sinh IV 12B bằng `SecureRandom` | `usesFreshIvPerEncryptionSoEqualNumbersProduceDifferentCiphertext` |
| Blind index keyed + deterministic | `HmacService.hmacSha256Hex(digits)` | `blindIndexIsDeterministicKeyedHmacAndNeverUnkeyedSha256` |
| Không rò khi lỗi | ciphertext hỏng/tamper → `IllegalArgumentException` message generic; service map `INTERNAL_ERROR` | `corruptCiphertextFailsClosedWithoutEchoingData`, `AccountQueryServiceTest#blindIndexFailureFailsClosedAsInternalError` |
| Khóa ngoài source | `CryptoConfig` đọc `app.crypto.*` từ env; tách AES/HMAC; rotation chưa hỗ trợ | `CryptoConfigTest` (Common, đã có) |
| Masking đúng OpenAPI | `PiiMaskingUtils.maskAccountLast4` → `**** **** <last4>` | `masksWithTheApprovedWireFormatAndRejectsInvalidLast4` |

`decrypt` **không** được dùng bởi read contract: `AccountQuery` không trả plaintext, không decrypt để hiển thị. Operation tồn tại cho verification round-trip và write path tương lai khi ACC-DR-04 được duyệt.

---

## E. Repository

**Files:** `adapter/out/AccountJpaRepository.java` (package-private), `adapter/out/JpaAccountReader.java`, `application/AccountReadPort.java`.

| Operation | Query | Access path |
|---|---|---|
| `findById` | `findById(accountId)` | `account_pkey` |
| `findByAccountNumberHash` | `findByAccountNumberHash(hash)` | `uq_account_no_hash` (unique) |
| `findByCustomerId(customerId, null)` | `findByCustomerIdOrderByAccountIdAsc` | `ix_account_customer` + sort PK |
| `findByCustomerId(customerId, status)` | `findByCustomerIdAndStatusOrderByAccountIdAsc` | `ix_account_customer` (lọc status sau) |

- Không có `findAll`, không JPQL tự do, không update/delete hàng loạt, không truy vấn theo `last4`.
- `AccountRecord` là projection không chứa ciphertext/blind index; chỉ `last4` để mask.
- Duplicate blind index: PostgreSQL `23505` → `DataIntegrityViolationException` (test). Không có write path nên chưa có business mapping conflict; khi provisioning được duyệt phải định nghĩa retry/conflict policy.
- Lỗi persistence kỹ thuật lan truyền (fail closed), không đổi thành empty.

---

## F. Kiểm thử và kết quả

```bash
cd digitalbanking-backend
./gradlew test --tests 'com.simulatedbank.banking.account.adapter.out.*' \
  --tests 'com.simulatedbank.banking.account.application.*' --console=plain
```

| Suite | Kết quả |
|---|---|
| `CommonAccountNumberProtectionAdapterTest` | **7/7 PASS** — round-trip, IV, HMAC-keyed, normalize strict, defensive copy, tamper, mask |
| `AccountPersistencePostgreSqlIntegrationTest` (PostgreSQL 16 Testcontainers) | **5/5 PASS** — mapping/defaults/timestamps, bytea không chứa plaintext, decrypt round-trip, query theo 3 access path, duplicate unique, FK, CHECK lifecycle/balance, `hibernate validate` + Flyway `V1`–`V8` |
| `AccountApiContractTest` (BE-ACC-01) | **6/6 PASS** (regression) |
| `ModuleBoundaryArchTest` | **18/18 PASS** (regression; account rules không bị nới) |

Full `./gradlew build --max-workers=1` **BUILD SUCCESSFUL** (6m30s): **93 classes / 1110 tests / 0 failure / 0 error / 0 skip**; BE-ACC-03 đóng góp 2 class/12 test mới (2 class còn lại của task BE-ACC-04; phần tăng khác đến từ working tree của module khác). Không dùng H2/mock persistence. Không có test nào pass khi chưa chạy.

---

## G. Việc còn lại (ngoài BE-ACC-03)

| Việc | Task | Trạng thái |
|---|---|---|
| Row lock `FOR UPDATE` + conditional debit/credit | BE-ACC-06/07 | Chưa làm (read repository không chứa lock/money) |
| Provisioning + sinh số tài khoản/idempotency (ACC-DR-04) | BA/Security/Product | **BLOCKED** |
| Opening balance provenance (ACC-DR-06) | Ledger/DBA | **BLOCKED** |
| REST adapter + security chain | BE-ACC-05 | Chưa làm |

---

## H. Change log

| Version | Ngày | Nội dung |
|---|---|---|
| v1.0 | 17/11/2026 | Entity/crypto/blind-index/read repository, unit + PostgreSQL 16 tests, [DEC-ACC-003](../../../../DECISIONS.md) |
