# FE-02 — Khách hàng và tài khoản

> 23/11/2026: có adapter GET v1 độc lập cho profile, account list/detail theo YAML v1.1.0, kiểm fixture và owner chỉ `customerId/status`. Adapter chưa nối UI/session hoặc gọi BE thật; GAP-05/ownership API test còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-V1-READ/2026-11-23-9832fa7/run-01.md]].

> 24/11/2026: FE-02-01-V1-API nối adapter vào dashboard customer sau auth v1. Ba GET self đã đạt HTTP QA với BE `740ab5f`, gồm foreign account 403; browser sau OTP còn bị chặn bởi sai lệch `AuthResponse.user.privileges` INT-10, nên chưa nghiệm thu UI/API end-to-end. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01-V1-API/2026-11-24-eb4e2e7/run-01.md]].

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-02 |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Mục tiêu và phạm vi

Customer xem hồ sơ, danh sách tài khoản, số dư và chi tiết masked. Account status được dùng để hướng dẫn lựa chọn nguồn tiền. Các màn staff đọc customer/account thuộc FE-06 và dùng lại component trình bày.

## 2. Nguồn và phụ thuộc

Yêu cầu tại [[digitalbanking-docs/docs-common/03. SRS.md]], API tại [[digitalbanking-docs/docs-common/07. API contracts.md]] và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]].

FR-008–012/039–040/052, UC-004, AC-005/006/023/024/029 trong SRS. Contract 07 §8.2 và [[03-customer-and-account]].

[[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-DATA-01 — Kiểu dữ liệu và định dạng]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-CACHE-01 — Cache và invalidation]], [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md#FE-UI-03 — Ma trận trạng thái]].

## 3. Màn hình và điều hướng

CUST-HOME, CUST-PROFILE, CUST-ACCOUNT theo route map. Account detail mở từ danh sách hoặc direct link và kiểm tra quyền qua API. Action chuyển tiền đưa accountId sang form FE-03 theo selection đã kiểm tra lại.

### Design UI của màn hình

- [[digitalbanking-docs/docs-frontend/design/screens/CUST-HOME.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-ACCOUNT.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PROFILE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-SETUP.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-CHANGE.md]].
- [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-RESET.md]].

Trạng thái đã có code và các vùng chờ cập nhật được ghi trong từng file. Quy tắc cập nhật tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 4. Luồng người dùng

1. Sau session load profile và listAccounts, các vùng có trạng thái loading/error độc lập.
2. Hiển thị tên/PII theo field trả về, số dư/available balance đúng field contract.
3. Mở account detail bằng getAccount.
4. Chọn chuyển tiền nếu account ACTIVE, FE-03 refetch/validate trước submit.
5. Refresh sau giao dịch terminal hoặc người dùng yêu cầu, giữ asOf khi có.

## 5. Trạng thái giao diện

Loading/empty/error/refetch theo FE-UI-03. LOCKED/CLOSED hiển thị nhãn và lý do nếu API cho phép, action nguồn tiền disabled. 403 cho ownership, 404 cho account không tồn tại. Null dữ liệu có nhãn chưa có thông tin.

## 6. Thành phần và dữ liệu

AccountSummaryCard, AccountDetailPanel, ProfilePanel và MoneyText/MaskedIdentity dùng shared. Query profile/list/detail phân biệt theo userId/accountId, số dư authoritative từ server. UI dùng DTO account và formatter tập trung.

## 7. Tương tác API

| operationId | Method / path |
|---|---|
| getMyProfile | GET /customers/me |
| listAccounts | GET /accounts |
| getAccount | GET /accounts/{accountId} |

Base /api/v1. DTO Profile/Account theo YAML, giữ accountId string.

## 8. Validation và thông báo

Path ID theo schema, account status dùng enum fallback. Tiền/PII theo shared formatter. Thông báo lỗi theo API-ERR-01, action refresh dùng read retry. Quyền customerId từ session và BE.

## 9. Trường hợp đặc biệt

Đổi tài khoản khi request cũ chưa trả phải bỏ response cũ. Back/reload direct link chạy session bootstrap. Account khóa ở tab khác hiển thị sau refetch và server validation khi tạo transfer. Logout xóa cache account.

## 10. Tiêu chí nghiệm thu

- FET-03 kiểm chứng profile/list/detail, ID lớn và Money string chính xác.
- Customer truy cập account khác bị từ chối theo AC-024, UI hiển thị đúng lỗi.
- Masked account/PII và account status theo AC-005/006/023.
- FET-14 kiểm chứng cards/table ở mobile và keyboard.

## 11. Liên kết triển khai

FE-02-01 trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. Dữ liệu account/profile dùng DTO hiện hành từ FE-FND-02. Test/evidence mới theo FET-03.

## 12. Vấn đề cần chốt

PIN cũ theo GAP-01. Các field profile chưa có API được giữ theo contract hiện hành. Quyền staff được đối chiếu tại GAP-07.

## 13. Bước độc lập FE-02-01 ngày18/11/2026

Sau push frontend@b9ba604 đã bắt đầu mock customer self theo v2 FE/mock: GET customers/me, accounts, accounts/{accountId}; fixture chỉ có customer tổng hợp, 3002 đại diện account tồn tại của customer khác (403), ID sai400, ID khác404. Không khẳng định mock chứng minh ownership backend. Component trình bày `src/features/accounts/v2/CustomerPanels.tsx` tái dùng Panel/Descriptions/kiểu bố cục cũ; đọc masked phone/email/account, string ID/Money và formatMoney. Không chuyển DTO legacy, không suy ra PIN, không gắn action transfer/history hoặc route/quyền staff. Cách mở UI đang hỏi người phụ trách FE vì quyết định trước là màn phiên chung, chưa nối nghiệp vụ. Parent và API thật còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-01.md]].

## 14. Preview riêng được chọn trong chat18/11

User chốt nguyên ý “Preview customer riêng; giữ màn phiên chung sau login (đề xuất)”. FE-02-01-MOCK nối preview dev/mock riêng tại `/customer?customerPreview=v2`, `/customer/profile?customerPreview=v2`, `/customer/accounts/:accountId?customerPreview=v2` với customer API client, query key theo fixture user và signal/cancellation. Fixture customer tổng hợp được seed rõ ràng chỉ trong preview; không phải session hoặc authorization BE. Màn phiên sau login của auth v2 không thay đổi. Runtime response được kiểm tra cấu trúc, Money/ID, masked account; unexpected fields bị loại; status lạ hiển thị fallback và không cấp action. Retry chỉ thủ công, không tự đặt backoff/budget chưa chốt.

Loading, empty, error,403/404 và direct detail có trong preview. Trong browser đã kiểm subset tổng quan/hồ sơ/detail/reload,403/404/empty/error và 1440/768/360; lần đầu reload khi Vite đang hot-reload từng quay về tổng quan, sau đó mở trực tiếp và reload giữ detail; nguyên nhân lần đầu chưa xác định, cần kiểm lại clean browser. Chưa kiểm chứng API thật, server ownership/audit, accessibility đầy đủ, liên kết transfer/history/PIN hoặc capability staff. Chờ người dùng review UI/mock; FE-02-01 và dependency tích hợp vẫn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]].


### Cập nhật ngày 18/11/2026 — CUSTOMER sau đăng nhập

Người phụ trách FE chốt trong chat: “tôi muốn sau khi đăng nhập thành công thì vào phần dashboard của customer, và trên màn hình chính của customer thì bỏ phần hiển thị trạng thái hoạt động của tài khoản ( đang hoạt động )”. Quyết định này thay thế phần giữ màn phiên chung sau login **đối với CUSTOMER**: trong preview auth v2, người dùng có userType và role CUSTOMER đến tổng quan `/customer?authPreview=v2`, dùng cùng controller phiên; không seed phiên customer riêng. Màn tổng quan không hiển thị status tài khoản; chi tiết vẫn đọc status theo contract. Vai trò khác giữ màn phiên chung; chưa chốt mapping GAP-07. Preview riêng vẫn tồn tại. Bản mock đang chờ review, không chứng minh runtime v2 hay quyền/ownership BE. Chi tiết: [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-03.md]].


### Run-04 ngày 18/11/2026 — trang chủ customer theo ảnh tham khảo

Theo yêu cầu người phụ trách FE trong chat 18/11, trang chủ mock dùng topbar thương hiệu/lời chào, sidebar trắng với icon và vùng active kiểu ảnh tham khảo. Menu chỉ có Trang chủ, Thông tin tài khoản và Hồ sơ của tôi, là các màn v2 đã nối trong FE-02-01. Nội dung chính hiển thị masked account, số dư khả dụng/asOf và thông tin tài khoản đọc từ GET /accounts; không dựng lãi suất, thẻ, vay, hóa đơn, khuyến mãi hoặc số liệu tổng hợp không có nguồn. Transfer/history có contract nhưng UI v2 thuộc task khác nên chưa mở menu ở đây. Dashboard tiếp tục không có nhãn trạng thái; màn chi tiết vẫn hiển thị status. Browser mock đã xem tại `/customer?authPreview=v2`; ảnh và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-04.md]]. Chờ review UI, không khẳng định API thật.


### Iconography customer mock ngày 18/11/2026

Đã áp icon navigation/feature/illustration theo cùng một catalog trên customer mock theo chốt người phụ trách FE. Icon chỉ hỗ trợ nhận diện; nội dung masked/account và trạng thái API giữ nguyên. Kết quả tại [[digitalbanking-docs/docs-frontend/evidence/FE-FND-03-ICONS/2026-11-18-b9ba604/run-01.md]].

## 15. Tích hợp lát đọc v1 ngày 24/11/2026

`AuthApiApp` đưa phiên có `userType=CUSTOMER` và role `CUSTOMER` vào `/customer`. `V1CustomerDashboard` gọi `getMyProfile`, `listAccounts`, `getAccount` qua `createV1CustomerReadApi` với cookie browser, query key chứa version/userId/customerId và hủy request/xóa cache khi phiên đổi. Loading/error/retry, danh sách rỗng, direct detail, 403/404 có trạng thái riêng. Trang chủ không hiện account status theo chốt FE 18/11; detail vẫn hiện status. Response detail v1 chỉ có `owner.customerId/status`, nên UI hiển thị mã khách hàng, không dùng tên chủ của DTO v2. Không nối transfer/history/limits/PIN, staff hay mutation ở lát này.

HTTP QA local trên BE thật đạt ba GET, masking, decimal string, 403 ownership và 404; component test kiểm route/UI. Browser chưa vào dashboard do INT-10: runtime BE cho mã permission vào `user.privileges` trái enum `PrivilegeCode` v1.1.0 (`CHECKER`). Không nới parser v1 khi chưa chốt; FE-02-01 vẫn Đang kiểm chứng, UI cuối/FE-TEST-03 còn mở.
