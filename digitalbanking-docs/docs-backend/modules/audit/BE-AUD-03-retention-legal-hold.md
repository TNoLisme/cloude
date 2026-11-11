# BE-AUD-03 — Retention, legal hold và archival

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-03; **Task:** BE-AUD-03  
> **Phiên bản:** 0.2; **Ngày:** 11/11/2026; **Trạng thái:** Draft — chờ review  
> **Owner:** Thịnh; **Reviewer đề xuất:** Đạt (admin/maker-checker), Phương (DB), Vũ (transfer/ledger)  
> **Nguồn:** [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §6.5, §10, §13; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.6.2–3, §9.6; [[digitalbanking-docs/docs-common/07. API contracts.md]] §1.2, §11.3; [[digitalbanking-docs/docs-backend/modules/_overview/10-admin.md]].

## 1. Mục tiêu, scope và safety gate

Đặc tả vòng đời audit/compliance: policy, legal hold, archive, checkpoint và purge có thể kiểm chứng, không xóa nhầm dữ liệu cần giữ. Đây là task high-risk, phụ thuộc audit writer/query cùng admin approval. Thịnh có thể viết design/tests proposal trước; không chạy purge hoặc bật destructive job trước khi policy, approval, archive/checkpoint, DB quyền và rollback được review.

Trong phạm vi design/code proposal: policy model, legal hold model, resolver scope, query/commands qua admin owner, archive manifest, checkpoint, purge batch, verification, synthetic integration tests. Ngoài phạm vi: production archival provider, WORM object storage, legal advice, Cloud deployment, purge dữ liệu thật.

**MVP safe default:** purge disabled; policy chỉ audit/read/report. Thời hạn 5 năm, archive sau 4 năm và retention values là đề xuất `OI-002`, không coi là luật hoặc quyết định nhóm. Purge test chỉ trên Testcontainers DB disposable.

## 2. Data ownership và contract phụ thuộc

`audit` sở hữu `audit_log`, `audit_chain_checkpoint`, `legal_hold`, `retention_policy`; `admin` sở hữu `approval_request`. Admin quyết định qua API/port, audit ghi event, retention executor chỉ đọc admin decision/hold contract. Không ghi trực tiếp bảng approval hoặc bảng business module khác.

```java
public interface RetentionPolicyPort {
    RetentionPolicyView getActivePolicy(DataClass dataClass, Instant asOf);
}
public interface LegalHoldPort {
    HoldDecision checkBeforeArchiveOrPurge(DataScope scope, Instant asOf);
    LegalHoldView place(PlaceHoldCommand command);
    LegalHoldView release(ReleaseHoldCommand command);
}
```

Đây là proposal để code domain/service tests trước; admin không cần cung cấp DB entity. `DataScope` đề xuất entityType/entityId/fromAuditId/toAuditId; `HoldDecision` gồm blocked/holdIds/safe reasonCode. `PlaceHoldCommand`: scope, actorId, reason, correlationId. `ReleaseHoldCommand`: holdId, approvalId, checkerId, correlationId. `LegalHoldView` không trả free-text reason cho caller không được phép. Server xác minh approval evidence; không tin approvalId/checkerId client gửi. Legal hold create/release và retention-policy change cần quyền/strong auth/approval C2 theo design/01; API chi tiết hiện ngoài OpenAPI MVP scope. Không thêm public endpoint trước khi contract được duyệt.

## 3. Dữ liệu, scope và flow

`04 §4.6.2–3` chưa mô tả đủ `audit_chain_checkpoint`; `design/01 §6.5.2` là proposal. Schema cần checkpointType VERIFIED/PURGE/ANCHOR, throughAuditId/hash, recordCount, verifiedAt, archiveLocation, signature/keyId theo checkpoint type; không bắt buộc mọi field cho mọi type. Ghi rõ mismatch trước migration; không tự chốt cột chưa có nguồn.

`LegalHoldResolver` xác định record IDs/ranges bị ảnh hưởng bởi CUSTOMER/ACCOUNT/TRANSFER/AUDIT_LOG/DATA_SCOPE. Không coi `subject_customer_id` là schema đã có. Resolve CUSTOMER/ACCOUNT qua owner ports hoặc index/backfill đã review; không query polymorphic tùy tiện qua bảng module khác. Nếu scope không resolve chắc hoặc source unavailable, block purge.

1. Đọc enabled policy, tính cutoff deterministic.
2. Resolve scope và check legal hold.
3. Nếu hold active, skip; không archive hoặc purge.
4. Archive continuous chain segment; manifest gồm range/count/hash/checksum, không PII thừa.
5. Verify archive readback/checksum; tạo checkpoint PURGE theo schema đã duyệt.
6. Trong transaction batch ngắn, recheck hold/approval, purge rows được phép và ghi `RETENTION_PURGED` cùng transaction. Audit fail rollback batch.
7. Verify retained chain từ checkpoint; mismatch dừng job và alert.

Chỉ purge prefix liên tục đầu chain; không xóa giữa chuỗi. Hold-vs-purge race cần một advisory lock protocol theo scope/key: đặt hold và purge cùng acquire lock trước mutation; purge giữ lock từ recheck hold đến delete. Lock key/order/batch cần review; nếu chưa thống nhất, purge giữ disabled.

## 4. Security, concurrency và config

Admin permission check ở endpoint và service; maker khác checker. Không audit raw hold reason; audit chỉ holdId, scope type/hash, reason code. Archive manifest không chứa credential. DB runtime role không có DELETE/TRUNCATE audit/ledger; nếu purge cần privileged function/role, phải review threat model và giới hạn invocation.

Config tham chiếu `deployment/configuration.md`; không hard-code cron/retention. Profile test dùng Testcontainers disposable. Purge disabled mặc định ở local/shared/demo/production. Không bật từ `.env.example` hoặc draft docs.

## 5. API/admin workflow

OpenAPI `07 §1.2` loại API retention/legal hold chi tiết khỏi MVP scope. Đề xuất release flow: maker đề xuất; checker step-up approve; admin gọi `LegalHoldPort.release`; audit C2 cùng transaction; retention recheck trước batch sau. Đây là proposal, chưa phải public endpoint. Service/domain tests có thể làm trước; không expose API mới trước khi contract được duyệt.

## 6. Tests và acceptance

- Unit scope resolver: CUSTOMER/ACCOUNT/TRANSFER/AUDIT_LOG/DATA_SCOPE; overlap/parent-child không bị bỏ sót.
- Testcontainers: hold ngăn archive/purge; concurrent hold-vs-purge không race; archive/checkpoint failure không delete; audit failure rollback; corrupt chain dừng job.
- Checkpoint: retained chain đúng từ checkpoint; purge chỉ contiguous prefix; sequence gap không bị coi là tamper.
- RBAC/approval với admin/identity thật trước claim integrated.
- Crash sau archive/checkpoint, giữa purge và trước audit commit; restart idempotent, không mất/xóa lặp.

Acceptance: purge disabled mặc định; không purge khi hold active, archive thiếu hoặc verification fail; hold recheck ngay trước delete; purge/audit atomic; evidence tái lập được. Không test DB shared/real data. Không claim legal compliance từ test kỹ thuật.

## 7. Thứ tự làm

1. Viết proposal data classes, hold scope, retention period.
2. Viết checkpoint schema/privilege proposal và review với audit/DB owners.
3. Chốt admin port/approval với Đạt; trước đó làm unit/domain tests, không expose endpoint.
4. Implement archive verification trước purge; thử trên synthetic Testcontainers.
5. Implement purge dưới guard disabled, batch nhỏ, recheck hold, audit cùng transaction.
6. Run failure/recovery/security tests và review rollback; không bật shared/prod job.
7. Ghi evidence vào [verification report](BE-AUD-03-verification-report.md); tách component verified khỏi production readiness.

## 8. Purge gate và checkpoint recovery

MVP gate: spec/code + dry-run/report trên synthetic data; purge job disabled trong shared, demo, production. Testcontainers disposable có thể chạy purge để test invariant; xác minh DB target disposable trước chạy. Không có feature flag hoặc draft doc nào tự cho phép bật purge runtime.

Checkpoint là trust boundary. Sai throughAuditId/hash, range/count hoặc archive checksum có thể làm verifier reject retained chain hoặc chấp nhận chain sai. Không purge trước khi serialized checkpoint và archive bytes verify độc lập từ source snapshot.

- Capture throughAuditId/hash, recordCount, hashVersion, range, archiveHash từ prefix liên tục trong cùng snapshot; verify records và archive readback trước checkpoint commit.
- Checkpoint append-only, unique theo policy/range/type, có metadata version. Signature/keyId chỉ khi key management được chốt; hash không khóa không phải chữ ký.
- Sau purge, verify record đầu retained có `prev_hash == checkpoint.throughRecordHash`; verify retained range. Mismatch/unknown version/checksum mismatch dừng job, alert, giữ archive/checkpoint.
- Inject sai byte/hash/range/count và crash ở mỗi boundary. Expected: không delete trước archive/checkpoint verified; audit failure rollback; restart không áp sai purge/checkpoint lần hai.
- Checkpoint invalid: không reset genesis, không overwrite chain để “sửa”; chỉ restore từ trusted archive/backup theo incident procedure.

Purge cần independent review trước mọi shared environment. Test fixture không chứng minh legal compliance hay production recoverability.

## Open issues / handoff

Retention legal values, scope resolution, chain partition/purge, privileged DB role, checkpoint signature/storage, admin API, restore sau hold muộn đều Proposed. Chưa có code/migration/purge execution.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Đề xuất safety-first retention/legal hold spec |
| 0.2 | 11/11/2026 | Thêm purge gate và checkpoint recovery criteria |
