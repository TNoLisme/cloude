# 03. Database Migration, Phân quyền và Backup/Restore

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-03`
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Baseline đã đối chiếu `docs-common/` — chưa có bằng chứng runtime
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§8), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]], [[digitalbanking-docs/docs-common/design/04. security.md]]

---

## 1. Chính sách migration

- Schema được quản lý bằng migration có version, **forward-only**.
- Không sửa migration đã áp dụng; mọi thay đổi tạo migration mới.
- JPA/Hibernate chỉ `validate`; không tự tạo/sửa bảng nghiệp vụ.
- Thay đổi migration phải qua review database, review API bị ảnh hưởng và cập nhật test plan.
- Phải có evidence cho **clean install** và **upgrade path**.
- Migration lỗi chặn release; không mở traffic nghiệp vụ trên schema đã migrate một phần.
- Không rollback database bằng cách đảo migration đã áp dụng; xem chính sách rollback tại [[digitalbanking-docs/docs-backend/deployment/configuration/05-release-and-rollback.md]].
- Chỉ role `banking_migrator` được chạy DDL; runtime dùng `banking_app`.

---

## 2. Thứ tự migration (`08` §8.1)

`08` chốt **thứ tự 12 bước**; tên file và số version chính xác là phạm vi implementation (`CFG-OPEN-05`).

| Bước | Nội dung | Ghi chú |
|---:|---|---|
| 1 | Tạo schema namespace và migration metadata | Schema `banking`; Flyway tự quản lý history. |
| 2 | Bảng reference và identity độc lập | `app_user`, role/permission, device, session, auth challenge/attempt, password history. |
| 3 | Bảng customer và account | Kèm `CHECK` số dư không âm. |
| 4 | Bảng transfer, ledger, idempotency | Kèm ràng buộc cặp bút toán kép. |
| 5 | Bảng limit, usage, approval, fraud | |
| 6 | Bảng audit, notification, retention, legal hold | Tách riêng khỏi nhóm limit/fraud. |
| 7 | Foreign key và ràng buộc liên miền | Chỉ thêm sau khi bảng được tham chiếu tồn tại. |
| 8 | Trigger và append-only protection | Sau khi bảng đích tồn tại. |
| 9 | Index | Tách khỏi FK/trigger; có thể tune riêng. |
| 10 | Seed dữ liệu tổng hợp | Chỉ `local`/`demo`; sau khi schema/constraint validate. |
| 11 | Backfill | Migration riêng, resumable, có evidence. |
| 12 | Không sửa migration đã áp dụng | Mọi thay đổi sau đó là migration forward-only mới. |

### 2.1. Kế hoạch file gợi ý (Proposed)

> Đây **không phải** tên file chuẩn. Chỉ dùng để minh họa cách ánh xạ 12 bước; implementation có thể đổi số version/tên file.

| Version gợi ý | Nội dung |
|---|---|
| `V1` | Schema `banking`, extension/hàm tiện ích cần thiết. |
| `V2` | Reference, identity, RBAC, session, auth. |
| `V3` | Customer, account và constraint số dư. |
| `V4` | Transfer, ledger, idempotency. |
| `V5` | Limit, usage, approval, fraud. |
| `V6` | Audit, notification, retention, legal hold. |
| `V7` | Foreign key và cross-domain constraints. |
| `V8` | Append-only triggers. |
| `V9` | Indexes. |
| `V10` | Seed reference data cho `local`/`demo`. |
| `V11+` | Backfill riêng khi cần. |

Nếu dùng seed, ưu tiên location riêng theo profile (ví dụ `classpath:db/migration,classpath:db/seed`) để seed không chạy nhầm trên môi trường khác. Không đưa dữ liệu thật hoặc secret vào seed.

---

## 3. Phân quyền DB least privilege

Xem chi tiết role và script tại [[digitalbanking-docs/docs-backend/deployment/configuration/01-environment-and-secrets.md]] §6. Các điểm bắt buộc:

1. `banking_migrator`: DDL/migration; không dùng cho runtime pool.
2. `banking_app`: không `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `DROP`, `TRUNCATE`.
3. `banking_app` chỉ `SELECT, INSERT` trên `ledger_entry` và `audit_log`.
4. `banking_app` không là owner bảng.
5. Không cấp quyền cho `PUBLIC`.
6. Bảng mới phải được grant/revoke tường minh; kiểm tra lại append-only sau mỗi migration tạo bảng evidence.

---

## 4. Backup

### 4.1. Chính sách

| Hạng mục | Tần suất/trigger | Thủ tục tối thiểu | Retention/trạng thái | Evidence |
|---|---|---|---|---|
| Database giao dịch | Trước mỗi demo và mỗi migration | Logical backup ngoài runtime volume | Bắt buộc theo trigger; retention **Open** | File metadata + checksum |
| Scheduled logical backup | Chưa quy định | Chốt sau khi thiết kế RPO | **Open**; không được tuyên bố RPO | Schedule + restore report |
| WAL/PITR/incremental | Chưa quy định | Đánh giá nếu giữ RPO 5 phút | **Open**; production dependency | Đo data loss |
| Audit data | Theo retention/legal hold | Giữ record, chain metadata, archive manifest | 5 năm **Proposed** | Kết quả verify chain |
| Transfer/ledger/fraud/limit history | Theo retention | Archive trước purge; chặn legal hold | 5 năm **Proposed** | Archive/purge evidence |
| Auth challenge | Theo retention | Purge sau ngưỡng | 90 ngày **Proposed** | Retention evidence |
| Session | Sau khi hết hạn | Purge sau ngưỡng | 30 ngày **Proposed** | Retention evidence |
| Notification | Theo retention | Purge sau ngưỡng | 90 ngày **Proposed** | Retention evidence |
| Idempotency record | Theo retention | Purge sau ngưỡng | 24 giờ **Proposed** | Replay/retention evidence |
| Configuration | Trước release/thay đổi | Lưu bản redacted + version | **Open** | Không có secret |
| Evidence package | Mỗi test/demo/recovery | Lưu report, log, manifest | **Proposed** | Manifest |

Backup phải nằm ngoài runtime volume, được bảo vệ khỏi truy cập trái phép và **phải được restore thử**. Backup chưa restore không được coi là evidence phục hồi.

### 4.2. Lệnh backup mẫu

```bash
BACKUP_FILE="backups/digital_banking_$(date -u +%Y%m%dT%H%M%SZ).dump"
pg_dump \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_MIGRATION_USER" \
  -d "$DB_NAME" -n banking -Fc -f "$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
# macOS: shasum -a 256 "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
```

Không đưa mật khẩu trực tiếp vào command line; dùng `PGPASSWORD`/passfile hoặc secret store của môi trường. Lệnh trên là mẫu vận hành, chưa phải evidence đã chạy.

---

## 5. Restore và kiểm chứng sau restore

### 5.1. Thứ tự phục hồi (`08` §8.4)

1. Phát hiện lỗi database/runtime.
2. Chuyển sang **fail-closed**; không trả success giả cho giao dịch tiền.
3. Bảo toàn log, correlation ID, audit evidence và timestamp lỗi.
4. Restore/reconnect database theo quy trình đã duyệt.
5. Kiểm tra schema, constraint, audit integrity và financial invariant.
6. Chỉ mở traffic sau khi health và smoke test đạt.
7. Retry request chưa rõ kết quả với **cùng idempotency key**.
8. Ghi lại RTO/RPO thực tế và data gap (nếu có).

### 5.2. Kiểm chứng bắt buộc

| # | Kiểm tra | Cách làm | Trạng thái |
|---:|---|---|---|
| 1 | Flyway history | Startup `validate-on-migrate`; task Flyway Gradle nếu được cấu hình; hoặc query `flyway_schema_history` | Bắt buộc |
| 2 | Không có số dư âm | Query `account` với `available_balance < 0 OR ledger_balance < 0` | Bắt buộc |
| 3 | `available_balance == ledger_balance` | Query đối chiếu trên mọi account | Bắt buộc (MVP không có hold) |
| 4 | Cân bằng sổ cái | Tổng debit == tổng credit trên `ledger_entry` | Bắt buộc |
| 5 | Không duplicate debit | Đối chiếu transfer/idempotency/ledger | Bắt buộc |
| 6 | Audit chain | Chạy `AuditVerificationJob`/quy trình verify; `prev_hash -> record_hash` liên tục, không đứt | Bắt buộc |
| 7 | Health + smoke | `/actuator/health` và một luồng nghiệp vụ mẫu | Bắt buộc |

> Tên cột/bảng trong query phải theo migration thực tế. Không sửa `ledger_entry` hoặc `audit_log` để "sửa" kết quả kiểm tra.

### 5.3. Mục tiêu RTO/RPO

`NFR-007` đề xuất **RTO ≤ 30 phút** và **RPO ≤ 5 phút**. Đây là **Proposed**, chưa verified cho đến khi có diễn tập restore và đo data loss thực tế (`CFG-OPEN-06`).

---

## 6. Tiêu chí nghiệm thu DR

Một diễn tập DR chỉ đạt khi:

- Restore hoàn tất trong RTO đã duyệt hoặc có ghi nhận độ lệch.
- Data loss không vượt RPO đã duyệt hoặc có ghi nhận độ lệch.
- Không có số dư âm, duplicate debit hoặc sổ cái không cân.
- Audit integrity vẫn verify được.
- Health báo đúng trạng thái trước và sau phục hồi.
- Evidence ghi rõ môi trường, quy trình, timestamp và kết quả thực tế.

---

## 7. Điều cấm

- Không dùng SQL thủ công để đặt `transfer.status = 'COMPLETED'` khi chưa có cặp `ledger_entry` và `audit_log` hợp lệ.
- Không `UPDATE`/`DELETE` `ledger_entry` hoặc `audit_log` để xử lý sự cố.
- Không sửa migration đã áp dụng.
- Không chạy restore đè lên evidence đang cần điều tra mà chưa bảo toàn bản sao.
- Không mở traffic khi migration mới chỉ chạy một phần.

---

## 8. Kiểm chứng

| ID | Kịch bản | Kỳ vọng | Trạng thái |
|---|---|---|---|
| `DVT-004` | Rà soát migration | Forward-only, clean install, upgrade path có evidence | **Not verified** |
| `DVT-005` | Diễn tập backup/restore | Restore trong target hoặc ghi nhận variance | **Not verified** |
| `DVT-006` | Failure/recovery | Không false success, không duplicate debit, ledger cân | **Not verified** |
