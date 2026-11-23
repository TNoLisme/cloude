# BE-INT-04 — Kiểm chứng provider Limit và hợp đồng Transfer

| Trường | Giá trị |
|---|---|
| Trạng thái | **Verified** cho BE-INT-04 |
| Ngày | 19/11/2026, Asia/Ho_Chi_Minh |
| Phạm vi | API-LIM-01, Limit provider LI-01/02/04/05/06/07, adapter Transfer, Admin mutation/HTTP và migration V20. |
| Yêu cầu | FR-016/031–033, AC-009/013/017/028/033/034, LI-INT-01…06, DEC-INT-011. |
| Môi trường | Profile `test`, OpenJDK 21, Gradle 8.14.3, PostgreSQL 16.15 Testcontainers, múi giờ nghiệp vụ `Asia/Ho_Chi_Minh`. Kiểm role `banking_app` tách khỏi migrator. Spring Boot dùng Hibernate validate. |
| Flyway | V20 checksum `1968807172`. V1–V19 giữ nguyên. |
| Source revision | `dev2@ba3441cc035ed34c5153e28c10b7e86cb7011971` và [manifest workspace](evidence/BE-INT-04/source-manifest.sha256). |
| Artifact | [Log Gradle, JUnit XML và tổng hợp test](evidence/BE-INT-04/results.tar.gz). |

## Kỳ vọng và kết quả

Hồi quy chính chạy **314/314 PASS**. Lượt recovery chạy **47/47 PASS**, gồm 26 ca candidate recovery và 21 ca ArchUnit đã chạy trong hồi quy chính. Tổng **340 ca riêng biệt PASS**, 0 fail, 0 error, 0 skip. Hai lệnh đều thoát với mã 0.

| Vector | Kỳ vọng | Kết quả PASS |
|---|---|---|
| LI-INT-01 · Read | Scope theo session và ownership, no-write | HTTP qua security chain với session Identity, Customer và Account trên PostgreSQL. Hai scope đúng owner, foreign account/customer 403, thiếu config trả items rỗng, remaining về 0, lỗi SELECT Account trả 5xx. Đối chiếu count usage trước/sau. API nội bộ trả đúng config version theo scope. |
| LI-INT-02 · Guard | Giữ đúng biên 10M/20M, tuần tự hóa customer | Hai connection tranh guard cùng customer, khác account/key. Dùng `pg_blocking_pids` để xác nhận chờ lock. Tổng bằng 20M không buộc challenge, cao hơn 0,01 buộc challenge, lệnh đúng 10M buộc challenge. Reservation hôm trước còn được tính và customer khác tiếp tục trong lúc chờ. Timeout 55P03 rollback probe và guard dùng lại được. Candidate nhánh challenge tạo 0 business rows. Lỗi C1, trùng key và FK lệch facts kiểm rollback. |
| LI-INT-03 · Ngày và version | Một timestamp cho ngày logic, snapshot nhất quán | Clock fixture chuyển từ ngay trước sang đúng nửa đêm giữa check và settlement. Hai giao dịch có effect/usage ở hai ngày tương ứng. Clock lùi từ chối trước khi tạo usage. Admin approve chờ T2 giữ guard; effect giữ version cũ và snapshot sau commit thấy version mới. Kiểm ranh giới cutover và ACCOUNT lần đầu. |
| LI-INT-04 · Settlement | Cộng amount/count đúng một lần và giữ counter khi fail | Bằng daily cap được phép, vượt 0,01 ở account thứ hai của cùng customer bị chặn. Hoàn tất tạo một effect mỗi scope áp dụng. Gọi lại và ref khác transaction bị từ chối. Release sau từ chối hoặc trước khi lock usage không tăng counter. Lỗi SQL downstream rollback cả effect/counter/state; retry hợp lệ cộng một lần. |
| LI-INT-05 · Admin | Đúng version/cutover, replay và rollback C2 | Proposal chưa áp dụng. Race hai checker, stale, reject, self-approval, ownership và policy được kiểm. HTTP trả mốc hiệu lực thực tế, replay cùng kết quả/cùng key và có một audit C2. Lỗi audit rồi retry với cùng key/challenge kiểm rollback. Không reset usage đã ghi khi đổi cấu hình. |
| LI-INT-06 · Recovery | Phân biệt kết quả và giữ usage | Đọc PENDING/SETTLED/RELEASED. Missing, mismatch facts/T1, partial effects, legacy/unknown mode và lỗi SELECT có outcome riêng. Đọc lại evidence đã SETTLED giữ counter. Role app bị từ chối sửa facts, chuyển lại trạng thái terminal hoặc xóa effect. |
| G1 · V20 | Fresh và upgrade giữ lịch sử | Chuỗi fresh V1–V20 chạy và validate. Nâng cấp từ V19 chạy đúng một migration, checksum cũ giữ nguyên, kiểm lần chạy lại 0 migrations. Số tiền/count và Transfer lịch sử giữ nguyên, V20 không tự tạo evidence cho lệnh cũ. |

## Lệnh tái lập

Chạy từ `digitalbanking-backend`. Chọn các test kiểm Limit provider, Admin, candidate có thay đổi và test migration có kỳ vọng chuỗi canonical. Gradle đóng gói `bootJar` trước khi chạy test.

```sh
export DOCKER_HOST="$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

./gradlew test --offline --max-workers=1 --console=plain \
  --tests '*TransferLimitPostgreSqlIntegrationTest' \
  --tests '*TransferLimitV20MigrationPostgreSqlIntegrationTest' \
  --tests '*LimitEnforcementIT' --tests '*LimitBusinessDateIT' \
  --tests '*LimitCustomerHttpIT' --tests '*LimitConfigMutationPostgreSqlIntegrationTest' \
  --tests '*LimitChangesIT' --tests '*LimitRulesIT' --tests '*LimitConfigTest' --tests '*LimitPersistenceIT' \
  --tests '*LimitAdminHttpIT' --tests '*LimitApprovalHttpPostgreSqlIntegrationTest' \
  --tests '*TransferIntentCandidateTest' --tests '*TransferSettlementCandidateTest' --tests '*TransferSettlementCandidatePostgresTest' \
  --tests '*PostgreSqlFreshMigrationIntegrationTest' --tests '*AccountV8MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalRequestV6MigrationPostgreSqlIntegrationTest' --tests '*AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest' \
  --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest.s01AndC01MfaBindsCustomerAndAllSelfReadsUseThatOwner'

./gradlew test --offline --max-workers=1 --console=plain \
  --tests '*TransferRecoveryCandidatePostgresTest'
```

## Giới hạn

Các ca sát nửa đêm và cutover dùng timestamp điều khiển được; lock, SQL và commit dùng PostgreSQL thật. Nhánh 428 và C1 của candidate T1 dùng test port cho seam chưa đấu nối. Kiểm thử Admin HTTP dùng OTP transport test-only với Identity, Limit và Audit thật. Đường chạy Transfer T0/T1, money T2, Admin approval đầu-cuối và recovery worker có tiêu chí nghiệm thu ở BE-INT-05/06/09/10. Bằng chứng BE-INT-04 áp dụng ở cấp provider, adapter và schema V20.
