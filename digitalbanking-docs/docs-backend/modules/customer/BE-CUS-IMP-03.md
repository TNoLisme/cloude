# BE-CUS-IMP-03 — AES-GCM encrypted PII và masking dùng Common

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework`
> **Trạng thái:** DONE — full backend regression 824/824 PASS; focused unit/PostgreSQL/Common PASS.
> **Quyết định phạm vi:** [DEC-CUS-002](../../../../DECISIONS.md).

## A. Phạm vi triển khai

- Thêm application boundary `CustomerPiiService`, `CustomerProfileStore`, `CustomerPiiProtection` và owner-safe projection `OwnerCustomerPiiView`.
- `CommonCustomerPiiProtectionAdapter` delegate trực tiếp sang Common `PiiCryptoService` và `PiiMaskingUtils`; Customer không tự triển khai cipher, random, ciphertext format hoặc masking rule thứ hai.
- `JpaCustomerProfileStore` là persistence adapter chỉ nhận/trả ciphertext. `CustomerEntity` clone mọi `byte[]` tại boundary và `toString()` tiếp tục redacted.
- Luồng nội bộ được triển khai và test:
  - create: validate display boundary → encrypt đủ PII → `saveAndFlush`;
  - owner read: kiểm `actorUserId == user_id` trước decrypt → full name cho owner, phone/email đã mask;
  - update từng field: xác thực/decrypt record hiện tại trước mutation, encrypt replacement bằng fresh IV, giữ nguyên ciphertext field không đổi;
  - crypto/masking failure: `INTERNAL_ERROR`/`VALIDATION_ERROR` an toàn, không cause/provider detail/plaintext và không fallback plaintext.
- Không mở REST endpoint, public `customer.api`, provisioning/idempotency/status workflow hoặc staff view. Các phần đó vẫn thuộc IMP-04/05/06/09.

## B. Ma trận bảo vệ PII

| Field thực tế | Storage protection | API/application exposure | Masking | Authorized access |
|---|---|---|---|---|
| `full_name_enc` | AES-256-GCM, Common blob `IV 12B || ciphertext || tag 16B`, PostgreSQL `BYTEA NOT NULL` | Owner projection được phép trả full name; không có REST endpoint ở IMP-03 | Owner: không mask theo `07 §2.6`; staff: chưa tạo projection vì policy chi tiết chưa duyệt | Chỉ owner khi `actorUserId == user_id`; create nội bộ |
| `phone_enc` | AES-256-GCM fresh IV, `BYTEA NULL` | Không trả raw; chỉ `phoneMasked` | Common `maskPhone`: `********` + last3 | Owner projection sau ownership check |
| `email_enc` | AES-256-GCM fresh IV, `BYTEA NULL` | Không trả raw; chỉ `emailMasked` | Common `maskEmail`: first char + `***@domain` | Owner projection sau ownership check |

Schema chuẩn không có national ID/passport, DOB, address, CIF hoặc KYC; IMP-03 không tạo field/migration/blind index cho dữ liệu không tồn tại.

## C. Common integration và cấu hình

| Thành phần Common | Cách dùng trong Customer |
|---|---|
| `PiiCryptoService` | AES/GCM/NoPadding, key 32 byte, SecureRandom IV 12 byte, tag 128 bit, one-shot authenticated decrypt |
| `CryptoConfig` + `AppProperties.Crypto` | Đọc `APP_PII_AES_KEY_BASE64`; thiếu/sai/trùng key hoặc rotation chưa hỗ trợ fail-fast khi startup |
| `PiiMaskingUtils.maskPhone` | Validate display boundary và tạo masked phone; invalid input không echo/fallback |
| `PiiMaskingUtils.maskEmail` | Validate display boundary và tạo masked email; invalid input không echo/fallback |
| `BankingBusinessException`/`ErrorCode` | `VALIDATION_ERROR`, `CUSTOMER_NOT_FOUND`, `AUTH_FORBIDDEN`, `INTERNAL_ERROR` với public detail cố định |

Test dùng key synthetic của profile `test`; không thêm secret triển khai. `app.customer.enabled=false` chỉ được đặt trong các Spring context test cố ý loại DataSource/JPA để module business không tạo persistence bean giả.

## D. Database compatibility

- Không có migration mới. V5 đã cung cấp `full_name_enc`, `phone_enc`, `email_enc` kiểu `BYTEA`, không giới hạn chiều dài ciphertext.
- Randomized AES-GCM không dùng cho equality lookup/unique. Customer chỉ lookup bằng `customer_id`/`user_id`; không cần blind index.
- Không sửa V5, constraint, index hoặc dữ liệu legacy. Chưa có production backfill được thực hiện hoặc tuyên bố.
- Update field giữ nguyên byte ciphertext của field không đổi; replacement cùng plaintext vẫn tạo ciphertext khác do fresh IV.

## E. Failure handling và chống rò rỉ

- Ownership được kiểm trước lần decrypt đầu tiên; actor sai nhận `AUTH_FORBIDDEN` ngay cả khi ciphertext đã bị tamper.
- Tag/ciphertext sai, truncated/corrupt blob hoặc masking lỗi trên dữ liệu đã lưu được map thành `INTERNAL_ERROR`; không trả provider detail, ciphertext hay plaintext.
- Encryption/masking validation hoàn tất trước `store.create`; lỗi giữa các lần encrypt không gọi persistence.
- Update decrypt/authenticate toàn bộ record cần trả trước mutation. Tampered field làm transaction fail và ciphertext hiện hữu không bị đổi.
- `OwnerCustomerPiiView`, `StoredCustomer`, `CustomerEntity` và Common adapter có `toString()` redacted. View chỉ chứa raw full name được contract cho owner; phone/email chỉ tồn tại dạng masked.
- Missing/invalid key, unsupported `previous-keys` và placeholder key ngoài local/test tiếp tục fail-fast qua Common `CryptoConfig`; Customer không catch rồi chạy plaintext.

## F. Automated tests và evidence hiện tại

| Test | Coverage | Kết quả |
|---|---|---|
| `CustomerPiiServiceTest` | roundtrip qua Common adapter, fresh IV, nullable/invalid input, masking, unauthorized-before-decrypt, tamper, update preserve/fresh IV, encryption failure không gọi store | 6/6 PASS |
| `CustomerPiiPostgreSqlIntegrationTest` | ciphertext-at-rest, owner-safe serialization, per-field fresh IV, unauthorized/tamper, update preservation, rollback/no partial row trên failure | 6/6 PASS trên PostgreSQL 16 Testcontainers |
| `CustomerJpaPostgreSqlIntegrationTest` + `CryptoOperationsTest` | IMP-02 DB boundary và Common DB-less operational context sau integration | 7/7 PASS |
| Full backend regression | Toàn bộ backend sau integration IMP-03 | `./gradlew build --max-workers=1 --console=plain` PASS: 61 classes / 824 tests / 0 failure / 0 error / 0 skipped |

Lệnh đã chạy từ `digitalbanking-backend`:

```bash
./gradlew test --tests 'com.simulatedbank.banking.customer.application.CustomerPiiServiceTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerPiiPostgreSqlIntegrationTest' --console=plain
./gradlew test --tests 'com.simulatedbank.banking.common.crypto.CryptoOperationsTest' --tests 'com.simulatedbank.banking.customer.adapter.out.CustomerJpaPostgreSqlIntegrationTest' --console=plain
```

Một lần regression Customer + Common đầu phát hiện hai integration issue: tamper fixture dùng `JdbcTemplate` ngoài transaction với Hikari `auto-commit=false`, và DB-less Common context cố tạo Customer JPA bean. Fixture được commit bằng `TransactionTemplate`; Customer bean dùng enable condition giống Identity (`app.customer.enabled`) và DB-less tests disable module. Test JPA IMP-02 cũng giữ DB là final NOT NULL boundary thay vì constructor thay đổi exception contract. Các focused rerun tương ứng đã PASS.

Lần chạy full đầu (`--rerun-tasks`) có 1 test Identity `OtpChallengePostgreSqlIntegrationTest.t20` fail `OutOfMemoryError` trong Argon2 dưới heap pressure của suite lớn; test class chạy isolated PASS 22/22 và full build rerun với `--max-workers=1` PASS 824/824, chứng minh flake tài nguyên môi trường, không phải code Customer/Common. Không test nào bị disable/weaken.

## G. Dependency và giới hạn còn mở

- Common chỉ hỗ trợ một active AES key; ciphertext chưa chứa key ID và rotation/KMS/backfill protocol chưa được duyệt. `previous-keys` khác rỗng phải fail-fast.
- Staff full-name mask (`Nguyễn V*** A`) chưa có oracle/thuật toán được duyệt; IMP-03 không tạo staff mapper có nguy cơ lộ full name. Owner: Customer/API/BA/Security tại CUS-OI-02.
- IMP-05 phải lấy `actorUserId` từ principal/session tin cậy; không nhận actor từ request. IMP-06 cần Admin scope + Audit integration trước staff read.
- Không có legacy plaintext backfill. Nếu production đã có row plaintext ngoài migration/test evidence hiện tại, cần migration/backfill protocol riêng trước rollout; không được decrypt-fallback plaintext.

## Change Log

| Ngày | Mô tả |
|---|---|
| 17/11/2026 | Thêm Common crypto/masking adapter, encrypted persistence service, owner-safe projection, unit/PostgreSQL tests và DEC-CUS-002. Full backend 824/824 PASS → DONE. |
