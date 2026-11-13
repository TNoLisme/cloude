# Kế hoạch triển khai FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-PLAN |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Bổ sung 13/11/2026 | Lộ trình bổ sung FE-DOC-03 được người phụ trách FE duyệt ngày 14/11/2026; UI/mock duyệt riêng, các gate API thật vẫn mở |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA, người điều phối |

## 1. Căn cứ và lịch

Kế hoạch theo [[digitalbanking-docs/docs-common/10. Master timelines.md]], phạm vi tại [[digitalbanking-docs/docs-frontend/management/01-scope-and-feature-map.md]]. M1–M6 là milestone chung. Đợt FE là thứ tự công việc, ngày và người nhận việc ghi trong tracker khi phân công.

## 2. Đầu ra và cổng chuyển tiếp

| Đợt / milestone | Đầu vào | Công việc / đầu ra | Điều kiện qua cổng |
|---|---|---|---|
| A / M1 | Phạm vi FE, SRS, API, security | FE-DOC-01, FE-DOC-02, FE-FND-01, review phạm vi/design/ADR/GAP | Chốt đầu việc tiếp theo và các phụ thuộc contract |
| B / M2 | A, quyết định UI/toolchain | FE-FND-02, FE-FND-03, FE-01-01, FE-02-01, shared client và app shell | Session/account chạy backend đích, test đạt |
| C / M2–M3 | B, Money/step-up thống nhất | FE-03-01, FE-03-02, FE-04-01 | Same-key retry, timeout, 428 và số dư được kiểm chứng cùng BE |
| D / M3 | B, API staff/approval và privilege | FE-05-01, FE-05-02, FE-06-01, FE-06-02, FE-07-01, FE-08-01 | Role/ownership/maker-checker được kiểm chứng |
| E / M4 | C–D theo phạm vi duyệt | FE-REL-01, FE-REL-02, UAT/evidence/bundle/accessibility | AC có bằng chứng, blocker có quyết định được duyệt |
| F / M5–M6 | M4 xác nhận, môi trường Cloud | FE-REL-03, Cloud smoke và handover | Deep-link/API/HTTPS smoke và demo được xác nhận |

FE-01-02 chạy sau GAP-04. Phần UI độc lập có thể chuẩn bị với mock bám contract, mức chứng minh ghi trong tracker. FE-06-01 có thể tra cứu theo ID trong khi search chờ GAP-03.

## 3. Phụ thuộc

Theo yêu cầu người phụ trách FE ngày 13/11/2026, các đợt implementation được chia thành UI/mock → người dùng review/chỉnh sửa/duyệt → tích hợp BE của phần đã duyệt → kiểm chứng và xác nhận merge. Xem [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]]. Các cổng API thật và nghiệm thu ở bảng trên vẫn áp dụng; mock không đóng task tích hợp. Mỗi lần chạy dùng [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]].

~~~mermaid
flowchart TD
  A[Review baseline và GAP] --> B[Khôi phục source và toolchain]
  B --> C[Shared API, session, UI]
  C --> D[Tài khoản]
  D --> E[Transfer và history]
  C --> F[Staff, audit, fraud]
  C --> G[Limit và role approvals]
  E --> H[UAT và evidence M4]
  F --> H
  G --> H
  H --> I[Cloud smoke và handover M5-M6]
~~~

## 4. Phân công và thay đổi

FE sở hữu implementation/UI và docs FE. BE sở hữu contract/runtime, QA cùng FE review acceptance, BA chốt quy tắc nghiệp vụ/phạm vi. Người điều phối chốt ngày, assignee và ngoại lệ.

Cuối đợt review GAP, evidence và liên kết nguồn. Thay đổi phạm vi Must/Should cần BA/người điều phối chấp thuận, ghi ảnh hưởng milestone trong tracker. Các điều chỉnh sau refactor được thực hiện theo mục 2.1 của tracker.

## 5. Nguồn thao tác

- [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].
- [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]].
- [[digitalbanking-docs/docs-frontend/guides/02-demo-and-handover.md]].

## 6. Cập nhật design trong quá trình triển khai

FE-DOC-02 bổ sung file màn hình trước khi push bộ docs. Khi từng task UI bắt đầu, dùng [[digitalbanking-docs/docs-frontend/design/README.md]] để chọn file. Task tạo UI mới điền phần mô tả đang trống, task sửa UI cập nhật component/CSS/trạng thái/evidence cùng PR. FE-FND-03 và các feature chỉ hoàn thành khi nội dung design tương ứng đã đồng bộ với code được review.
