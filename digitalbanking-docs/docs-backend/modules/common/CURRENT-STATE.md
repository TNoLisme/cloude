# Common — Final report về trạng thái triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-COM-FINAL-REPORT
> **Phiên bản / thời điểm:** v1.0 / 2026-11-12T20:01:01+07:00
> **Owner module:** Phương
> **Baseline runtime:** `5bf84a6d4ca887084daa0803aae55ed088bdf01a` (task07–08 đã commit); source được đối chiếu với worktree
> **Trạng thái tổng thể:** Nền tảng common đã triển khai và kiểm chứng local; **chưa nghiệm thu toàn module/release/Cloud**
> **Review / sign-off:** Chưa review; đây là snapshot tổng hợp của implementer

## 1. Kết luận về state hiện tại

Common có **28 Java files trong 4 package**: config 14, context 4, crypto 4, error 6. Các primitive/config/error/context/HTTP security/observability cần dùng chung đã có implementation. Chưa có production adapter identity hoặc Java implementation trong 10 module nghiệp vụ còn lại; migration hiện chỉ tạo schema banking.

Trong **8 task implementation**, **5 Done** trong phạm vi được công bố (01–04, 06), **3 Blocked** (05, 07, 08). Ngoài ra có 2 task tài liệu DOC-01/02 đã hoàn thành nội dung/verification tài liệu. Không cộng hai loại này thành bằng chứng hệ thống đã chạy end-to-end. Tất cả vẫn chưa có reviewer sign-off được ghi nhận.

Kết quả runtime gần nhất: **203 tests pass, 0 failure/error/skip, 23 suites** ở [verification 08](BE-COM-IMP-08-verification.md), bao gồm PostgreSQL 16 và packaged demo JAR. Đợt này chỉ rà soát tài liệu/source/links và đọc XML hiện có, **không chạy lại test suite**. Không coi kết quả common là bằng chứng D1–D5, full security scan, financial correctness, immutable audit hoặc Cloud readiness toàn hệ thống.

Quyết định release giữ **NO-GO**: cần consumer integration và quality gate 07, RC đã được chấp nhận, owner hạ tầng/reviewer, URL/môi trường/secret references và bằng chứng W7/W8. Từ “final” trong tên report là tổng hợp công việc đã làm đến snapshot này, không phải tuyên bố mọi dependency đã hoàn tất.

## 2. Hạng mục đã thực hiện — task và verification

| Task | Nội dung đã làm | Kết luận hiện tại | Verification |
|---|---|---|---|
| [BE-COM-DOC-01](../tasks/BE-COM-DOC-01.md) | Thiết kế chi tiết common, traceability, class/error/security/crypto/context/test catalog trước code | Hoàn thành tài liệu; không runtime evidence | [DOC-01](BE-COM-DOC-01-verification.md) |
| [BE-COM-DOC-02](../tasks/BE-COM-DOC-02.md) | Phân xử nguồn, giữ rotation/ngưỡng còn mở, chuẩn bị baseline triển khai | Hoàn thành tài liệu; không phê duyệt release policy | [DOC-02](BE-COM-DOC-02-verification.md) |
| [BE-COM-IMP-01](../tasks/BE-COM-IMP-01.md) | AppProperties/nested defaults/validation, Clock UTC/business zone, đồng bộ baseline cấu hình | **Done — scope cấu hình/thời gian**, chờ review | [01](BE-COM-IMP-01-verification.md) |
| [BE-COM-IMP-02](../tasks/BE-COM-IMP-02.md) | 46-code ErrorCode, RFC9457 Problem/writer/advice/extension allowlist, safe MVC/security/SQLSTATE formatting | **Done — scope error layer**, không tự điều phối rollback/retry | [02](BE-COM-IMP-02-verification.md) |
| [BE-COM-IMP-03](../tasks/BE-COM-IMP-03.md) | Correlation/request IDs, request snapshot/MDC/scope, outer filter/error/async cleanup, proxy trust disabled | **Done — scope HTTP/context**, chưa propagation qua bảng nghiệp vụ | [03](BE-COM-IMP-03-verification.md) |
| [BE-COM-IMP-04](../tasks/BE-COM-IMP-04.md) | AES256-GCM/HMAC/SHA/constant-time, RFC8785 JCS/raw duplicate detection, PII masking, crypto fail-fast | **Done — primitive/caller-fixture scope**, không storage/consumer/rotation toàn hệ thống | [04](BE-COM-IMP-04-verification.md) |
| [BE-COM-IMP-05](../tasks/BE-COM-IMP-05.md) | Production chain, SPI SessionAuthenticator, CSRF/CORS/headers/permission gates, bounded body reader/token buckets, tests | **Blocked — 5/9 AC Verified, 4 Blocked**; thiếu session/identity/cookie/quota integration và môi trường liên quan | [05](BE-COM-IMP-05-verification.md) |
| [BE-COM-IMP-06](../tasks/BE-COM-IMP-06.md) | Safe JSON stdout/completion, HTTP timer/histogram/export, operational endpoint protection, HealthController app/DB | **Done — common/local**, 9/9 AC Verified; business signals/C3 fallback/Cloud còn bàn giao | [06](BE-COM-IMP-06-verification.md) |
| [BE-COM-IMP-07](../tasks/BE-COM-IMP-07.md) | Ma trận W3/W4/W5, real PG error pipeline/rollback probe, contract subset, 5 ArchUnit rules, regression | **Blocked — 5/10 Verified, 5 Blocked**; thiếu consumer/state/schema để kiểm đủ end-to-end | [07](BE-COM-IMP-07-verification.md) |
| [BE-COM-IMP-08](../tasks/BE-COM-IMP-08.md) | Demo Secure/CORS config, packaged JAR start/restart/negative config, roles/Flyway, readiness/runbook/NO-GO/handoff | **Blocked — 6/10 Verified, 4 Blocked**; W6 phần độc lập đạt, chưa RC/Cloud/owner/W7/W8 | [08](BE-COM-IMP-08-verification.md) |

Task/report cũ ghi kết quả của revision và môi trường khi thực hiện; không sửa counts lịch sử thành 203 hoặc tự thêm sign-off. [Tracker](../tasks/README.md) giữ AC/dependency và điều kiện Done. Đợt tổ chức tài liệu này không tạo task implementation thứ 9 và không thay trạng thái của bất kỳ AC nào.

Các commit runtime liên quan: 01 `00a5823`, 02 `8b7298a`, 03 `991472b`, 04 `b44e927`, 05–06 `87427ce`, 07–08 `5bf84a6`. Các thay đổi README/TKCT/final report hiện tại chưa commit.

## 3. Năng lực có thể bàn giao ngay và giới hạn sử dụng

| Nhóm | Sẵn có | Consumer cần làm / giới hạn |
|---|---|---|
| Cấu hình/thời gian | AppProperties, UTC Clock, businessZoneId, validated HTTP/crypto config; local/test/demo profiles | Owner tự áp policy và transaction/business date theo nguồn; jobs config không có nghĩa scheduler đã chạy |
| Context | Filter trước security, validated correlation/server requestId, bind/restore MDC/ThreadLocal, async-safe HTTP completion | Owner bind worker và persist đúng IDs trong challenge/transfer/ledger/audit/notification/job; ThreadLocal không tự propagate |
| Error | 46 public codes, safe Problem, flattened checked extensions, MVC/servlet/security writer, DB generic mapping | Owner phân loại context sau rollback/retry; common không đoán outcome tiền hoặc ghi audit. SQLSTATE không thay idempotency/business rule |
| Crypto/JCS/PII | Một active AES/HMAC key, strict RFC8785, masking helpers và safe failures | Normalize/payload/hash protocol/storage/authorization do owner; chưa rotation, name policy/IPv6 mở rộng, audit scanner hay blind-index migration |
| HTTP security | Chain/SPI/CSRF/CORS/headers/body 65536B/token buckets; Secure=true/explicit origins cho demo | Chưa identity adapter, real session/cookie/CSRF storage, normalized username/challenge quota, owner permission/ownership/maker-checker; buckets process-local, chưa distributed |
| Logging | JSON stdout, completion route template/status/latency/error/trace, masked internal user ID, global allowlist | Generic message/arguments/stack/unknown MDC bị bỏ; richer coded owner events/C3 security fallback chưa tích hợp. Technical log không thay audit |
| Metrics/health | HTTP count/latency/histogram, low-cardinality tags; actual operational HealthController và public probe | Không emit business metrics/outbox count giả; authenticated access cần identity thật; chưa Cloud scraping/alerts/retention |
| Quality/readiness | PostgreSQL primitive failures qua HTTP handler, probe rollback/no generic retry, architecture rules, demo JAR smoke/negative config | Probe/harness không thay tiền/audit/session consumer; same-JAR restart không phải rollback accepted prior release hoặc Cloud deployment |

Xem [README hướng dẫn sử dụng](../README.md), [TKCT](../TKCT.md), [runtime backend](../../../../../digitalbanking-backend/README.md), [readiness/runbook](../../../deployment/common-readiness.md). Không dùng `logger.info` tùy ý hoặc test auth adapter như API nghiệp vụ production.

## 4. Dependency còn chặn hoàn thiện

Đây là backlog tích hợp thực tế, không phải các primitive cần viết lại. Mười module nghiệp vụ đều 0 Java tại snapshot; owner theo tài liệu 11, không suy trách nhiệm hạ tầng từ vai trò module/PM.

| Dependency / owner | Phần thiếu | Task/AC bị ảnh hưởng | Điều kiện tiếp tục |
|---|---|---|---|
| Identity/auth — Đạt | SessionAuthenticator production, user/customer/permission snapshot, active/expiry/revoke/last activity, OTP/challenge, CSRF persistence, cookie issuance; username/pre-auth quotas và token lookup protection | 05 AC01/02/04/05;07 AC02/03/05/06;08 AC03–05 | Có schema/services/migrations/adapter thật, synthetic sessions và positive/negative tests cookie/Bearer/CSRF/permissions/multiple sessions |
| Customer/account — Đạt | Storage ciphertext/blind index/last4, profile/account DTO mapper, ownership/permission và account state | 07 AC02/03/05/06;08 AC05 | Có persistence/migrations, owner-authorized mapper/query và tests cross-customer/PII channels |
| Transfer/limit — Vũ; ledger — Phương; account — Đạt | T0/T1/T2 money path, actual idempotency/payload/replay/resume, step-up binding/customer quota/usage, ledger unique/invariant và trace persistence | 07 AC02–06;08 RC/W8 gate | Có ports/services/schema/seed; chạy real T-CON/T-IDEM/T-LIM/T-STEP, no double debit/negative balance/ledger mismatch |
| Audit — Thịnh cùng identity/transfer owners | Recorder/API/hash-chain payload/locking/allowlist/scanner; C1/C2 same-TX rollback, C3 denial-preserving fallback/alerts và audit gap signals | 07 AC02/03/04/05/06;08 AC04/05 | Protocol hash_version được đồng bộ nguồn; actual audit schema/transaction/C3 integration và failure/tamper/security tests |
| Notification — Thịnh | Masked outbox persistence, challenge/transfer linkage, delivery/retry/job context, payload not leaking | 07 AC02/03/06;08 journeys/observability | Có outbox/worker/gateway, after-commit/retry tests và trace/log/data linkage |
| Fraud/admin — Thịnh/Đạt; phối hợp Vũ limit | Actual rule/flag/review/approval, permission/ownership/maker≠checker/step-up, audited state transitions | 07 AC03/05/06;08 operational journeys | Có services/schema, tests rule boundary/fail-open/review/approval/permission và audit integration |
| Hạ tầng/Cloud — chưa phân công; Vũ theo dõi quyết định | RC sau 07, URL/provider/private DB/TLS/ingress, secret reference versions/IAM, pinned image/scan/CI, monitoring/backup/rollback compatibility | 08 AC02–05; release gate | Chốt technical owner/reviewer theo 11 §8, đủ release candidate và môi trường; thực chạy W7/W8, không thay localhost cho Cloud |

Không có thao tác gửi thông báo cho các owner trong đợt này. Đầu mối/điều kiện tiếp tục là bàn giao trong tài liệu; không tự nhận review thay người khác hoặc phân công kỹ thuật hạ tầng cho Vũ.

### 4.1. Điểm còn mở ngoài việc chờ consumer

| Vấn đề | State / quyết định hiện tại | Đầu mối và thời điểm cần giải quyết |
|---|---|---|
| Rotation OQ-15/OI-COM-05 | Chưa protocol key version/ciphertext/HMAC index migration/KMS; previousKeys phải rỗng | Security/common + customer/account/DevOps trước multiple keys |
| Audit hash_version | Wording 04 §4.6.1/§9.4 và field set của 06 chưa đồng bộ; fixture không là persisted protocol mới | Audit/architecture trước ghi/verify chain thật; xem 04 DEC-07 |
| Policy ngưỡng | Lockout 5 lần/10 phút, transfer 30/phút/customer và các threshold nguồn còn Đề xuất; không tự trở thành policy release | Business/Security/owner tương ứng trước release |
| Metadata/masking | IPv6 fail-closed, chưa role-aware name masking; caller input ngoài biên không fallback plaintext | Owner PII/security chốt khi mở rộng dữ liệu/mapper |
| Log/metrics/audit | Global free-text suppression chủ động giảm chẩn đoán; chưa richer coded owner events/full C3 fallback/business counters/alerts | Audit/identity/owner/common khi tích hợp07; retention/rotation theo08 còn Open |
| Proxy/distribution | Forwarded headers none; chưa trust ingress/source IP/TLS/SPA CSP hoặc quota aggregate nhiều replicas | Security/hạ tầng + identity trước Cloud/scale |
| Release acceptance | D1–D5, full OpenAPI/scan/generated client/Cloud/accepted previous artifact rollback chưa đủ evidence | Owners/PM/hạ tầng, theo 09 exit gate và 08 runbook |

Đây là các giới hạn/policy thật cần giữ rõ, không gộp mọi việc còn lại thành “chỉ thiếu module khác”. Không thay đổi spec01–11 hoặc tự đóng open issue để báo module Done.

## 5. Rà soát TKCT sau implementation

| Nội dung cũ cần chỉnh | Cập nhật TKCT v1.11 | Lý do/code/report đối chiếu |
|---|---|---|
| README chứa cả thiết kế; class còn mang nhãn đề xuất và trạng thái chưa xác nhận | README thành hướng dẫn; TKCT giữ 7 mục/traceability, metadata và §1.4 phản ánh code | Baseline 5bf84a6 + report 01–08; không viết lại history |
| Inventory chưa đủ class config/context | Sinh cây đủ 28 files; thêm CryptoConfig/SPI/CSRF/headers/body/rate/encoder/HealthController/HttpRequestLog và visibility/trách nhiệm | Source thực trong 4 packages, không giả tên class thành implementation mới |
| “Common không có endpoint riêng” | Ghi rõ operational HealthController /api/v1/health và actuator security/status/fields, không business endpoint | HealthController/SecurityFilterChainConfig, reports 06/08 |
| Trace/PG testing vẫn mô tả như việc tương lai | Sửa attribute wiring đã có, filter order-102/-101/-100, completion trước scope cleanup; thêm mapping probePG và giới hạn consumer | Context/config source, reports 03/06/07 |
| Log fields có thể bị hiểu mọi logger đều emit đủ action/audit_id/free text | Phân biệt HTTP_REQUEST_COMPLETED với generic APPLICATION_LOG; allowlist/suppression và C3/owner gaps rõ | SafeJsonLogEncoder/HttpRequestLog; report06 decisions |
| Ví dụ invalid correlation có errors/PATTERN chưa implement | Ví dụ giữ Problem thực, bỏ errors[] giả định; optional field errors theo07 §4.1 | CorrelationAndRequestIdFilter/ProblemWriter; không đổi runtime hoặc API source |
| Audit fixture formula có thể bị hiểu đã chốt chain protocol | Đặt rõ fixture design/01, khôi phục gap hash_version/consumer và ownership | Report04 DEC-07, source04/06; không invent protocol |
| Test catalog có thể bị hiểu tất cả Planned hoặc đã đạt end-to-end | Giữ IDs/oracles; thêm §7.6 phân lớp common verified / consumer-môi trường chưa verified | Actual test classes, report 07/08, không nâng partial AC thành pass |
| Chưa ghi đầy đủ demo/nonblocking/limiter interface | Demo Secure/CORS, body buffer synchronous, SPI snapshot validation và limiter return/wait/capacity được ghi | application-demo.yml/AppProperties/security/filter/limiter source |
| Tham chiếu thiết kế trỏ README nay đã đổi vai trò | Current task instructions trỏ TKCT; overview sửa order/backlog và link đủ guide/design/final; giữ historical reports | Không đổi AC/task status. Sửa tham chiếu nhầm tasks/TKCT.md trong verifier02 về tasks/README.md chưa từng di chuyển |

Không sửa production code, migrations, runtime tests hoặc policy trong đợt này. Các chỉnh đường dẫn reports 01–04 đã có trước lượt được giữ; không đổi test results/commit/sign-off lịch sử hoặc tạo evidence/ mới. Verifier lịch sử không chạy để tránh ghi lại integrity artifacts của phiên cũ; chỉ sửa đúng đường dẫn AC index.

## 6. Căn cứ kiểm chứng và cách tiếp tục

Runtime baseline 5bf84a6 trùng source/config/test hiện tại khi bỏ qua .gitkeep. Lần chạy gần nhất là E03 report 08: clean check, 203 pass/0 fail/error/skip, 23 suites; report chứa artifact JAR/source hashes, packaged demo startup/restart và 10 negative config cases. Đợt này đọc XML hiện có và đối chiếu metadata, **không gán timestamp mới cho lần test cũ**.

| Kiểm tra trong đợt docs này | Actual |
|---|---|
| Source inventory/ownership | 28 common files, 4 packages, 0 business Java; migration tạo schema; không source change runtime |
| Task/report consistency | 01–04/06 Done scope;05/07/08 Blocked;2 DOC verification tài liệu; chưa reviewer sign-off |
| TKCT vs code | Class tree, signatures, filter order, health fields/roles, logger allowlist/demo/input boundaries được đối chiếu |
| Links/navigation | Đã kiểm 224 Markdown/wikilink references và section anchors trong guide/TKCT/final/tasks/overview: 0 lỗi; đường dẫn AC index của verifier là tasks/README.md |
| Regression trong đợt này | Không chạy; thay đổi chỉ docs/navigation. Baseline runtime suite 203 đã có trong report 08 |

Tiếp tục từng dependency ngay khi owner có implementation, không cần viết lại primitive hoặc đợi tất cả module cùng lúc. Khi runtime đổi: cập nhật TKCT/usage, chạy tests phù hợp, bổ sung verification theo đúng revision/môi trường, cập nhật final report/tracker. Chỉ đóng05/07/08 và module release khi mọi AC tương ứng đã Verified và có review/sign-off thực tế.

## 7. Định danh snapshot rà soát

Các hash dưới đây dành cho đối chiếu docs/source tại thời điểm viết; không thay snapshot runtime/historical evidence của từng task.

| File | SHA-256 |
|---|---|
| `digitalbanking-docs/docs-backend/modules/common/README.md` | `a40fcfba9018b63c377d98e6ed62e250a28241b5db4aab1040bd247cde389a3c` |
| `digitalbanking-docs/docs-backend/modules/common/TKCT.md` | `93d07bbe1a865075454ac6691c2c4d41535d908ba53a72a7729984ea506fcafe` |
| `digitalbanking-docs/docs-backend/modules/common/tasks/README.md` | `566019c70ee6ac756ab82a6d44de3a50f21b9e629f0882434231c39db4a2db4f` |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/HealthController.java` | `2e47bc8504ec877cbdc0eb42fa2f41b887cac42708ca3f7ca24eabe0831021c5` |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/SafeJsonLogEncoder.java` | `64407f50bd2bca2881bd40db469d2e18f1bd1929dedf38d24d89ff3922061e10` |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/context/HttpRequestLog.java` | `69b5d5e828ffb5b4edd35f97658faf598ce7d29691cf6cdd1191467ce678e4d1` |
