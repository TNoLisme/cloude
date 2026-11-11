# Quy trình và thứ tự task Backend của Thịnh

> **Dự án:** Digital Banking MVP; **Mã tài liệu:** BE-WORK-THINH-01  
> **Phiên bản:** 0.3; **Ngày:** 11/11/2026; **Baseline:** `717755c2c7d99e405be0c039ddc67d2fa90f0c41`  
> **Người đề xuất:** Thịnh; **Reviewer đề xuất:** Đạt, Vũ, Phương theo phạm vi  
> **Trạng thái:** Draft — chờ review; business implementation và test evidence của tasks dưới đây chưa có  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §2–§6; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §4–§12; [[digitalbanking-docs/docs-common/09. Test plan.md]].

## 1. Mục tiêu và cách làm chủ động

Thịnh viết docs/spec/hợp đồng tích hợp/test plan trước; có thể code phần mình với fake/test adapter khi module khác chưa có. Những gì trước đây ghi “chờ thống nhất” được chuyển thành proposal cụ thể của Thịnh, ghi tác động và reviewer. Không đợi owner khác hoàn thành mới bắt đầu.

Proposal không đồng nghĩa team đã duyệt. Tách ba trạng thái:
- **Tài liệu:** Draft — chờ review / đã review; ghi reviewer, ngày, revision.
- **Triển khai:** Planned / Implemented / Integrated; fake-only không phải Integrated.
- **Kiểm chứng:** Planned / Not verified / Verified / Blocked; component Verified không đồng nghĩa integration/release Verified.

Base/config là baseline làm việc theo chỉ đạo. Task này không audit lại hoặc sửa base. Khi audit phát hiện vấn đề, ghi change request và cập nhật phần liên quan sau review.

## 2. Phạm vi sở hữu

Thịnh owner `audit`, `notification`, `fraud`; phối hợp fraud/audit trong `admin`. Đạt owner chính `admin` và `identity/customer/account`; Phương owner `common`; Vũ owner `transfer/limit`. AI là nghiên cứu mở rộng, không thay fraud rule-based MVP.

Source: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/`; tests trong package tương ứng `src/test/java/`; module docs tại `digitalbanking-docs/docs-backend/modules/<module>/`. README chỉ hướng dẫn/link. Workflow liên-module ở `workofteam/`.

## 3. Task order — không gắn tuần

| Order | Task/spec | Standalone output | Integration dependency |
|---|---|---|---|
| 01 | [BE-AUD-01 — writer](../modules/audit/BE-AUD-01-audit-writer.md) | Port, validator, hash-chain core, append-only DB tests | common, identity, transfer |
| 02 | [BE-AUD-02 — query/export/verify](../modules/audit/BE-AUD-02-query-export-verification.md) | Search/export/verifier | AUD-01, identity/RBAC |
| 03 | [BE-NOT-01 — outbox](../modules/notification/BE-NOT-01-transactional-outbox.md) | T2 enqueue, T3 worker, simulator | transfer, identity |
| 04 | [BE-FRD-01 — rules/review](../modules/fraud/BE-FRD-01-rules-and-review.md) | Rule evaluator, flag, review API | AUD-01, transfer/limit, identity |
| 05 | [BE-ADM-01 — admin collaboration](../modules/admin/BE-ADM-01-fraud-audit-integration.md) | Permission/provider/caller integration tests | AUD-01/02, FRD-01, Đạt’s admin/identity |
| 06 | [BE-AUD-03 — retention/legal hold](../modules/audit/BE-AUD-03-retention-legal-hold.md) | Policy/hold/checkpoint design, disposable DB tests | AUD-01/02, ADM-01 |
| 07 | [BE-AI-01 — feasibility](../modules/fraud/BE-AI-01-fraud-feasibility.md) | Synthetic-data study and recommendation | FRD-01 baseline; does not block MVP |

Có thể viết mọi spec trước. NOT-01 không cần chờ AUD-02; FRD-01 component không cần chờ NOT-01. AI feasibility độc lập sau khi có baseline rule. Thứ tự chỉ là dependency gợi ý, không phải lịch.

## 4. Workflow mỗi task

### 4.1 Đọc nguồn, ghi baseline

Đọc task spec, overview, `docs-common`, OpenAPI và [Backend conventions](../00.%20architecture%20%26%20conventions.md). OpenAPI quy định machine contract; `docs-common/` quy định requirements/data/business; Backend docs diễn giải cách thực hiện. Ghi source revision, FR/AC/TC, table owner, endpoints/ports, config keys và acceptance. Không sửa config chung ngoài phạm vi.

### 4.2 Viết proposal v0.1

Mô tả input/output, caller/provider, owner bảng, transaction, validation/errors, fail-open/closed, rollback, test và non-goals. Ghi decision ID, evidence source, tác động, fallback và reviewer. DTO/SQL/code snippets là proposal, không phải implementation đã tồn tại.

### 4.3 Code độc lập, giữ integration linh hoạt

Có thể triển khai domain rules, validators, hash primitives, persistence SQL và tests với fake trước review. Giữ `api/` nhỏ, dùng DTO immutable, không expose entity/repository. Trước owner review, không khóa tên/kiểu/error của integration contract. Nếu contract tạm cần test, giới hạn ở fixture/adapter test-only; đổi sang contract đã review trước integration.

Dependency thiếu:
- Fake chỉ trong test; production không giả success.
- PostgreSQL Testcontainers cho transaction/lock/constraint; fake không chứng minh DB behavior.
- FK target chưa tồn tại: fixture schema disposable để test component; đề xuất migration ordering; không tạo production table giả.
- Flyway version chốt tại integration theo baseline mới nhất; không sửa migration đã chạy.
- Common utility thiếu: đề xuất Phương; không tự copy crypto/security/error framework.

### 4.4 Review trước shared integration

Gửi spec, sample I/O, test evidence và decision IDs cho owners. Review names/types/error/status, table ownership, migration, lock order, permissions và duplicate work. Làm component work trong lúc chờ; không tự ghi Accepted khi chưa có reply. Nếu public API/money behavior đổi, chờ review trước khi bật shared runtime.

### 4.5 Verification/report

Chạy unit/contract/component trước, integration thật sau. Chạy targeted tests trong task spec từ `digitalbanking-backend/`; Windows `gradlew.bat`, Git Bash/Linux `./gradlew`. Report ghi revision, runtime versions, profile, command, expected, actual, exit, evidence path và limitations. Không ghi secrets/PII. Test rollback/roles phải test behavior thật, không chỉ kiểm tra file/resource tồn tại.

### 4.6 Handoff và audit findings

Task chỉ đóng theo acceptance và phạm vi evidence rõ. Nếu audit base/config/contract phát hiện mismatch, ghi delta/decision, sửa đúng file liên quan và chạy regression. Không xóa nội dung chung, không biến docs-only thành Verified code, không commit/push nếu chưa có lệnh riêng.

## 5. Cấu hình và safety

[deployment/configuration.md](../deployment/configuration.md) là reference config; không lặp config trong từng spec. Test dùng synthetic data và DB disposable. Purge thật, destructive migration, external gateway thật, model API và Cloud không được chạy chỉ vì có spec. Runtime `banking_app` không dùng quyền migrator. Audit runtime không UPDATE/DELETE/TRUNCATE.

## 6. Known limitations và controls

| Task | Giới hạn/failure | MVP decision/evidence | Upgrade trigger |
|---|---|---|---|
| Audit writer | Global advisory lock tuần tự C1/C2; tail truncation không phát hiện thiếu anchor | Chấp nhận chain đơn cho MVP; đo lock-wait/latency/pool với benchmark. Chưa benchmark, không claim TPS/capacity | Vượt SLO nhóm chốt: ADR partition/batch/single-writer; thiết kế lại hash semantics/atomicity/verifier |
| JCS | Java/Jackson không mặc định tuân RFC 8785 | Dùng common implementation đã review + independent vectors; thiếu provider thì hash integration Blocked | Đổi provider: compatibility/hash version plan |
| Fraud caller input | Evaluator không biết count query có đúng không | Unit test chứng minh evaluator với fixture; caller correctness chỉ Verified bằng transfer/PostgreSQL integration | Caller/query/timezone/status thay đổi thì rerun integration |
| Notification | Gửi có thể duplicate; retries hữu hạn nên có thể thất bại; polling thêm delay | Chấp nhận bounded at-least-once attempts; không đảo tiền; đo duplicate/exhaustion/backlog/delivery latency; không claim exactly-once | Duplicate/delay vượt tiêu chí: provider idempotency hoặc queue redesign theo ADR |
| Audit purge | Checkpoint/archive sai làm retained chain invalid hoặc mất evidence | Purge disabled shared/demo/prod; chỉ dry-run hoặc disposable Testcontainers; independent review | Chỉ xem xét sau restore drill, security/ops approval và signed-off runbook |
| Cross-module proposal | DTO/API chưa được owners chấp nhận gây rework | Code core với fake; integration port nhỏ, linh hoạt; ghi decisions trước ghép | Owner review xong mới đóng contract |

Đây là trade-offs, không mặc định là bugs. Benchmark chưa chạy; không TPS/SLO nào được khẳng định. Thay đổi hash contract phải có compatibility/version plan.

## 7. Proposal register

| ID / task | Proposal của Thịnh | Reviewer đề xuất |
|---|---|---|
| AUD-P01..05 / AUD-01 | Event/validation, hash v1, propagation, global-lock trade-off, JCS boundary | Phương, Vũ, Đạt |
| AUD-Q01..03 / AUD-02 | Snapshot export, C2 fail-closed, verifier | Đạt, Phương |
| NOT-P01..04 / NOT-01 | Lease/dedupe schema, security alert, OTP boundary | Vũ, Đạt |
| FRD-P01..05 / FRD-01 | Snapshot, frequency proposal, optional savepoint, auto-block | Vũ, Đạt |
| ADM-P01..03 / ADM-01 | Provider/caller split; giữ admin owner Đạt | Đạt |
| AUD-L01..03 / AUD-03 | Dry-run, hold lock, checkpoint recovery | Đạt, Phương |
| AI-P01 / AI-01 | Offline synthetic evaluation, không inference trong T2 | Vũ, Đạt, Phương |

Tất cả Proposed; reviewer là người được đề xuất, chưa có approval.

## 8. Quy ước file và handover

Mỗi task có spec + verification report cùng thư mục. Report lúc tạo ghi `Planned/Not verified`; cập nhật actual chỉ sau run. AI task/report riêng dưới fraud; không tạo runtime module thứ 12.

Phiên sau đọc workflow và task 01 nếu bắt đầu implementation. Chỉ thực hiện task được yêu cầu; không tự chạy cả danh sách.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Workflow docs-first/code-first, 7 tasks không gắn tuần |
| 0.2 | 11/11/2026 | Known limitations, evidence boundaries và safeguards |
| 0.3 | 11/11/2026 | Giữ core linh hoạt; API proposals nhỏ trước owner review |
