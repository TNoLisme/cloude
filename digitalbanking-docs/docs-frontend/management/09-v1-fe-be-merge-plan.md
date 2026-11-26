# Kế hoạch ghép FE với backend v1 trên `dev2`

> Cập nhật 23/11/2026: M0 source merge `282de76`, phần độc lập M1 `371ea10`, nhánh tích hợp đã push. Nhật ký thực tế và vấn đề còn mở tại [[digitalbanking-docs/docs-frontend/management/10-fe-be-integration-log.md]] và [[digitalbanking-docs/docs-frontend/management/11-fe-be-integration-issues.md]]. Không coi các mốc đó là nghiệm thu tích hợp API thật.

| Mục | Giá trị |
|---|---|
| Quyết định FE | Người phụ trách FE chốt trong chat 22/11/2026: dùng bàn giao **v1 của BE** làm đích tích hợp/merge FE–BE. |
| Snapshot lập kế hoạch | `frontend@41209c4`; `origin/dev2@740ab5f` (đã fetch 22/11/2026). BE OpenAPI v1.1.0, base `/api/v1`. Cần ghim lại SHA khi chạy thực tế. |
| Trạng thái | Kế hoạch chờ review. Chưa merge source, chưa gọi API thật, chưa duyệt UI cuối hoặc nghiệm thu FET. |
| Nguồn | [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]], [[digitalbanking-docs/docs-frontend/management/08-common-dev2-gap-final-review.md]], [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]], [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]], [BE-INT-11 dev2](https://github.com/TNoLisme/cloude/blob/740ab5f/digitalbanking-docs/docs-backend/workofteam/integration%20modules/README.md) |

## 1. Ranh giới quyết định v1

FE sẽ phát triển adapter/client cho **contract backend đã bàn giao** tại `dev2` OpenAPI v1.1.0. FE 07a/YAML v2, generated types và mock v2 giữ như lịch sử prototype, được bật rõ ràng chỉ ở dev/test; không dùng chúng để định nghĩa response v1, không đổi lặng lẽ 07/YAML v1. Quyết định chọn version của người phụ trách FE không ký thay BE/Security đối với cookie, CSRF hoặc migration.

`dev2` dùng `DB_SESSION`/`LOGIN_NEW_DEVICE`; Security common và FE ADR-002 trước đây chọn `SESSION_TOKEN`/`LOGIN_MFA` cho v2. Trước khi nối auth thật, BE/Security cần xác nhận chính sách v1 sẽ dùng trong môi trường tích hợp và cách FE xử lý cookie/CSRF; ghi xung đột và kết quả ở GAP-05. Không làm endpoint v1 giả trả response v2.

## 2. Hai loại merge

1. **Ghép source để chuẩn bị tích hợp:** trên nhánh tích hợp cách ly từ `frontend`, merge `origin/dev2` sau khi ghim hai SHA. Kiểm giả lập ngày 22/11 thấy 13 file cùng sửa và ba vùng conflict: `.gitignore`, `AGENTS.md`, `digitalbanking-docs/docs-common/05. Technical stack.md`. Giải từng file theo thứ bậc common/BE và quyết định FE, ghi lý do; không tự lấy toàn bộ một bên nếu làm mất quy tắc bên kia. Commit ghép source có thể tồn tại mà chưa mở API thật.
2. **Merge chức năng/tích hợp vào nhánh đích:** chỉ sau khi từng lát có UI được người phụ trách FE duyệt, client khớp v1, API/browser critical flow và checks đạt, docs/evidence/review xong. Trình diff, FE/BE SHA, GAP còn mở trong phạm vi và kết quả cho người phụ trách FE xác nhận **mốc merge cụ thể**. Không lấy build/mock/BE verification riêng làm nghiệm thu FE.

Không dùng `git pull` trực tiếp trên `frontend` khi đang xử lý conflict hoặc khi chưa có điểm phục hồi. Sau ghép source, soát `AGENTS.md`, common 05/07/OpenAPI v1 và generated types; không để việc lấy BE đè quyết định FE đã ghi hoặc đưa v2 Draft thành contract runtime.

## 3. Thứ tự task và điều kiện qua cổng

| Mốc / task | Việc thực hiện theo v1 | Điều kiện vào | Kiểm tra/đầu ra |
|---|---|---|---|
| M0 · FE-REL-V1-SOURCE | Tạo nhánh tích hợp, merge `dev2`, giải ba conflict, lập inventory diff/version; mỗi task một commit riêng theo yêu cầu FE. | Working tree sạch, SHA ghim, đọc common/BE/AGENTS. | `git diff --check`, trạng thái conflict sạch; FE và BE build/check riêng theo thay đổi; phiếu evidence. **Chưa** tuyên bố API tích hợp. |
| M1 · FE-FND-02-V1 | Dùng YAML v1.1.0 của BE làm nguồn generated types và operation map; phân tách cấu hình mock v2 với API v1 thật, credentials/CSRF/Problem/204/headers theo 07 v1. | M0; BE/Security xác nhận GAP-05 cookie/CSRF v1; môi trường QA/seed synthetic từ BE-INT-11. | Schema/type check; test transport 401/403/428/409/422 và mock-off không fallback; cấu hình URL/HTTPS/CORS/cookie được kiểm browser. |
| M2 · FE-01-01-API + FE-02-01-API | Password/OTP, bootstrap/reload/logout; dashboard, profile, account list/detail self. FE customer sau login vào dashboard như người phụ trách FE đã chốt. | M1; UI phần tương ứng được duyệt; auth challenge v1 rõ. | FET-01/03/13 trên API/browser; CSRF, logout/reload, masked data, ownership, cross-user cache. FIDO/passcode chưa mở nếu GAP-04 còn thiếu. |
| M3 · FE-04-01-API + FE-05-01-READ-API | History list/detail/filter/page theo v1 và xem hạn mức customer. | M2; UI từng màn được duyệt; sort/filter/owner theo v1 đã đối chiếu. | FET-08 và lát đọc FET-09; URL/back/reload, timezone, page, 403/404, amount/asOf. |
| M4 · FE-06-01-READ-API + FE-05-02-READ-API + FE-06-02-READ-API + FE-07-01-READ-API | Staff exact-ID customer/account, approval read, fraud read, audit list/detail; nối vào session staff theo handoff FE đã chốt. | M1/M2; UI từng màn được duyệt; handoff GAP-07 M4 tại [[digitalbanking-docs/docs-frontend/management/12-m4-be-handoff.md]] và [[digitalbanking-docs/docs-frontend/management/13-m4-security-handoff.md]]. Trước khi nhập mỗi lát, phải có endpoint v1 runtime, response đúng contract và role/scope QA. Phone search, fraud decision, export vẫn tách. | FET-10/11/13 lát đọc; role, direct URL, 403/404, masking, cache đổi phiên và audit/correlation. Lát thiếu runtime/evidence bị loại khỏi M4 merge, không mở rộng quyền để vượt blocker. |
| M5 · FE-03-01/02-API | Recipient/submit, 428 challenge, same-key replay, result/unknown/recovery; không gửi lần hai khi kết quả chưa rõ. | M2; UI transfer duyệt; GAP-02/08/09 liên quan được chốt, BE giao vectors/202 support và evidence. | FET-04–07; 201/422/409/428, Retry-After, ledger/idempotency/recovery BE evidence, browser timeout/reload. |
| M6 · FE-05/08/06/07 mutation/export | Limit/role maker-checker, fraud review, audit export; chỉ từng thao tác có contract/quyền/scope được chốt. | GAP-03/07/08/11/12 tương ứng; UI từng phần duyệt. | FET-09–13; maker khác checker, step-up binding, forbidden, file mẫu/413/download, audit. GAP-01/04/10 mở riêng theo common và quyết định phạm vi. |
| M7 · FE-REL-01/02/03 | Regression, responsive/accessibility, build/deploy, review và handover cho phạm vi được duyệt. | Các Must AC trong phạm vi đạt hoặc ngoại lệ do owner chấp thuận; không còn blocker thuộc mốc merge. | FET-01–15 phù hợp phạm vi, FE/BE SHA, CI/QA browser, defect list, PR review và xác nhận merge cụ thể. |

M2–M4 là những lát đọc nên ưu tiên sau M1. M5/M6 có thêm GAP và BE invariant, không đẩy chúng vào cùng một commit với M1. Nếu BE chỉ có một subset sẵn sàng, merge theo subset đã được duyệt; tracker/task cha giữ phần còn lại mở.

## 4. Checklist trước mỗi task tích hợp

1. Mở phiếu FE-RUN trước khi sửa, ghi FE SHA, BE SHA, contract `info.version`/hash, working tree, môi trường/seed synthetic, màn/AC/FET và GAP.
2. So sánh operationId, path, request/response, status/header, cookie/CSRF, role/ownership giữa common 07, OpenAPI v1.1.0 và BE runtime. Mâu thuẫn gửi đúng owner; không sửa canonical contract bằng giả định FE.
3. Chuyển client/UI của **lát được duyệt** sang API v1 thật; giữ mock dev/test tách rõ, thiếu handler báo lỗi. Không đưa credential/PII/raw idempotency key vào log/evidence.
4. Chạy checks liên quan và browser critical flow trên môi trường BE thật; đối chiếu report BE invariant nếu là tiền/approval/audit. Ghi expected/actual, lỗi, ảnh/screenshot thật nếu có và SHA.
5. Cập nhật feature/design/standards/guide, tracker, phiếu và review. Chỉ khi đủ FE-TEST-03 mới trình người phụ trách FE diff/kết quả để xác nhận merge phần đó; không gộp “source merged” với “function accepted”.

## 5. Những quyết định vẫn cần owner

- **GAP-05:** chọn v1 là quyết định đích FE; BE/Security xác nhận cookie `DB_SESSION`, Path/cờ, `LOGIN_NEW_DEVICE`, CSRF và cách xử lý mâu thuẫn Security common. Đóng GAP chỉ sau runtime/browser v1 được chứng minh cho lát auth.
- **GAP-07:** ma trận quyền và nguồn capability staff; BE single-bank exact-ID đã có nhưng SRS/Security còn mâu thuẫn Auditor.
- **GAP-02/03/08/09/11/12:** chỉ chặn các lát recipient/search/mutation/recovery/fraud/export tương ứng. Replay/TTL đã có quyết định dev2; cần vector/version và UX, không chốt lại từ đầu.
- **GAP-01/04/10:** phạm vi legacy, trusted-device enrollment và notification/retention UI theo bản rà common/dev2; không được đưa vào v1 bằng code cũ khi chưa có BR/FR/API.

Lộ trình này không gán ngày hoàn thành hoặc tuyên bố BE G3/FE UAT đã đạt. Sau mỗi mốc, cập nhật tiến độ thực tế trước khi mở mốc phụ thuộc.
