# Phiếu chạy task FE-FND-02-DEV-REVIEW / 16-11 run-02

## 1. Định danh và đầu vào

Ngày 16/11/2026, Asia/Bangkok; Codex. Yêu cầu chat: “đối với contract mới thì fe có cần chỉnh sửa những gì”. FE nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; working tree lúc bắt đầu có tracker và phiếu run-01 cùng ngày chưa commit. Nguồn main đã fetch ở run-01: d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49. Mức chứng minh: đối chiếu source/tài liệu; không thực thi API/browser.

Nguồn đã đọc: FE README/tracker/roadmap, FE-RUN/template; FE-03 mục 4–12; API-CLIENT-01/API-IDEM-01/API-RETRY-01/API-CACHE-01; auth standards và design CUST-TRANSFER. Đối chiếu Modules contract mục 1/8/11/13/14 với 07/OpenAPI v1 (không đổi theo run-01); source src/api/client.ts, features/transfers/TransferPage.tsx và stores/transfer.ts. Legacy chỉ dùng xác định phần code cần chuyển, không làm nguồn nghiệp vụ đích.

## 2. Kiểm tra sẵn sàng

Contract mới là [05. Modules contract v0.1](https://github.com/TNoLisme/cloude/blob/d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49/digitalbanking-docs/docs-common/design/05.%20Modules%20contract.md), nội bộ BE. HTTP vẫn theo 07/YAML; chữ ký/DTO Đề xuất chưa phải quyết định để FE đổi payload. Không có lựa chọn mới về version, endpoint hoặc policy từ yêu cầu phân tích này. Baseline FE/mock v2 đã duyệt vẫn giữ; BE/Security/runtime chưa chốt.

## 3. Thay đổi và kịch bản

Chỉ bổ sung phân tích ảnh hưởng và liên kết tracker. Không sửa code/UI/mock/contract/feature/standards/design; không commit/push. Bảng sau là mapping tới công việc đã có, không tự thêm nghiệp vụ hoặc đánh dấu đã triển khai:

| Phần FE | Phần code/kiểm thử cần làm theo kế hoạch | Task / phụ thuộc |
|---|---|---|
| Shared client/session | Chuyển client Bearer/refresh/checkedLogin legacy sang metadata cookie session, CSRF, Problem Details; chọn version rõ ràng. Types/mock v2 đã có, UI/client chưa migrate | FE-FND-02, FE-01-01; GAP-05/07 vẫn mở cho integration |
| Dữ liệu wire | Dùng Money string/ID string theo types mới và utilities đã duyệt; tách DTO UI khỏi shape legacy | FE-FND-02-DATA đã duyệt utility; nối feature thuộc các task migration |
| Chuyển tiền/step-up | Luồng mới nhận 428/challenge trước create, verify rồi retry cùng intent/key/body; bỏ sự phụ thuộc vào nhánh legacy PIN/create rồi AWAITING_OTP trong flow đích | FE-03-01, GAP-01/02/08; không triển khai PIN đích khi thiếu contract |
| Retry/idempotency | Giữ key/payload theo cùng ý định, khóa gửi lặp; 409 IN_PROGRESS theo Retry-After; conflict không gửi lại mù; timeout chưa đồng nghĩa FAILED | FE-03-02; GAP-08/09 và MOD-OI-04 |
| Tra cứu/kết quả | Lookup theo reference thay transferId legacy; phân biệt PENDING/Unknown/COMPLETED/FAILED; kết quả và số dư authoritative từ server, refetch account/limits/history khi kết quả xác nhận | FE-03-01/02, FE-04-01, FET-04–08 |
| Quyền/PII/trace | Hiển thị projection masked theo quyền; route/action policy từ matrix đã chốt; lưu trace phù hợp khi replay, không dùng DTO nội bộ hoặc thông tin fraud nội bộ cho UI customer | GAP-07, MOD-OI-08; FE-FND-02/03, FE-REL-01 |
| Audit/notification errors | Export chỉ xử lý file trả thành công; xử lý Problem khi server từ chối. Trạng thái gửi notification không thay kết quả transfer đã xác nhận | FE-07-01/GAP-12, FE-03, FET-11/AC-025; audit persistence/notification worker là trách nhiệm BE |
| Mock/tests và readiness | Thêm kịch bản 428/retry/replay/in-progress/timeout, session/ownership/masking và lỗi theo schema đã rõ; tích hợp từng phần sau khi BE sẵn sàng | FE-REL-01/02/03 và cổng roadmap; mock không chứng minh transaction/ledger/audit |

Các nội dung trên phần lớn đã nằm trong tracker mục 2.1 và FE-03. Contract mới không buộc FE gọi trực tiếp module Java, dựng transaction T1/T2, tính ledger hay tự quyết định policy.

Các điểm cần owner chốt trước implementation phụ thuộc:

- MOD-OI-04: 07 replay 201/422 nhưng YAML có 200 replay; thống nhất HTTP/schema, TTL/purge và quy tắc retry. TTL >=24h còn Đề xuất; không tự đặt thời gian FE hoặc coi hết TTL là an toàn gửi giao dịch mới.
- MOD-OI-08: correlation trong body replay so với header request mới; quyền staff/read model/ownership. Cần API/FE/Security và owner read model thống nhất, không lấy gate quyền common làm matrix cuối.
- MOD-OI-01/02/03/06: step-up với tổng ngày đồng thời, điều kiện tạo FAILED, reconciliation PENDING, ngưỡng/config/date. BE/BA/QA/Architect/owner liên quan chốt đúng nguồn; FE chưa thêm heuristic hoặc rule dựa trên đề xuất.
- GAP-05: backend main vẫn v1, FE/mock v2 riêng; không đổi quyết định FE hoặc coi cookie/version đã thống nhất từ contract module nội bộ.

## 4. Đối chiếu hoàn thành

Lệnh thực tế: git status --short; đọc nguồn FE bằng Get-Content/rg; git show origin/main:"digitalbanking-docs/docs-common/design/05. Modules contract.md", trích mục 8/11/14 bằng Node; rg source TransferPage/stores để nhận diện flow đang có. Quan sát client còn kiểm accessToken/Bearer và refresh; store còn stage otp, nextTransferStage nhận AWAITING_OTP; TransferPage gửi pin, lookup transferId. Các request này trong preview hiện đi qua fixture adapter đã duyệt, không phải bằng chứng chúng là API refactor đã tích hợp.

Lệnh đọc design ban đầu dùng TRANSFER-CREATE.md không tồn tại; đã dùng rg --files xác định đúng CUST-TRANSFER.md rồi đọc lại. Không dùng kết quả đọc sai path làm evidence đạt. Không chạy lại FE/BE tests vì không sửa code hoặc contract. Không có API/browser/invariant evidence mới.

## 5. Tài liệu và review

Tracker ghi thêm kết quả mapping và nguồn MOD-OI; không đổi tiêu chí task, đóng GAP hoặc tự duyệt nguồn mới. Các phiếu/approval cũ giữ nguyên. Feature/design/guide không đổi vì không triển khai hành vi mới. Những phần chưa rõ chỉ được mô tả là phụ thuộc phải chốt trước khi làm.

## 6. Kết quả lần chạy

Đã xác định ảnh hưởng FE: tiếp tục migration đã có trong lộ trình; bổ sung tham chiếu MOD-OI-04/08 và test/readiness liên quan. Không có breaking HTTP delta mới được chốt để đổi DTO/client ngay trong lần này. Mức chứng minh chỉ đọc source; phiếu/tracker chưa commit trên nền 6bceb5d. Thứ tự tiếp theo giữ roadmap: shared client/session mock trong phần rõ → auth/account → transfer/intent/recovery theo các GAP đã chốt → API/browser theo readiness.
