# BE-LED-01 — Bằng chứng kiểm thử contract và candidate

Ngày 17/11/2026, chạy trên bản sao tạm của backend từ working tree `dev2@9d96c96` bằng Java 21 và Gradle 8.14.3, với dependency cache offline. Hai suite candidate chạy qua Testcontainers `postgres:16-alpine` với schema và provider fixture thuộc test.

```sh
./gradlew test --offline --console=plain --no-daemon \
  --tests 'com.simulatedbank.banking.ledger.contract.*' \
  --tests '*TransferSettlementCandidateTest' \
  --tests '*ModuleBoundaryArchTest' \
  --tests '*TransferSettlementCandidatePostgresTest' \
  --tests '*TransferRecoveryCandidatePostgresTest'
```

| Suite trong XML Gradle | Passed | Phạm vi |
|---|---:|---|
| `LedgerContractOracleTest` | 32 | TRFC-LE-01 input và cặp; TRFC-LE-02 absent, sai/lệch và lỗi đọc; DEC-LED-003 seeded/zero opening, lệch, thiếu và mốc hiệu lực. Account/Ledger ports là mock test-only. |
| `TransferSettlementCandidateTest` | 26 | Thứ tự pha và hành vi consumer với ports mock. |
| `TransferSettlementCandidatePostgresTest` | 7 | Settlement candidate trên PostgreSQL với schema/provider synthetic. |
| `TransferRecoveryCandidatePostgresTest` | 26 | Recovery candidate trên PostgreSQL với schema/provider synthetic. |
| `ModuleBoundaryArchTest` và `AuditBoundaryArchTest` | 13 | Boundary hiện có của backend. |
| **Tổng** | **104** | **0 failed, 0 errors, 0 skipped.** |

Kết quả `BUILD SUCCESSFUL`. Owner review, migration/role production, provider Ledger và Account thật, API-TXN-01/02 và D1–D5 thuộc các gate trong [backlog](../README.md). Kết quả PostgreSQL ở trên chỉ áp dụng cho Transfer candidate với fixture; các bài kiểm hệ thống phải chạy lại với provider, migration và scope thật.
