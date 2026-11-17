# BE-LED-02 — Evidence PostgreSQL cho Ledger candidate

Chạy ngày 17/11/2026 từ bản sao tạm của backend ở working tree `dev2@9d96c96`, gồm migration, fixture và test BE-LED-02 cùng các thay đổi candidate đã có. Dùng Java 21.0.1, Gradle 8.14.3, Flyway 11.7.2 và PostgreSQL 16.15 qua Testcontainers (`postgres:16-alpine`).

```sh
./gradlew test --offline --console=plain --no-daemon \
  --tests 'com.simulatedbank.banking.ledger.*' \
  --tests '*ModuleBoundaryArchTest' \
  --tests '*TransferSettlementCandidateTest' \
  --tests '*TransferSettlementCandidatePostgresTest' \
  --tests '*TransferRecoveryCandidatePostgresTest'
```

## Chain và role

| Location Flyway | Migration | Checksum |
|---|---|---:|
| `classpath:db/ledger-fixture` | `V1__synthetic_ledger_parent_keys.sql` | `1745668395` |
| `classpath:db/ledger-candidate` | `V2__create_ledger_entry.sql` | `-729544012` |

Harness chạy `docker/postgres/init/01-create-roles.sh`. `banking_migrator` sở hữu Ledger và function trigger. Kết nối runtime ghi nhận `current_user:session_user = banking_app:banking_app` với ACL `SELECT:false`, `INSERT:false`, trong đó `false` là không có grant option. Kết nối `ledger_trigger_probe` có quyền DML thử nghiệm và bị từ chối DDL. V1 chỉ tạo PK Account/Transfer theo contract với dữ liệu tổng hợp. Test này dùng chính SQL Ledger candidate và giữ hai FK đã validate.

## Kết quả từng oracle

| ID | Kết quả candidate | Evidence |
|---|---|---|
| LED-DB-01 | PASS | Catalog kiểm cột, kiểu, nullability, default, identity, owner, PK/FK/CHECK/UNIQUE, ba index và trigger enabled. |
| LED-DB-02 | PASS | Runtime commit và đọc lại cặp 0,01 VND qua kết nối mới. Hai ID dương khác nhau, balance 0 và default currency/time đúng. |
| LED-DB-03 | PASS | Giá trị NULL tường minh và input sai bị từ chối với SQLSTATE `23502`/`23514`. CHECK tiền cũng từ chối PostgreSQL `NaN`. |
| LED-DB-04 | PASS | Giữ chính xác `99999999999999999.99` và correlation 64 ký tự. Overflow và chiều dài 65 trả `22003`/`22001`. PostgreSQL ép `1.235` thành `1.24` và `2.345` thành `2.35`; BE-LED-03 kiểm scale trước ghi. |
| LED-DB-05 | PASS | Orphan và DELETE/UPDATE PK parent đã có entry trả `23503` với đúng tên FK. |
| LED-DB-06 | PASS | Duplicate cùng transaction và waiter sau khi winner commit trả `23505`. Khi winner rollback, waiter ghi thành công. Test xác nhận waiter bị đúng backend winner chặn. |
| LED-DB-07 | PASS | Lỗi seq/type ở entry thứ hai và rollback tường minh sau một hoặc hai insert đều để lại 0 dòng. Entry đã commit trước đó còn nguyên. |
| LED-DB-08 | PASS | Runtime nhận `42501` cho mutation, DDL, thay/disable trigger, đổi role và thao tác trực tiếp trên identity sequence. |
| LED-DB-09 | PASS | Role có DML nhận `55000` với message `ledger_entry is append-only` cho UPDATE, DELETE và TRUNCATE. TRUNCATE bị chặn cả trên bảng rỗng. |
| LED-DB-10 | PASS trên chain fixture | Sau bootstrap, migration và lần chạy lại, quyền runtime vẫn giới hạn. Inherited UPDATE được tiêm qua default grant khiến migration trả `42501` và rollback cả DDL lẫn bản ghi history V2. Migration chạy được sau khi thu hồi default grant thử nghiệm. |
| LED-DB-11 | PASS trên chain fixture, Blocked trên production | Fresh áp dụng V1/V2 và validate. Upgrade áp dụng V2 trên dữ liệu parent đã có và giữ nguyên khóa. Validate đạt và restart áp dụng 0 migration. Production chain và Hibernate mapping vẫn chờ. |
| LED-DB-12 | PASS | Fixture commit được một seq hoặc một cặp amount lệch. Kiểm đầy đủ cặp trước audit và rollback T2 thuộc BE-LED-03–04. |

Với 4.000 bút toán tổng hợp trong transaction rollback, `EXPLAIN (COSTS OFF)` chọn `ix_ledger_account_time` cho account/time, `uq_ledger_transfer_seq` cho transfer/seq, và `ix_ledger_correlation` cho correlation. Catalog xác nhận `ix_ledger_transfer` tồn tại theo baseline.

## Tổng hợp Gradle

| Suite | Passed |
|---|---:|
| `LedgerCandidateMigrationPostgresTest` | 14 |
| `LedgerContractOracleTest` | 32 |
| `ModuleBoundaryArchTest` và `AuditBoundaryArchTest` | 13 |
| `TransferSettlementCandidateTest` | 26 |
| `TransferSettlementCandidatePostgresTest` | 7 |
| `TransferRecoveryCandidatePostgresTest` | 26 |
| **Tổng** | **118** |

Gradle trả `BUILD SUCCESSFUL`. XML có 0 failures, 0 errors và 0 skipped. Hai suite PostgreSQL Transfer dùng fixture/provider synthetic của Transfer.

DB, Account và Transfer còn cần chốt chain sau khi kiểm history production và xử lý hai migration V2 trùng version. Hibernate validate chờ mapping BE-LED-03. DB/Security/QA/Ops review và bằng chứng tích hợp T2 với provider thật vẫn chờ theo [điều kiện bàn giao](../BE-LED-02.md#9-evidence-và-bàn-giao).
