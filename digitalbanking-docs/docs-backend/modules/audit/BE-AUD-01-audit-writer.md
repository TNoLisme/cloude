# BE-AUD-01 — Audit writer và internal port

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-01; **Task:** BE-AUD-01  
> **Phiên bản:** 0.2; **Ngày:** 11/11/2026  
> **Trạng thái tài liệu:** Draft — chờ review; **Implementation:** Planned; **Verification:** Not verified  
> **Người đề xuất / Owner:** Thịnh; **Reviewer đề xuất:** Phương (common/ledger), Đạt (identity/admin), Vũ (transfer); chưa có approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §4.3, §5, §6, §7, §13; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.6.1; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §4, §6, §10; [[digitalbanking-docs/docs-common/09. Test plan.md]].

## 1. Phạm vi và truy vết

FR-034/035/037/038/039, AC-018/019/021/022/023/034, NFR-020/021/023: audit nghiệp vụ bất biến, hash chain và tính nguyên tử với giao dịch. Đây là capability đầu tiên cung cấp cho identity, transfer, fraud và admin.

Trong phạm vi: contract ghi audit, action registry, validation/allowlist, canonicalization, JDBC writer, migration và permission, test component và test tích hợp caller. Ngoài phạm vi: REST query/export (BE-AUD-02), retention/legal hold (BE-AUD-03), external anchor và Cloud.

Thịnh chủ động dùng các proposal dưới đây làm thiết kế v0.1 và có thể code trước với fake caller. Không cần chờ các owner viết xong module. Review là checkpoint trước tích hợp hoặc đổi nguồn chuẩn, không ngăn spec/unit/component code.

## 2. Interface, kiểu dữ liệu và file map

Chữ ký dưới đây là **đề xuất chưa có trong source**. Root production: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/audit/`; test root tương ứng dưới `src/test/java/`.

| File | Trách nhiệm |
|---|---|
| `api/AuditModuleApi.java` | Port ghi audit cho module khác |
| `api/dto/AuditEvent.java`, `AuditRecordRef.java` | DTO bất biến, không lộ entity |
| `domain/AuditActionCatalog.java` | Map action tới class C1/C2/C3/C4, entity và field allowlist |
| `domain/AuditEventValidator.java` | Requiredness, kiểu dữ liệu, kích thước, PII |
| `domain/AuditHashPayload.java` | Canonical field set cố định theo hash version |
| `application/AuditRecorder.java` | Transaction propagation và điều phối |
| `application/port/AuditStore.java` | Outbound storage port |
| `adapter/out/AuditJdbcStore.java` | SQL lock, last hash và insert |

```java
public interface AuditModuleApi {
    AuditRecordRef recordInTransaction(AuditEvent event);
    Optional<AuditRecordRef> recordStandalone(AuditEvent event);
}
public record AuditRecordRef(long auditId, String recordHash, short hashVersion) {}
public record AuditEvent(
    String action, String actorType, Long actorUserId, String actorRole,
    String entityType, String entityId,
    Map<String,Object> beforeState, Map<String,Object> afterState,
    BigDecimal amount, String currency, String correlationId,
    String requestId, String outcome, Map<String,Object> clientInfoMasked,
    Instant occurredAt) {}
```

Records phải defensive-copy sâu map/list; Java record tự nó không làm Map bất biến. Null snapshots giữ null; không xóa null khỏi canonical payload. Không nhận arbitrary object để serializer duyệt object graph.

**AUD-P01 — validation đề xuất:**
- `actorType`: USER/SYSTEM; `outcome`: SUCCESS/FAILURE/REJECTED; USER bắt buộc actorUserId dương. Login không biết username dùng SYSTEM/ANONYMOUS, không ghi username.
- Action phải thuộc catalog nguồn; entityId không rỗng và theo entity convention.
- correlationId theo pattern nguồn; requestId do server/job tạo. Job tự tạo ID, không dùng chuỗi rỗng.
- Amount null thì currency null; có amount thì VND, không âm, scale tối đa 2; canonical money fixed 2 decimals, không dùng double.
- Timestamp UTC, chuẩn hóa microsecond trước persist và hash; format 6 fractional digits.
- Snapshot chỉ nhận fields/types trong design/01 §5.6, tối đa 32 KB; nested matchedValue chỉ amount/count/window. Không reject chuỗi số mù vì ID/money hợp lệ.
- Không ghi password/hash/token/OTP/username/notes/description hoặc PII raw. Client info chỉ IP masked và userAgent tối đa 256 ký tự.

## 3. Dữ liệu, hash và SQL

Bảng `banking.audit_log` theo `04 §4.6.1`: audit_id BIGINT identity; occurred_at/recorded_at; actor; entity; JSONB snapshots; amount NUMERIC(19,2); currency; correlation_id/request_id; outcome; client_info_masked; prev_hash/record_hash CHAR(64); hash_version SMALLINT. Index theo occurred_at, correlation_id, entity, actor, action.

**AUD-P02 — hash v1 proposal:** dùng công thức chi tiết design/01 §7.3: `lowercase_hex(SHA-256(UTF8(JCS(payload) + "|" + prev_hash)))`. hash_version chỉ nằm trong payload, không nối lần hai. Field set v1: occurred_at, actor_type/user_id/role, action, entity_type/id, before_state, after_state, amount/currency, correlation_id/request_id, outcome, client_info_masked, hash_version. Không có audit_id, recorded_at, prev_hash, record_hash hoặc subject_customer_id trong payload v1.

Genesis là 64 số 0; application luôn ghi prev_hash. Đề xuất NOT NULL + UNIQUE record_hash theo design/01; ghi vào migration proposal. Không đổi DB dùng chung âm thầm. Nếu bổ sung subject_customer_id sau này, cần hash version mới.

SQL writer proposal (adapter, cùng transaction caller):

```sql
SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0));
SELECT record_hash FROM banking.audit_log ORDER BY audit_id DESC LIMIT 1;
-- Tính hash trong application bằng canonical bytes đã chuẩn hóa.
INSERT INTO banking.audit_log
    (occurred_at, actor_type, actor_user_id, actor_role, action,
     entity_type, entity_id, before_state, after_state, amount, currency,
     correlation_id, request_id, outcome, client_info_masked,
     prev_hash, record_hash, hash_version)
VALUES (:occurred_at, :actor_type, :actor_user_id, :actor_role, :action,
        :entity_type, :entity_id, CAST(:before_state AS jsonb),
        CAST(:after_state AS jsonb), :amount, :currency, :correlation_id,
        :request_id, :outcome, CAST(:client_info_masked AS jsonb),
        :prev_hash, :record_hash, :hash_version)
RETURNING audit_id, record_hash, hash_version;
```

Identity cấp lúc INSERT dưới advisory lock. Khi chưa purge, bảng rỗng dùng genesis; sau checkpoint/purge dùng verified checkpoint. Migration tạo bảng/constraint/trigger thuộc audit. Nếu app_user chưa có, đề xuất FK theo `04`; dùng fixture schema disposable hoặc migration FK sau target table. Không tạo production identity table giả. Flyway version chốt lúc tích hợp. Runtime banking_app chỉ SELECT/INSERT audit, không UPDATE/DELETE/TRUNCATE; test permission bằng app role, không dùng postgres superuser.

## 4. REST và xử lý lỗi

Task không tạo REST. C1/C2 port failure propagate để rollback transaction; HTTP caller map qua common error catalog, ví dụ AUDIT_WRITE_FAILED nếu contract quy định. Không trả AuditRecordRef thay transfer receipt. SQL/stack trace/canonical payload không lọt response/log.

Không cài crypto/error/context lại trong audit production. **JCS dependency:** Java/Jackson serialization mặc định và sort-key thủ công không đảm bảo RFC 8785. Đề xuất Phương/common chọn hoặc cung cấp implementation dùng chung; audit gọi API nhỏ nhận JSON-compatible value, trả canonical UTF-8 bytes. Đánh giá library license, maintenance, Java compatibility và golden vectors. Nếu provider chưa được review, hash integration giữ Blocked; không âm thầm downgrade RFC.

## 5. Luồng và transaction semantics

| Class | Cách gọi | Khi lỗi |
|---|---|---|
| C1/C2 | `recordInTransaction`, MANDATORY | Rollback nghiệp vụ và audit cùng nhau |
| C3 | `recordStandalone`, transaction riêng sau security-state transaction | Optional.empty, safe log/metric/alert; không hoàn tác lockout/logout |
| C4 | Job tự quản transaction theo semantics sự kiện | Retry/alert job; không ghép vào transaction tiền đã kết thúc |

Recorder phân loại theo catalog; caller không đổi class để né fail-closed. Spring propagation qua proxy hoặc TransactionTemplate; không dựa self-invocation.

1. Chuẩn hóa và validate trước khi giữ audit lock.
2. Caller hoàn thành business locks/writes; writer audit chạy cuối.
3. Lấy advisory transaction lock, đọc predecessor, hash và insert.
4. Nếu một transaction có nhiều audit events, nối liên tiếp tại bước cuối; không lấy audit lock rồi quay lại acquire business lock.
5. Commit ở caller. Lỗi T1 rollback toàn bộ T1. Audit cuối T2 fail làm rollback T2; T1 PENDING có thể còn.

Không gọi C3 REQUIRES_NEW khi outer transaction còn giữ audit lock để tránh self-wait. Ghi sau khi outer kết thúc hoặc safe technical fallback. Export là C2, không dùng C3 best-effort; xem AUD-02.

## 6. Action catalog và quan sát

Map action đầy đủ từ design/01 §5.3; ví dụ TRANSFER_CREATED/COMPLETED/FAILED/RECONCILED là C1; AUTH_LOGIN_SUCCESS/AUTH_STEP_UP_VERIFIED, LIMIT_CHANGE_*, ROLE_CHANGE_*, FRAUD_FLAG_CREATED/REVIEWED là C2; login failure/access denied/idempotency conflict là C3. Không sinh wildcard action thật.

Metric audit writes theo class/outcome; không tag ID. C3 fallback không dump event, chỉ metadata allowlisted.

## 7. Trình tự code và kiểm chứng

- [ ] DTO/catalog/validator + `AuditEventValidatorTest`: unknown action, missing actor, oversize/nested payload, numeric IDs hợp lệ, forbidden PII.
- [ ] `AuditCanonicalizationTest` (T-AUD-01/21): order keys/Unicode/null/money/time, v1 fields; compare golden vector độc lập.
- [ ] Migration/JDBC + `AuditWriterIT` (T-AUD-02/03/04): chain insert/recompute, app role mutation denied; PostgreSQL 16 disposable.
- [ ] `AuditConcurrencyIT` (T-AUD-05): 50 writers, barrier/connections; một chain liên tục; sequence gap không false positive.
- [ ] `AuditTransactionIT` (T-AUD-06/07/09): business mutation rollback cùng audit; MANDATORY ngoài transaction fail; C3 fail không rollback security state. Đây là component proof, chưa money-path proof.
- [ ] `AuditTransferIT` (AC-034/T-AUD-06/07/08/12): caller transfer thật, T1/T2 failure, replay, correlation. Caller chưa sẵn sàng thì integration Blocked.

Lệnh dự kiến từ Backend: `./gradlew test --tests '*audit.AuditEventValidatorTest' --tests '*audit.AuditCanonicalizationTest' --tests '*audit.AuditWriterIT' --tests '*audit.AuditConcurrencyIT' --tests '*audit.AuditTransactionIT'`. Windows dùng `gradlew.bat`. Các class chưa tồn tại; đây là kế hoạch, chưa chạy.

## 8. Giới hạn MVP và đo hiệu năng

**AUD-P04 — global lock trade-off:** `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))` tuần tự writer C1/C2 tại bước nối hash. Contention có thể tăng money-path latency và DB connection hold time. MVP chấp nhận lựa chọn này để giữ một chain có thứ tự; không cam kết TPS cụ thể. Chưa có benchmark chứng minh ngưỡng vài trăm TPS hoặc capacity khác.

- Không claim hiệu năng đạt trước benchmark trên DB/pool config có revision.
- Khi runtime path sẵn sàng, benchmark writer đồng thời với transfer; ghi throughput, lock-wait p50/p95/p99, transaction duration, pool wait/timeouts, error rate, workload, hardware, pool, seed và command.
- Nếu benchmark vượt SLO nhóm chốt, mở ADR trước đổi chain. Partition theo account hoặc batching/single-writer là hướng nghiên cứu; đổi chain semantics, atomicity, verifier, export, checkpoint/purge.
- Chain nội bộ không phát hiện chắc chắn tail truncation nếu thiếu external anchor. Anchor ngoài phạm vi task này.

**AUD-P05 — RFC 8785/JCS:** Không dùng Jackson mặc định hoặc sort-key thủ công làm canonicalizer. Phương/common cần chọn/cung cấp implementation dùng chung. Golden vectors độc lập gồm Unicode, escaping, null, nested object, numeric edge cases và timestamp. Chưa có provider được review thì hash integration Blocked, không downgrade âm thầm khỏi RFC.

## Proposal, Definition of Done và handoff

AUD-P01 event/types/validation, AUD-P02 hash/schema, AUD-P03 transaction semantics, AUD-P04 global-chain trade-off và AUD-P05 JCS provider đều là proposal của Thịnh. Có thể code core/domain/validator/SQL với fake; giữ `api/` nhỏ. Không khóa tên/type/error DTO tích hợp trước owner review. Trước ghép, Phương review common/JCS/DDL; Vũ/Đạt review caller contract. Ghi decision và revision vào [verification report](BE-AUD-01-verification-report.md).

Done component khi unit/PostgreSQL/permission/concurrency pass; done integration khi caller thật chứng minh audit failure rollback money/replay an toàn. Không báo performance đạt nếu chưa benchmark. Không báo toàn module audit xong khi query/retention còn task riêng.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Thịnh đề xuất typed contract, hash v1, transaction và test plan; chưa code |
| 0.2 | 11/11/2026 | Ghi global-lock trade-off, benchmark gate, tail-truncation limit và JCS dependency |
