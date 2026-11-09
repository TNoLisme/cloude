# FE-03 — Chuyển tiền và phục hồi kết quả

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-03 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Customer chuyển giữa tài khoản cùng chủ hoặc đến tài khoản khác trong hệ thống. FE thu thập thông tin, review, step-up khi server yêu cầu, gửi đúng intent/key và theo dõi kết quả. Biên nhận thể hiện reference/status/amount/time/correlation, thông báo kết quả theo FR-041/043.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-013–026/034/038/041/043–046, UC-005–008/014–015, AC-006–014/018/022/025–026/033–034. Contract 07 §5/6/8.3 và [[04-transfer-and-ledger]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-IDEM-01 — Transfer intent]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-AUTH-03 — Challenge và step-up dùng chung]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-DATA-01 — Kiểu dữ liệu và định dạng]].

## 3. Màn hình và điều hướng

CUST-TRANSFER và CUST-RESULT, policy CUSTOMER/TRANSFER_CREATE. Form có các bước nhập → review → gửi/challenge → kết quả. History/detail liên kết FE-04. Recipient lookup/confirmation UX chờ GAP-02.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/CUST-TRANSFER.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-RESULT.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-TX.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Load account nguồn và limits hiện tại, chọn destination bằng accountId cùng chủ hoặc accountNumber.
2. Validate, chuẩn hóa Money/currency/description và hiển thị review.
3. Xác nhận tạo intent/key rồi createTransfer. Khóa form cho intent đang gửi.
4. Nếu 428 nhận TRANSFER_STEP_UP, verifyStepUp rồi retry cùng key/body theo 07 §6.2.
5. 201 COMPLETED mở receipt. 200 replay diễn giải status trong body. 202 PENDING là nhánh contract đề xuất, theo dõi bằng reference khi backend hỗ trợ.
6. Lỗi 422 hiển thị mã nghiệp vụ, lấy reference/failure data nếu Problem extension cung cấp. Lỗi 500 AUDIT_WRITE_FAILED hoặc timeout giữ trạng thái cần xác minh.
7. Có reference gọi getTransfer đến terminal hoặc đạt giới hạn poll theo API-RETRY-01.
8. Terminal refetch account/limits/history và cho xem receipt hoặc lỗi server xác nhận.

## 5. Trạng thái giao diện

| Trạng thái UI | Ý nghĩa và hành động |
|---|---|
| Editing/review | Có thể sửa, xác nhận tạo intent |
| Submitting | Chặn gửi lặp và đổi payload |
| Challenge | OTP/method theo server, intent giữ nguyên |
| PENDING | Server đã trả reference và đang xử lý |
| Unknown | Transport chưa xác định kết quả, giữ key/reference, action kiểm tra |
| COMPLETED | Receipt từ server |
| FAILED | Lỗi server xác nhận, xem detail và nguồn tiền đã refetch |
| Conflict/in-progress | Đối chiếu hoặc chờ Retry-After |

FE-03-01 thống nhất trạng thái giao dịch theo contract và flow step-up, FE-03-02 xử lý pending/unknown theo bảng công việc tiếp theo trong tracker.

## 6. Thành phần và dữ liệu

TransferForm, TransferReview, TransferIntentController, ChallengeDialog, TransferStatusPanel, Receipt. Query account/limits đọc, intent/key/payload/challenge memory theo API-IDEM-01. RequestHash do server canonicalize, FE giữ payload chuẩn hóa bất biến khi retry. Số dư UI cập nhật từ query server.

## 7. Tương tác API

| operationId | Method / path | Vai trò |
|---|---|---|
| listAccounts / getLimits | GET /accounts, GET /limits | Nguồn tiền/hạn mức |
| createTransfer | POST /transfers | Idempotency-Key và CSRF |
| verifyStepUp | POST /auth/step-up/verify | Verify challenge theo binding |
| getTransfer | GET /transfers/{reference} | Trạng thái và receipt |

Destination theo schema Destination và các nhánh oneOf trong YAML, amount Money, description tối đa 255. Retry transfer sau verify được server tìm challenge bằng key/hash theo 07, sensitive-action header thuộc FE-05/08.

## 8. Validation và thông báo

Amount dương, decimal đúng schema, source/destination hợp lệ, description theo maxLength. Validate cả own transfer source khác destination khi có ID. Số dư/limit được server kiểm tra lại.

Map INSUFFICIENT_BALANCE, LIMIT_EXCEEDED, ACCOUNT_INACTIVE, FRAUD_BLOCKED, STEP_UP_CHALLENGE_MISMATCH và idempotency codes trong error catalog. Ngưỡng step-up lấy quyết định server. 428 là trạng thái yêu cầu xác thực trước tạo transfer.

## 9. Trường hợp đặc biệt

Double click giữ một intent. Cùng key khác payload dừng và đối chiếu. Thay đổi destination/amount sau challenge tạo context mới sau khi giải quyết intent cũ. Timeout/abort có thể đã xử lý server. Reload khi chưa biết reference theo GAP-09, UI dẫn history và chặn lặp vô thức bằng thông báo xác minh trước gửi mới. Có reference dùng direct lookup. Transfer terminal giữ trạng thái server, failure/reconcile có evidence BE.

## 10. Tiêu chí nghiệm thu

- FET-04 cho own/intra-bank, validation/source/destination/limit và receipt.
- FET-05 chứng minh 428 trước create, verify và cùng key/body, sai/expired/locked challenge.
- FET-06 cho double click, replay, in-progress và conflict.
- FET-07 cho pending/timeout/reload, reconcile và audit failure.
- FE/QA gắn report BE cho một debit/credit, rollback, audit bền vững và AC-034.
- Notification failure được trình bày phù hợp kết quả transfer đã xác nhận theo AC-025.

## 11. Liên kết triển khai

FE-03-01 và FE-03-02 trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Nội dung điều chỉnh intent/key, step-up và reference được ghi tại mục công việc tiếp theo. Evidence gắn FET-04–07 và D2/D3 phía BE.

## 12. Vấn đề cần chốt

GAP-02 recipient lookup/UX, GAP-08 binding, GAP-09 reload khi chưa có reference. Ngưỡng/TTL cấu hình giữ trạng thái của 07. GAP-01 cho PIN legacy. Tất cả quyết định ở tracker.
