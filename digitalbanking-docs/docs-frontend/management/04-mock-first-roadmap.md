# Lộ trình FE: duyệt UI với mock rồi tích hợp từng phần

| Thuộc tính | Giá trị |
|---|---|
| Mã / ngày | FE-ROADMAP / 13/11/2026 |
| Căn cứ | Yêu cầu người phụ trách FE trong chat ngày 13/11/2026 |
| Trạng thái | UI/toolchain và phạm vi đợt đầu đã được người phụ trách FE chốt trong chat 13/11/2026; người dùng đã cho phép fixture chỉ xem và khóa thao tác legacy thiếu contract |
| Nguồn trạng thái task | [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |

## 1. Nguyên tắc và căn cứ

Đây là cách chia nhỏ thực hiện kế hoạch FE-PLAN, không thay thế phạm vi hoặc tiêu chí của tài liệu gốc. Đọc nguồn sở hữu theo [[digitalbanking-docs/docs-frontend/README.md#3. Nguồn chính thức]]: SRS/BRD, stack, kiến trúc, API semantics, OpenAPI và security; sau đó feature/standards/design. Nếu nguồn thiếu hoặc mâu thuẫn, hỏi người phụ trách FE trước phần bị ảnh hưởng, ghi GAP và chờ quyết định. Không tự bổ sung nghiệp vụ để làm demo chạy được.

UI dùng mock API để người dùng xem và yêu cầu chỉnh sửa. Mock bằng MSW theo stack và FE-TEST-01; dữ liệu tổng hợp bám schema, đường dẫn, method và response đã có trong YAML. Kịch bản thành công/rỗng/loading/lỗi là dữ liệu phục vụ kiểm tra UI, không phải quyết định về hành vi backend. Các kịch bản chưa có căn cứ phải chờ chốt.

## 2. Thứ tự thực hiện

| Đợt | Task cha / task con mock | Đầu ra để xem và sửa UI | Điều kiện bắt đầu / câu hỏi cần chốt | Cổng tích hợp BE của phần đó |
|---|---|---|---|---|
| 0 | FE-FND-01/03 | Chuyển source tham chiếu `9e09a71` vào thư mục đích, scripts và môi trường preview | GAP-06/ADR-001: UI library, package manager, phiên bản và lộ trình toolchain; chốt phạm vi đợt đầu | Cài sạch, typecheck/build/checks theo package được chốt; tài liệu local đúng lệnh |
| 1 | FE-FND-02-MOCK, thuộc FE-FND-02 | MSW, fixture tổng hợp, mock toggle, hướng dẫn mở UI và tái lập trạng thái; unhandled API báo lỗi | Công nghệ từ đợt 0; mapping operationId/status/schema; không mock nghiệp vụ chưa rõ | Generated DTO/contract check đạt; mock tắt khi dùng API thật; readiness API phần sẽ tích hợp |
| 2 | FE-01-01, FE-02-01 | Login/bootstrap/logout, profile, account list/detail; vòng xem UI → góp ý → sửa → duyệt | GAP-05/07 trước hành vi session/quyền còn mâu thuẫn. FIDO thuộc FE-01-02 chờ GAP-04 | UI phần này được duyệt; FET-01/03/13 liên quan chạy BE thật; phiên/CSRF/ownership được xác nhận |
| 3 | FE-03-01/02, FE-04-01 | Transfer/review/challenge/result, history/filter/detail; các trạng thái có căn cứ trong contract | GAP-02/08/09: người nhận, binding step-up, same-key retry và recovery phải chốt trước phần phụ thuộc | UI được duyệt; FET-04–08; BE evidence idempotency/ledger/reconcile liên quan |
| 4 | FE-05-01/02, FE-08-01 | Limits, approval list/detail, role/privilege maker/checker | GAP-03/07/08 và quyền theo loại approval được chốt | UI được duyệt; FET-09/12/13; kiểm chứng maker khác checker và kết quả server |
| 5 | FE-06-01/02, FE-07-01 | Staff lookup, fraud review, audit/detail/export theo phạm vi đã chốt | GAP-03/11/12; lookup theo ID có căn cứ, search chưa có contract tiếp tục chờ | UI được duyệt; FET-10/11/13; quyền, masking và file export được kiểm chứng |
| 6 | FE-REL-01/02/03 | Regression, responsive/accessibility, build và handover | Các Must AC trong phạm vi không còn blocker hoặc có ngoại lệ được duyệt đúng owner; Cloud theo M4 | Evidence FET-01–15 phù hợp phạm vi; review, smoke và xác nhận bàn giao |

Đợt 2–5 đều có thể tích hợp phần đã duyệt khi BE phần đó sẵn sàng; không cần chờ tất cả màn FE mới kiểm tra API thật. Ngày hoàn thành và ước lượng chưa được chốt, không gán thời hạn khi chưa biết phạm vi/owner/readiness.

## 3. Cổng cho mỗi phần

1. Đọc nguồn và mở phiếu chạy task; liệt kê AC/FET và điểm chưa rõ.
2. Chỉ khi đầu vào rõ: triển khai UI/mock trong phạm vi task con, kiểm tra và cập nhật tài liệu.
3. Gửi cách mở UI, màn/kịch bản và evidence cho người dùng review. Ghi trạng thái Chờ review, mức Mock; phản hồi phải được sửa và kiểm tra lại trước duyệt.
4. Khi người dùng duyệt UI, ghi mốc UI/mock được duyệt; phần tích hợp vẫn mở theo FE-TEST-03.
5. Khi BE sẵn sàng, đối chiếu contract/FE SHA/BE SHA và GAP; kiểm tra phần đó bằng API thật và browser, sửa sai lệch theo nguồn đã chốt.
6. Sau checks/docs/evidence/review đạt, trình kết quả cho mốc merge cụ thể. Chỉ merge khi có xác nhận và không còn phụ thuộc chưa giải quyết trong phạm vi merge. Ghi PR/commit vào tracker sau khi thao tác thực sự thành công.

## 4. Quyết định của người dùng

| ID | Câu hỏi | Ảnh hưởng | Trạng thái |
|---|---|---|---|
| Q-01 / GAP-06 | Giữ Ant Design với stack đích, giữ toolchain cũ cho preview tạm, hay chuyển Tailwind/shadcn? Phiên bản chính xác/lockfile phải được ghi khi chốt | Dựng app, cài dependency, form/theme | Đã chốt Ant Design + pnpm/Vite 7/RHF/Zod trong chat 13/11/2026 |
| Q-02 | Đợt đầu làm nền tảng + auth/accounts, hay xem toàn bộ màn source cũ trước? | Phạm vi preview đầu tiên và cách chia task | Đã chốt toàn bộ màn có source cũ trước, rồi đồng bộ contract và bổ sung màn thiếu |
| Q-03 / GAP-01/02/08 | Với màn legacy thiếu/khác contract mới: chỉ hiển thị fixture và khóa thao tác chưa rõ, hay mô phỏng đầy đủ theo contract cũ riêng cho preview? | Mock runtime cho toàn bộ màn legacy | Đã chốt trong chat 13/11/2026: cho phép xem UI bằng fixture; khóa thao tác chưa có contract rõ ràng |

Quyết định Q-02 không đóng GAP-01 hoặc tự đưa register/PIN/recovery vào phạm vi đích. Các GAP khác giữ owner và điều kiện đóng trong tracker.

## 5. Vòng đời mỗi lần chạy

Theo [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]] và [[digitalbanking-docs/docs-frontend/templates/task-run-template.md]]. Mỗi lần kết thúc đều phải trả lời: phần nào đạt, phần nào chưa đạt/chờ quyết định, bằng chứng ở đâu và task nào được phép bắt đầu tiếp.

## 6. Phê duyệt ngày 14/11/2026

Người phụ trách FE đã duyệt FE-DOC-03 và UI/mock FE-FND-02-MOCK, cho phép push lên frontend trong chat ngày 14/11/2026. Phê duyệt này không đóng task cha, không nghiệm thu API thật và chưa cho phép merge main. [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-03/2026-11-14-c1760f1/run-02.md]].

## 7. Phạm vi tích hợp legacy được người dùng chốt ngày 14/11/2026

Giữ đăng ký/recovery/PIN/tạo khách tại quầy/nạp số dư/khóa và mở khóa tài khoản trong đợt tích hợp đầu. Chờ BE/BA bổ sung contract trước khi làm phần tương ứng; không tự mô phỏng mutation hoặc hoãn khỏi phạm vi. GAP-01 tiếp tục mở cho phần API/ngữ nghĩa/AC. Xem [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-01.md]].

## 8. Baseline session theo Security ngày 14/11/2026

Đã chốt SESSION_TOKEN, Path=/api, LOGIN_MFA cho FE/mock. Theo [[digitalbanking-docs/docs-frontend/decisions/ADR-002-session-security-contract-alignment.md]], phải thống nhất version/route rồi đồng bộ semantics → YAML → DTO/mock trước client phụ thuộc. Người dùng đã chọn giữ v1, tạo v2 riêng theo quy tắc 07; nguồn mới là 07a/YAML v2 (Draft FE/mock), không coi là backend v2 đã có. Runtime BE/Security/DB vẫn mở.

## 9. Bổ sung theo audit contract ngày 17/11/2026

Đã tìm thấy DEC-TRF-004/005/012 trên dev2/rework: thiết kế replay, retention/TTL, correlation và quyền đọc đã được lựa chọn trong nguồn đó; main/dev và FE chưa đồng bộ toàn bộ. [[digitalbanking-docs/docs-frontend/management/05-fe-be-contract-handoff.md]] ghi bằng chứng theo SHA, câu hỏi Q-01..05 còn cần FE–BE xác nhận và phân rã thực hiện ở mục 5.

Thực hiện phần shared client/session mock đã rõ trước; đưa semantics Transfer mới vào DTO/mock/flow sau khi xác nhận đúng bản contract và HTTP version giao FE. Customer ownership đã có rule không bị chặn toàn bộ vì staff scope còn thiếu. Các bước intent/replay → retention/trace/query → staff scope → API/browser có gate theo bảng handoff; không tự biến baseline branch thành FE/mock v2 đã duyệt hoặc nghiệm thu runtime. Không gán lịch hoàn thành khi các bên chưa chốt readiness.

Thứ tự chốt được người phụ trách FE yêu cầu ngày 17/11: lấy phản hồi BE tại handoff mục 4.1 trước, sau đó người phụ trách FE chốt phần UI/client tại mục 4.2. Task chỉ mở phần phụ thuộc khi cả input server và quyết định FE liên quan đã rõ; phần độc lập đã được duyệt vẫn được tiếp tục.

## 10. FE-01-01 preview auth v2 — 17/11/2026

Đã thực hiện phần độc lập login/OTP/bootstrap/logout trên v2 FE/mock. Người dùng chốt màn phiên chung mọi role, chưa nối nghiệp vụ và cho phép cờ fixture dev/test không secret/xóa khi logout. Đây là quyết định phạm vi preview, không đóng GAP-07 hoặc runtime v2. Checks unit/component/MSW đạt; browser bị chặn nên task còn Đang làm, chưa được duyệt UI/mock. Tiếp tục xác minh browser và nhận review trước tích hợp phần này; không tự chuyển sang các task business còn phụ thuộc phản hồi BE. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

## Quyết định thứ tự UI demo ngày 18/11/2026

Người phụ trách FE chốt trong chat: “chưa chốt hẳn UI vì tôi sẽ còn sửa nữa mà tôi muốn thực hiện xong các task có UI demo rồi mới thiết kế lại UI”. Các màn FE-01/02 và iconography hiện là demo để xem/chỉnh, không ghi UI/mock được duyệt hoặc khóa thiết kế. Tiếp tục task demo độc lập có contract; sau khi các task có UI demo hoàn tất mới gom vòng thiết kế lại/duyệt UI. Cổng tích hợp BE và merge ở mục 3 vẫn giữ nguyên: phần chưa được duyệt UI chưa tích hợp. FE-04-01-MOCK customer read-only là bước kế tiếp có thể mở; FE-03 phần phụ thuộc GAP-02/08/09 tiếp tục chờ. [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-03/2026-11-18-b9ba604/run-03.md]].
