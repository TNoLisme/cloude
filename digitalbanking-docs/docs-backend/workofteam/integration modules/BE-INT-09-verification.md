# BE-INT-09 — Kiểm chứng Admin Maker/Checker

| Mục | Phạm vi |
|---|---|
| Trạng thái | **Verified** trong phạm vi BE-INT-09. **228/228 test liên quan / 20 class** có evidence PASS theo từng class qua hai lượt cuối, 0 failure/error/skip còn lại. Lượt cuối 55/55 PASS; nghiệm thu mới 30/30 và upgrade/lock V23 1/1 PASS. |
| Ngày | 20/11/2026, Asia/Ho_Chi_Minh |
| Revision | Kiểm thử trên `phuong/integration@f82993355bcfb5a4afebdf3a17d4b5d4b84986d1` với thay đổi BE-INT-09. [Manifest nguồn SHA-256](evidence/BE-INT-09/source-manifest.sha256) ghi checksum của 770 file build/source/test/resources/OpenAPI tại thời điểm kiểm thử. |
| Requirement | SRS AC-028, FR-033/050/051, UC-011/013; API `07` §§5.6/6.5/6.6/8.5/8.8, OpenAPI; schema `04`; Modules contract §§5.3/10/11.1/12.1; DEC-INT-018. |
| Environment | JDK 21.0.13, Gradle 8.14.3, PostgreSQL Testcontainers `postgres:16-alpine`; canonical V1–V23, Hibernate validate; HTTP server cổng ngẫu nhiên. Bộ nghiệm thu mới dùng `banking_app` và `banking_migrator` riêng; observer chủ DB chỉ tạo dữ liệu tổng hợp/tiêm lỗi/đối chiếu. |
| Procedure | Từ `digitalbanking-backend`: `sh '../digitalbanking-docs/docs-backend/workofteam/integration modules/evidence/BE-INT-09/verify.sh'`. Chỉ các class trực tiếp hoặc dependency bị ảnh hưởng, qua `--tests` theo DEC-INT-007. |
| Artifacts | [Lệnh, revision và kết quả từng lượt](evidence/BE-INT-09/results.json), [JUnit hồi quy](evidence/BE-INT-09/junit-regression.xml), [JUnit chạy lại](evidence/BE-INT-09/junit-followup.xml), [Gradle hồi quy](evidence/BE-INT-09/gradle-regression.log), [Gradle chạy lại](evidence/BE-INT-09/gradle-followup.log). JUnit giữ case/result/failure metadata; bỏ stdout/properties để không đóng gói dữ liệu xác thực. |

## Đối chiếu và sửa lỗi

Provider và workflow đã tồn tại từ BE-ADM-INT-01; không tạo adapter giả hoặc workflow thứ hai. Hai lỗi chỉ lộ khi kiểm tích hợp bằng role thực và thứ tự DB:

1. V22: Identity `PESSIMISTIC_READ`/`refresh(...PESSIMISTIC_READ)` trên role gây `42501 permission denied for table role`. Test Admin cũ dùng chủ DB nên không phát hiện. V23 thêm hàm khóa đọc chỉ có EXECUTE cho runtime, giữ SELECT-only catalogue. Test hai connection chứng minh thay đổi status role bị `55P03` đến khi caller nhả khóa; UPDATE trực tiếp của runtime vẫn `42501`; PUBLIC không EXECUTE, search_path cố định; tham số được bind.
2. Probe PostgreSQL ở Audit append phát hiện `23514 Business write after audit` cho proposal role và limit. Payload JPA được đánh dấu không updatable; decision update flush trước complete idempotency/Audit. Probe giữ trên tám bảng business cho cả proposal/approve/reject; sau sửa, các luồng commit được mà không có DML sau append.

Chỉ thêm V23; V1–V22 không sửa. Upgrade V22→V23 đối chiếu checksum/history cũ và re-run migration không phát sinh thay đổi. Không đổi API REST hoặc policy nghiệp vụ; đồng bộ `04`, Modules contract và DEC-INT-018. Công thức hash trong `04` được sửa theo quyết định sẵn có DEC-ADM-015, khớp writer và `design/01`.

## Ma trận nghiệm thu HTTP/PostgreSQL

Các hàng dưới kiểm cả `ROLE_CHANGE` và `LIMIT_CHANGE` trong `AdminMakerCheckerPostgreSqlIntegrationTest` (30 ca).

| Ca | Expected / Actual đã quan sát |
|---|---|
| Maker khác Checker | Maker có cả CHECKER vẫn nhận 409 ROLE_CONFLICT khi tự duyệt; approval/pending config chưa áp dụng. Checker khác Maker áp dụng thành APPLIED. |
| Maker binding | Đổi key hoặc payload sau verify → 409 STEP_UP_CHALLENGE_MISMATCH; proof vẫn VERIFIED/chưa consume; retry request gốc tạo đúng một proposal. |
| Checker binding | Đổi note, APPROVE→REJECT, approval hoặc payload đã lưu → 409 mismatch; dùng proof của Checker khác → 404 AUTH_CHALLENGE_NOT_FOUND. Approval/provider chưa đổi, proof chưa consume. |
| Replay | Cùng key/payload replay JSON/status đã lưu; decision có Idempotent-Replayed=true, không cần consume proof lần hai. Một REQUESTED và một APPROVED audit. Key cũ với payload khác → 409 conflict; key mới trên approval terminal → 409 already decided. Thứ tự member JSON không dùng để so sánh nghĩa response. |
| Hai Checker | Hai HTTP worker độc lập bắt đầu cùng barrier: đúng một 200 và một 409 APPROVAL_ALREADY_DECIDED; một assignment/version bump/session revoke hoặc một ACTIVE limit; một proof consume, một audit APPROVED. |
| Stale version | Hai proposal capture cùng version; duyệt proposal đầu rồi dùng proof đã verify của proposal sau → 409 ROLE_CONFLICT; proposal sau vẫn PENDING, claim rollback, proof chưa consume và không có APPROVED audit. Không sửa version bằng SQL để tạo ca stale. |
| Provider fault | Trigger AFTER UPDATE ở revoke session Identity hoặc activate Limit phát SQLSTATE 58000 sau mutation. HTTP 500 INTERNAL_ERROR; approval/provider/session/version/challenge/idempotency đều quay lại trạng thái trước. Sequence ngoài rollback xác nhận đã chạm fault. Gỡ trigger rồi retry cùng key/proof → một APPLIED. |
| Audit fault | BEFORE INSERT cho REQUESTED/APPROVED/REJECTED phát 58000 → 500 AUDIT_WRITE_FAILED. Không orphan approval/config ở proposal; quyết định giữ PENDING, provider chưa áp/reject, proof và claim rollback; không audit nghiệp vụ lỗi. Retry cùng key/proof sau gỡ trigger thành công đúng một lần. |
| Pha DB cuối | Probe Audit cùng TX chặn mọi DML tiếp theo trên approval/idempotency/challenge/app_user/user_role/user_privilege/session/limit_config. Proposal, approve, reject của cả hai loại PASS. |

Provider Identity, Limit, Audit và Notification adapter là production code trong cùng ứng dụng/datasource/transaction manager. SMS receiver HTTP là simulator ngoài transaction, chỉ giữ OTP tạm trong bộ nhớ để verify qua HTTP Identity; không lưu OTP/token/contact vào evidence. Session được seed để cô lập approval; login end-to-end đã thuộc BE-INT-08. Test hồi quy module cũ có fixture OTP hoặc Audit seam riêng; không dùng chúng thay bằng chứng production-provider của 30 ca mới.

## Lệnh, kết quả và phạm vi

`results.json` giữ cả lượt thất bại và thành công. Lượt đầu không khởi động được test worker vì daemon cũ sau cập nhật JDK; dùng `--no-daemon` chạy được. Lượt RED xác định hai lỗi production; hai assertion replay ban đầu so byte JSON được sửa sang so JSON tree. Lượt chẩn đoán thu SQLSTATE 42501/23514 trực tiếp từ PostgreSQL. Lượt sau sửa đã qua khóa role/pha Audit, nhưng fixture 08006 khiến Hikari loại connection và che mã lỗi trong lúc rollback; fixture lỗi SQL xác định được dùng 58000 theo mapper hiện hành. Lượt nghiệm thu cuối đạt 30/30 ca mới, 1/1 migration V23 và 21 kiểm tra kiến trúc tự chạy cùng JUnit engine.

Hồi quy chọn Admin HTTP/step-up/proposal/idempotency/persistence/OpenAPI, Identity role provider, Limit mutation, Audit C2/C3, năm class migration bị thay đổi kỳ vọng version và hai class architecture. Đây là các dependency trực tiếp của sửa JPA/Identity/V23; không chạy toàn backend. Lượt hồi quy có **221/228 PASS**, 7 lỗi kỳ vọng: 4 assertion số migration vẫn V22 trong ba class; 3 assertion OpenAPI cũ đòi mọi parameter dùng shared ref dù query `status` của API-ADM-02 được khai báo inline hợp lệ. Sửa số version và kiểm schema inline query, vẫn bắt shared refs cho header/path. Chạy lại đúng bốn class lỗi (kèm architecture engine): **55/55 PASS**, BUILD SUCCESSFUL. Hợp nhất kết quả mới nhất của từng class: **228/228 / 20 class PASS**, không tuyên bố một lượt 228 ca toàn xanh. Không đổi runtime sau hồi quy, chỉ sửa kỳ vọng test; dừng sau PASS theo DEC-INT-007. `results.json.verifiedCoverage` chỉ rõ nguồn evidence của từng class; 22 migration V1–V22 có SHA-256 trùng HEAD.

## Giới hạn

Chỉ nghiệm thu luồng Admin của BE-INT-09 trên dữ liệu tổng hợp. Giá trị policy OI-API-05 vẫn cần cấu hình theo quyết định nghiệp vụ trước triển khai; không tự thêm ngưỡng. SMS ngoài tiến trình được mô phỏng, không phải nhà cung cấp triển khai thực tế. Attempt vận hành REQUIRES_NEW được giữ khi business rollback; không coi đó là thay đổi nghiệp vụ. Không suy từ test này sang recovery Transfer BE-INT-10, FE BE-INT-11, D1–D5/phát hành BE-INT-12, đa tenant, Audit retention/export hoặc `GET ApprovalDetail.result` lịch sử ADM-OI-11.
