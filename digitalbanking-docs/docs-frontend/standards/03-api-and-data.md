# API, dữ liệu và xử lý lỗi

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-API |
| Phiên bản / ngày | 1.0 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Nguồn và client

Nguồn là [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] và [[digitalbanking-docs/openapi/README.md]]. Base path /api/v1. OperationId trong feature phải có trong YAML baseline.

## API-CLIENT-01 — Transport tập trung

shared/api sở hữu base URL, credentials include, header CSRF/correlation, timeout, JSON/Problem Details, xử lý 204 và download stream. Feature gọi function theo operationId. Sinh types từ YAML hiện hành bằng công cụ được chốt tại ADR-001, file generated có quy trình regeneration/check trong CI.

Mọi request có cancellation, bỏ qua kết quả response cũ sau khi user/filter/session đổi. Abort một request mutation chỉ hủy chờ phía browser, trạng thái server vẫn cần query lại. Timeout theo 07, giá trị hiện 10 giây cho API thường. API env và build contract theo local guide.

## API-DATA-01 — Kiểu dữ liệu và định dạng

| Kiểu | Giao ước FE |
|---|---|
| ID | String xuyên suốt route/DTO/query, tránh ép sang Number |
| Money | Decimal string đúng schema Money, payload có 2 chữ số thập phân |
| Tính toán tiền | Integer minor units bằng BigInt hoặc decimal implementation được review, parse/format tập trung |
| Instant | ISO UTC có Z, chuyển timezone khi hiển thị |
| LocalDate | Giữ ngày nghiệp vụ, tách khỏi Instant |
| Account number | String giữ số 0 đầu, chỉ masked khi hiển thị dữ liệu trả về |
| Unknown enum | Fallback nhãn trạng thái chưa hỗ trợ, giữ dữ liệu an toàn và vô hiệu action chưa hiểu |

Một số DTO cho Money nullable phải hiển thị chưa có dữ liệu. Kiểm tra max length/precision theo YAML. Tránh đi qua floating-point khi parse, tính và serialize tiền. Formatter Intl phải được dùng trên representation giữ đủ precision. Unit test boundary, decimal và ID vượt MAX_SAFE_INTEGER.

## API-PAGE-01 — Filter và phân trang

API mới dùng page 0-based, size tối đa 100 và sort whitelist theo endpoint. UI có thể hiển thị trang bắt đầu từ 1 rồi chuyển đổi tập trung. Đổi filter/size/sort reset page về 0. Query key chứa user/scope/filter/page/sort và loại bỏ giá trị undefined theo chuẩn chung.

From inclusive và to exclusive theo 07. Khoảng ngày UI được chuyển thành hai mốc UTC theo timezone cấu hình. Giữ asOf từ response khi hiển thị snapshot. Filter URL chỉ giữ field đã phân loại an toàn theo FE-SEC-03.

## API-ERR-01 — Lỗi và thông báo

Parse Problem Details với code, correlationId, retryable và errors khi có. Map code sang tiếng Việt trong một catalog chung, feature thêm nội dung ngữ cảnh qua interface catalog. Với response HTML/JSON hỏng hoặc mất kết nối, tạo lỗi transport có mã nội bộ và correlation nếu có.

| Nhóm | Hành vi mặc định |
|---|---|
| 400 validation | Map errors.field vào form theo allowlist, lỗi còn lại ở ErrorPanel |
| 401 | Phân biệt lỗi OTP/challenge với hết session theo code, FE-AUTH-01 |
| 403 | Hiển thị từ chối, kiểm tra CSRF/session theo contract, giữ correlation |
| 404 | Resource không tìm thấy, action quay lại danh sách |
| 409 | Phân nhánh theo code, in-progress theo API-RETRY-01, conflict yêu cầu refresh/đối chiếu |
| 410 / 423 | Challenge expired/locked, FE-AUTH-03 |
| 422 | Lỗi nghiệp vụ, transfer FAILED nếu body xác nhận, dữ liệu số dư/hạn mức refetch |
| 428 | Điều phối step-up, giữ payload/key gốc |
| 429 | Tôn trọng Retry-After, hiển thị thời gian có thể thử lại |
| 5xx / timeout | Phân biệt read retry và mutation chưa rõ kết quả |

Không đưa raw stack/detail nhạy cảm vào UI/log. Hiển thị thông báo fallback an toàn khi code chưa biết.

## API-RETRY-01 — Retry và kết quả chưa rõ

GET có backoff giới hạn, tôn trọng Retry-After và dừng khi logout/unmount/đổi filter hoặc lỗi không retryable. Mức đề xuất FE là tối đa 3 lần cho lỗi mạng/5xx/429 hợp lệ, phải được chốt cùng chính sách nguồn trước implementation.

Mutation chỉ retry qua flow biết tính idempotent. POST /transfers giữ nguyên key và payload theo API-IDEM-01, xử lý 428 hoặc IDEMPOTENCY_IN_PROGRESS theo contract. Admin create/review chưa có bảo đảm idempotent đầy đủ thì tra cứu approval/flag trước khi gửi lại, theo GAP-08.

PENDING chỉ poll GET theo reference với khoảng chờ/backoff có giới hạn. Đề xuất FE tối đa 60 giây trên trang đang mở rồi chuyển action kiểm tra thủ công, chờ FE/BE review. Reconcile phía server có thể dài hơn polling UI.

## API-IDEM-01 — Transfer intent

Khi xác nhận form, tạo một intent chứa payload chuẩn hóa và key ngẫu nhiên không có PII. Khóa payload khi gửi/step-up/unknown. Retry cùng ý định dùng cùng key/body, kể cả sau verify step-up.

409 IDEMPOTENCY_CONFLICT yêu cầu đối chiếu, 409 IN_PROGRESS chờ Retry-After. 428 có thể xảy ra trước khi transfer tồn tại. 201/202/422/body được diễn giải theo 07 và schema, chỉ COMPLETED server xác nhận mới thành công. Idempotent-Replayed chỉ xác nhận replay.

Nếu có reference thì query getTransfer. Chưa có reference và reload mất key/intent theo GAP-09, UI dẫn tới lịch sử để xác minh trước khi tạo intent mới. Key/payload chưa được chốt lưu bền trong browser. Server TTL từ nguồn, UI không tự coi hết TTL là giao dịch chưa xảy ra.

## API-CACHE-01 — Cache và invalidation

Query key bắt đầu bằng userId và capability scope của session hiện tại. Account/history/limit cập nhật sau transfer xác nhận terminal. Approval mutation invalidate list/detail và dữ liệu đích khi applied. Fraud review invalidate flag list. Logout/đổi user hủy request, xóa cache/intent.

Thông tin tài chính lấy authoritative server, stale data có asOf và action refresh. Secret/challenge proof theo FE-SEC-03. CacheTime/staleTime cụ thể được chốt theo loại dữ liệu khi implementation, ghi tập trung tại shared/api.

## 2. Phụ thuộc

[[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] giữ GAP-05/08/09 và task FE-FND-02. Session/step-up tại [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md]].

## 3. Hiện thực Money/ID ngày 14/11/2026 — được FE duyệt

FE-FND-02-DATA cung cấp `digitalbanking-frontend/src/shared/lib/money.ts` và `id.ts`. Các type Money/ApiId tham chiếu types sinh từ YAML mới, không dùng schema legacy. `moneyToMinorUnits(unknown)` nhận đúng Money wire format và trả BigInt minor units; `minorUnitsToMoney(bigint)` serialize đúng hai decimals, từ chối kết quả âm/vượt precision. `formatMoney(Money)` dùng Intl trên phần nguyên BigInt, ghép hai số thập phân và đơn vị VND để không mất precision. Input sai scale/kiểu/leading zero/whitespace không được tự sửa hoặc làm tròn.

`isApiId/parseApiId` chỉ nhận string theo pattern ID, không đi qua Number hoặc tự thêm giới hạn số nguyên DB ngoài schema. Đây là kiểm tra định dạng; không xác nhận ID tồn tại hay quyền sở hữu. Money 0.00 hợp lệ ở cấp schema; rule amount chuyển tiền >0 thuộc feature/BE, không áp cho số dư hoặc hạn mức nullable. Caller phải phân biệt null/missing trước khi gọi formatter; helper không tự thay chúng bằng 0.

Các utility chưa thay renderer/DTO/client cũ của preview. Unit tests tại shared/lib kiểm tra biên schema, cộng decimal, overflow, format và ID vượt MAX_SAFE_INTEGER. Bằng chứng/thực trạng task tại [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DATA/2026-11-14-cfde9ad/run-02.md]]. Đây không phải kết quả tích hợp API hoặc nghiệm thu toàn bộ FE-FND-02.
