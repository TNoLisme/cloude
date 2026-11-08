# Testing 01: Backend Test Matrix, Concurrency Harness & Demo Evidence (`D1–D5`)

| Trường | Giá trị |
|---|---|
| **Dự án** | Digital Banking Platform Simulator (Single-Bank, Core Simulated) |
| **Mã tài liệu** | `BE-TST-01` |
| **Phiên bản** | v1.0 |
| **Ngày cập nhật** | 2026-11-07 |
| **Trạng thái** | Hoàn thành — Tài liệu tham chiếu chuẩn cho Backend Developer & Coding Agent |
| **Nguồn tham chiếu** | `[[digitalbanking-docs/docs-common/03. SRS]]` (§8 AC-001..034, §10 Demo D1–D5), `[[digitalbanking-docs/docs-common/07. API contracts]]` (§10 CT-API-001..024), `[[digitalbanking-docs/docs-common/09. Test plan]]` (Toàn bộ), `[[digitalbanking-docs/docs-common/design/01. audit-traceability]]` (§13), `[[digitalbanking-docs/docs-common/design/02. consistency-concurrency]]` (§12, Phụ lục D) |

---

## 1. Nguyên tắc Kiểm thử Bất di Bất dịch cho Backend (`Non-Negotiable Test Rules`)

Khi viết Unit Test, Integration Test và Concurrency Test cho dự án này, Developer và Coding Agent **bắt buộc** tuân thủ 6 nguyên tắc sau:

1. **Cấm dùng H2 Database cho Integration/Concurrency Test:**
   - H2 không hỗ trợ đúng ngữ nghĩa `SELECT ... FOR UPDATE NOWAIT`, `FOR UPDATE SKIP LOCKED`, `pg_advisory_xact_lock`, Partial Unique Index (`WHERE status = 'ACTIVE'`), và PL/pgSQL Triggers (`trg_ledger_entry_immutable`, `trg_audit_log_immutable`).
   - Toàn bộ Integration/Concurrency Test **bắt buộc** chạy trên **Testcontainers PostgreSQL 16** (`postgres:16-alpine`) với đầy đủ Flyway migrations `V1..V6`.
2. **Không dùng `@Transactional` trên hàm `@Test` khi kiểm thử Concurrency hoặc Multi-Phase (`T0 -> T1 -> T2`):**
   - Nếu gắn `@Transactional` lên hàm test, Spring sẽ giữ mọi thao tác trong 1 transaction duy nhất của luồng test và che giấu hoàn toàn hành vi commit tách rời giữa `T1` và `T2` cũng như tranh chấp khóa giữa các kết nối DB.
   - Mỗi worker thread trong Concurrency Test phải mở kết nối/transaction độc lập và dữ liệu test phải được commit thật xuống Testcontainers PostgreSQL, sau đó dọn dẹp bằng script cleanup riêng.
3. **Đồng bộ hóa điểm xuất phát bằng `CountDownLatch` / `CyclicBarrier`:**
   - Không dùng `Thread.sleep()` tùy tiện để tạo race condition. Phải dùng cặp `readyLatch` và `startLatch` để phóng đồng thời tất cả các luồng vào đúng khoảnh khắc tranh chấp.
4. **Kiểm tra Bất biến Toàn cục (`Global Financial & Audit Invariants`) sau mọi bài test:**
   - Sau mỗi bài test chuyển tiền (dù thành công, thất bại hay lỗi giữa chừng), luôn gọi helper `FinancialInvariantAssertions.assertAllInvariants()` để kiểm chứng: không âm số dư, tổng `DEBIT == CREDIT`, `available_balance == ledger_balance == SUM(CREDIT) - SUM(DEBIT)`, và mọi giao dịch `COMPLETED`/`FAILED` đều có bản ghi `audit_log` hợp lệ.

---

## 2. Cấu hình Base Integration Test với Testcontainers PostgreSQL 16

```java
package com.simulatedbank.banking.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("digital_banking_test")
        .withUsername("banking_migrator")
        .withPassword("test_pwd");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
            () -> POSTGRES.getJdbcUrl() + "&currentSchema=banking");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
```

---

## 3. Mẫu Concurrency Test Harness & Invariant Assertions Chuẩn

### 3.1. Helper chạy $N$ luồng đồng thời (`ConcurrentTestHarness`)

```java
public final class ConcurrentTestHarness {

    public static <T> List<TryResult<T>> runConcurrently(int threadCount, Callable<T> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TryResult<T>>> futures = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(); // Đợi tất cả các luồng sẵn sàng rồi xuất phát cùng lúc
                try {
                    return TryResult.success(task.call());
                } catch (Throwable t) {
                    return TryResult.failure(t);
                }
            }));
        }

        readyLatch.await(10, TimeUnit.SECONDS);
        startLatch.countDown(); // Phóng toàn bộ N luồng cùng lúc!

        List<TryResult<T>> results = new ArrayList<>(threadCount);
        for (Future<TryResult<T>> f : futures) {
            try {
                results.add(f.get(15, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException e) {
                results.add(TryResult.failure(e));
            }
        }
        executor.shutdownNow();
        return results;
    }

    public record TryResult<T>(T value, Throwable error) {
        public static <T> TryResult<T> success(T val) { return new TryResult<>(val, null); }
        public static <T> TryResult<T> failure(Throwable err) { return new TryResult<>(null, err); }
        public boolean isSuccess() { return error == null; }
    }
}
```

### 3.2. Bộ Kiểm chứng Bất biến Tài chính & Kiểm toán (`FinancialInvariantAssertions`)

```java
@Component
public class FinancialInvariantAssertions {
    @Autowired private JdbcTemplate jdbcTemplate;

    public void assertAllInvariants() {
        // 1. Không có tài khoản nào âm tiền hoặc lệch available_balance != ledger_balance
        Integer invalidAccounts = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM banking.account
            WHERE available_balance < 0 OR ledger_balance < 0 OR available_balance <> ledger_balance
            """, Integer.class);
        Assertions.assertEquals(0, invalidAccounts, "INV-BAL-01 violated: negative or mismatched account balance");

        // 2. Mọi giao dịch COMPLETED phải có đúng 2 dòng ledger_entry (1 DEBIT, 1 CREDIT) cân bằng tuyệt đối
        Integer unbalancedTransfers = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM banking.transfer t
            WHERE t.status = 'COMPLETED'
              AND NOT EXISTS (
                  SELECT 1 FROM banking.ledger_entry l
                  WHERE l.transfer_id = t.transfer_id
                  GROUP BY l.transfer_id
                  HAVING COUNT(*) = 2
                     AND SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount ELSE 0 END) = t.amount
                     AND SUM(CASE WHEN l.entry_type = 'CREDIT' THEN l.amount ELSE 0 END) = t.amount
              )
            """, Integer.class);
        Assertions.assertEquals(0, unbalancedTransfers, "INV-LED-01 violated: COMPLETED transfer without balanced double-entry");

        // 3. Không có bất kỳ giao dịch FAILED hoặc PENDING nào có dòng trong ledger_entry
        Integer leakedLedgerRows = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM banking.ledger_entry l
            JOIN banking.transfer t ON t.transfer_id = l.transfer_id
            WHERE t.status IN ('PENDING', 'FAILED')
            """, Integer.class);
        Assertions.assertEquals(0, leakedLedgerRows, "INV-LED-02 violated: Non-completed transfer has ledger entries");

        // 4. Mọi giao dịch COMPLETED hoặc FAILED bắt buộc phải có bản ghi kết thúc trong audit_log
        Integer missingAuditTransfers = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM banking.transfer t
            WHERE t.status IN ('COMPLETED', 'FAILED')
              AND NOT EXISTS (
                  SELECT 1 FROM banking.audit_log a
                  WHERE a.target_type = 'TRANSFER'
                    AND a.target_id = t.reference
                    AND a.action IN ('TRANSFER_COMPLETED', 'TRANSFER_FAILED', 'TRANSFER_RECONCILED')
              )
            """, Integer.class);
        Assertions.assertEquals(0, missingAuditTransfers, "I-AUD-01 violated: Terminal transfer missing audit record");
    }
}
```

---

## 4. Ma trận Kịch bản Kiểm thử Backend Chi tiết (`Backend Test Matrix`)

### 4.1. Nhóm `D1` — Concurrency & Locking (`T-CON-01..04`, `T-LIM-01..02`)

| Mã Test | Kịch bản & Thiết lập Dữ liệu | Hành động Thực thi | Kết quả Kỳ vọng (`Expected Assertions`) |
|---|---|---|---|
| **`T-CON-01`** | Tài khoản `A` có `10,000,000 VND`. Tài khoản `B` có `0 VND`. | Chạy đồng thời `10` luồng chuyển `1,000,000 VND` từ `A -> B` (mỗi luồng 1 `Idempotency-Key` riêng). | Cả `10/10` luồng `201 COMPLETED`. Số dư cuối: `A = 0`, `B = 10,000,000`. Có đúng `20` dòng `ledger_entry`. |
| **`T-CON-02`** | Tài khoản `A` chỉ có `5,000,000 VND`. Tài khoản `B` có `0 VND`. | Chạy đồng thời `10` luồng chuyển `1,000,000 VND` từ `A -> B` (tổng yêu cầu `10,000,000 > 5,000,000`). | Đúng `5` luồng `201 COMPLETED`, đúng `5` luồng `422 INSUFFICIENT_FUNDS`. Số dư cuối: `A = 0` (không bao giờ âm), `B = 5,000,000`. Có đúng `10` dòng `ledger_entry`. |
| **`T-CON-03`** | Tài khoản `A` có `20,000,000 VND`, tài khoản `B` có `20,000,000 VND`. | Chạy đồng thời `10` luồng chuyển `500,000 VND` chiều `A -> B` và `10` luồng chuyển `500,000 VND` chiều ngược lại `B -> A`. | Nhờ khóa theo thứ tự `ORDER BY account_id ASC FOR UPDATE`, **`0` lỗi Deadlock (`40P01`)**. Toàn bộ giao dịch hoàn tất chính xác, tổng số dư `A + B = 40,000,000 VND`. |
| **`T-LIM-01`** | Tài khoản `A` có số dư `100,000,000 VND`, nhưng hạn mức ngày còn lại chỉ còn `8,000,000 VND`. | Chạy đồng thời `5` luồng chuyển `3,000,000 VND` từ `A -> B`. | Nhờ khóa `FOR UPDATE` trên `banking.limit_usage`, chỉ đúng `2` luồng thành công (`6,000,000 VND`), `3` luồng còn lại nhận `422 LIMIT_EXCEEDED_DAILY`. |

### 4.2. Nhóm `D2` — Idempotency & Retry Safety (`T-IDEM-01..06`)

| Mã Test | Kịch bản | Hành động Thực thi | Kết quả Kỳ vọng (`Expected Assertions`) |
|---|---|---|---|
| **`T-IDEM-01`** | Concurrent Duplicate Request | Bắn đồng thời `5` request cùng `Idempotency-Key = K1` và cùng body. | Duy nhất `1` lệnh chuyển tiền được tạo và trừ tiền `1` lần; các luồng đến cùng lúc nhận `409 IDEMPOTENCY_IN_PROGRESS` (hoặc `201` replay nếu đến sau khi `T2` vừa xong). |
| **`T-IDEM-02`** | Key Reuse with Modified Payload | Gửi `Idempotency-Key = K1` với `amount = 1,000,000` (`201`), sau đó gửi lại `K1` với `amount = 2,000,000`. | Request thứ 2 bị từ chối với `409 IDEMPOTENCY_KEY_CONFLICT`, ghi audit `SECURITY_IDEMPOTENCY_CONFLICT`, không phát sinh giao dịch mới. |
| **`T-IDEM-03`** | Safe Replay on `COMPLETED` & `FAILED` | Gửi lại cùng `Idempotency-Key` và cùng body sau khi giao dịch đã `COMPLETED` (`201`) hoặc đã `FAILED` (`422`). | Trả về đúng HTTP status (`201` hoặc `422`) và đúng `response_body` gốc kèm header `Idempotent-Replayed: true`. |
| **`T-IDEM-05`** | Self-Healing Resume after `T2` Rollback | Dùng `@SpyBean` làm `T2` ném lỗi tạm thời ở lần gọi thứ 1 (`transfer` giữ `PENDING`), sau đó gỡ lỗi và gọi lại API với cùng `Idempotency-Key`. | Lần gọi thứ 2 phát hiện bản ghi `RECEIVED` + `transfer (PENDING)` không bị khóa nên tiếp quản và chạy `T2` thành công lên `COMPLETED` (`201`) mà không tạo bản ghi thứ 2. |

### 4.3. Nhóm `D3` — Failure Injection & Crash Recovery (`T-AUD-06`, `T-REC-01..04`, `T-OUT-01..02`)

| Mã Test | Kịch bản Tiêm lỗi (`Fault Injection`) | Cách Tiêm lỗi trong Test | Kết quả Kỳ vọng (`Expected Assertions`) |
|---|---|---|---|
| **`T-AUD-06`** (`AC-034`) | Lỗi ghi Audit ở cuối pha `T2` (`TRANSFER_COMPLETED`) | Dùng `@SpyBean` trên `AuditHashChainWriter` ném `DataAccessResourceFailureException` khi `action == TRANSFER_COMPLETED`. | API trả về `500 AUDIT_WRITE_FAILED`. Toàn bộ `T2` bị rollback: số dư tài khoản nguồn/đích **không đổi**, bảng `ledger_entry` **0 dòng**, bảng `notification` **0 dòng**, `transfer` giữ trạng thái `PENDING`. |
| **`T-REC-01`** | Phục hồi giao dịch `PENDING` bằng Reconciliation Job | Sau khi `T-AUD-06` tạo ra bản ghi `PENDING`, khôi phục `AuditHashChainWriter` bình thường và gọi `transferReconciliationScheduler.reconcilePendingTransfers()`. | Job quét thấy bản ghi `PENDING` qua `FOR UPDATE SKIP LOCKED`, thực thi lại `T2` thành công lên `COMPLETED`, ghi đủ 2 dòng `ledger_entry` và audit. |
| **`T-OUT-01`** | Cổng gửi thông báo (`NotificationSender`) bị sập | Cấu hình `SimulatedNotificationSender` ném `RuntimeException("SMTP timeout")`. | Giao dịch chuyển tiền `POST /api/v1/transfers` vẫn trả về `201 COMPLETED` bình thường. Bản ghi trong `banking.notification` được `NotificationOutboxWorker` tăng `attempt_count` và đặt lịch `next_retry_at` theo Exponential Backoff. |

### 4.4. Nhóm `D4` — Security, Ownership, Step-up, Maker-Checker & Audit Tamper (`CT-API-015..022`, `T-AUD-03..04`)

| Mã Test | Kịch bản Kiểm thử | Kết quả Kỳ vọng (`Expected Assertions`) |
|---|---|---|
| **`CT-API-015`** | `Customer A` gọi `GET /api/v1/accounts/{accountB}` hoặc `POST /api/v1/transfers` từ `accountB` của `Customer B`. | Nhận `403 AUTH_FORBIDDEN` và có 1 dòng `SECURITY_ACCESS_DENIED` trong `banking.audit_log`. |
| **`T-STEP-01`** | Chuyển `15,000,000 VND` (`>= 10M`) không kèm `X-Step-Up-Challenge`, sau đó bắn đồng thời 2 request dùng chung 1 `challengeId` đã `VERIFIED`. | Bước 1 trả về `428 STEP_UP_REQUIRED` (0 dòng `transfer` được tạo). Bước 2 chỉ đúng 1 request tiêu thụ được challenge (`status = CONSUMED`), request kia nhận `403 STEP_UP_INVALID`. |
| **`T-AUD-03/04`** | Thử chạy `UPDATE`/`DELETE` trên `banking.audit_log` và `banking.ledger_entry`; thử kiểm định chuỗi băm bằng `AuditChainVerificationScheduler`. | Lệnh `UPDATE`/`DELETE` bị trigger chặn với `SQLSTATE 55000`. Trình kiểm định xác nhận chuỗi băm hợp lệ từ Genesis đến bản ghi cuối. |

---

## 5. Hướng dẫn Thực thi & Thu thập Bằng chứng Demo (`D1–D5` Evidence Package)

Để phục vụ báo cáo môn học và bảo vệ đồ án (Acceptance Demo `D1–D5`), bộ test backend được tổ chức thành các Tag JUnit 5 để chạy tách biệt và xuất báo cáo:

```bash
# Chạy toàn bộ Unit + Integration + Concurrency + Recovery + Security Tests
./mvnw clean verify

# Chạy riêng từng kịch bản Demo D1 - D4 để lấy log bằng chứng
./mvnw test -Dgroups="demo-d1-concurrency"
./mvnw test -Dgroups="demo-d2-idempotency"
./mvnw test -Dgroups="demo-d3-recovery"
./mvnw test -Dgroups="demo-d4-security-audit"
```

- **Đối với `D5` (Load & Performance Test — `20 RPS × 10 phút`):**
  - Sử dụng kịch bản `k6` hoặc `Gatling` gọi vào môi trường `demo` (Docker Compose gồm Nginx + Spring Boot + PostgreSQL 16).
  - Sau khi kết thúc 10 phút chạy tải (`12,000` requests), chạy câu lệnh SQL đối soát bất biến trong `FinancialInvariantAssertions` để chứng minh: `p95 <= 2s`, `p99 <= 5s`, `error_rate < 1%`, và `0` sai lệch số dư/sổ cái.
