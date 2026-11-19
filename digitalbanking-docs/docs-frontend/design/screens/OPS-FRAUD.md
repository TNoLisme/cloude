# OPS-FRAUD — Cờ rủi ro

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | OPS-FRAUD |
| Trạng thái UI | Có source UI, có phần chờ cập nhật |
| Route trong code | /staff/risk |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/staff/ReadOnlyPages.tsx dòng 24–288](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/staff/ReadOnlyPages.tsx#L24-L288)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

BankingLayout staff → Heading Cờ rủi ro → Alert info giải thích cảnh báo → Panel filters/table → Tải thêm → Drawer Chi tiết cờ rủi ro.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| FRAUD-NOTICE | Alert info showIcon | Cờ rủi ro là cảnh báo theo quy tắc..., mô tả đánh giá best-effort | Tĩnh |
| FRAUD-RULE | Select allowClear | Quy tắc, Tất cả quy tắc, LARGE_TRANSFER/HIGH_FREQUENCY | Map ruleId |
| FRAUD-TRANSFER | Input | Transfer ID, UUID tùy chọn | Map transferId |
| FRAUD-TIME | Datetime-local | Từ/Đến, to > from | UTC filter |
| FRAUD-ACTIONS | Button primary + Button | Lọc / Cập nhật | Apply/refetch |
| FRAUD-TABLE | Table | rowKey flagId, scroll.x1000, pagination=false | Xem mở drawer |
| FRAUD-COLUMNS | 6 cột | Thời gian VN, Quy tắc, Phiên bản, Giao dịch, Lý do cảnh báo, Chi tiết | Read-only |
| FRAUD-MORE | Button | Tải thêm, loading next page | Cursor |
| FRAUD-DRAWER | Drawer width520 | Chi tiết cờ rủi ro | Clear selected khi đóng |
| FRAUD-DETAIL | Descriptions một cột | flagId/transferId/ruleId/ruleVersion/reason/detectedAt và allowlist chung nếu có | Null thành —, date format |

## 4. Style và kích thước

Panel/filter/table/flow-alert/Drawer theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. Alert margin-bottom24. Chi tiết Xem là Button link size small. Drawer global max-width100vw.

## 5. Trạng thái và tương tác

Pending Table loading. Empty text Chưa có dữ liệu phù hợp. Query error dùng ErrorPanel retry. Dedupe flagId, selected row mở Drawer. Source chưa có action review decision, severity/status filter hay link reference mới.

## 6. Responsive

Table vùng cuộn1000, filter full-width tối đa767 và sidebar/drawer theo staff layout. Không chuyển sang mobile cards. Drawer detail tối đa viewport.

## 7. Accessibility và nội dung

Warning có text giải thích rủi ro. Labels đủ theo source, status của table render dữ liệu hiện có. Cần QA keyboard/vùng cuộn/drawer row context và long reason.

## 8. Cập nhật khi triển khai

FE-06-02 triển khai endpoint và DTO mới, severity/status/reference, review action theo permission. GAP-11 chốt phạm vi Option B. File này được cập nhật phần đọc và phần review khi code tương ứng xuất hiện.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Form và dialog review ALLOW/BLOCK/ESCALATE/CLOSE

### Severity/status filter và trạng thái review theo contract mới

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
