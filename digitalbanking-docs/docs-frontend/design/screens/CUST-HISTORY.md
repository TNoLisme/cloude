# CUST-HISTORY — Lịch sử giao dịch

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-HISTORY |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/transfers |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/transfers/HistoryPages.tsx dòng 39–252](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/transfers/HistoryPages.tsx#L39-L252)

[Source frontend/src/styles.css dòng 315–385](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L315-L385)

[Source frontend/src/styles.css dòng 525–562](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L525-L562)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

Heading Lịch sử giao dịch → Panel không title → Form.filters → HistoryList gồm ErrorPanel/loading/empty → desktop table hoặc mobile cards → Tải thêm ở panel-bottom.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| HISTORY-STATUS | Select allowClear | status, Tất cả trạng thái, options COMPLETED/AWAITING_OTP/EXPIRED/FAILED | Submit filter |
| HISTORY-FROM | Input datetime-local | Từ · giờ Việt Nam | Chuyển UTC khi submit |
| HISTORY-TO | Input datetime-local | Đến (không bao gồm), phụ thuộc from, validation to > from | Chuyển UTC |
| HISTORY-FILTER | Button primary | Lọc | Cập nhật filters state |
| HISTORY-TABLE | Table | rowKey transferId, pagination=false, scroll.x650, 4 cột | Link counterparty tới detail |
| HISTORY-DIRECTION | Direction icon/text | INCOMING mũi tên xuống nền xanh lá, còn lại lên nền xanh dương, name và masked account | Label Tiền vào/Tiền ra |
| HISTORY-TIME | Table cell | Thời gian · giờ Việt Nam | date formatter |
| HISTORY-AMOUNT | Money + prefix | Căn phải, dấu +/−, incoming xanh lá | Giá trị amount |
| HISTORY-STATUS-CELL | Status | Raw enum từ response | Đọc |
| HISTORY-MOBILE | TransactionCard Link | Bên trái icon/name/direction/time, bên phải amount/status | Toàn card đi detail |
| HISTORY-MORE | Button | Tải thêm, loading theo isFetchingNextPage | Cursor next page |

## 4. Style và kích thước

Bảng theo shared theme, direction flex gap10, icon32×32/radius8, name link13px/600, small11px. Amount nowrap/tabular. Incoming icon nền #EAF7EF và text #237A52, outgoing icon nền #EDF3FC/text primary. Filters và table-region theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 5. Trạng thái và tương tác

Pending Loading, không row và không lỗi EmptyState Chưa có giao dịch phù hợp. ErrorPanel retry next page nếu lỗi fetchNextPage, còn lại refetch. Rows nối các pages rồi dedupe transferId. Chỉ hiện Tải thêm khi hasNextPage và recent=false. HistoryList recent dùng chung trên CUST-HOME, limit5/COMPLETED và ẩn Tải thêm.

## 6. Responsive

Trên767 dùng desktop Table trong vùng cuộn650. Tối đa767 ẩn desktop, hiện mobile cards. Card padding17px 0/gap10/font11/border-bottom, name12, detail10, amount/status cột phải/gap6, tag10px/margin0. Filter field full-width theo shared CSS.

## 7. Accessibility và nội dung

Label cho filter, link counterparty/mobile card, status text và tiền kèm dấu. Input datetime-local theo browser. Kiểm chứng bàn phím/vùng cuộn, chuỗi dài và thiết bị nhỏ tại FET-08/14.

## 8. Cập nhật khi triển khai

FE-04-01 chuyển page 0-based, /transactions, direction mới và route reference. Cập nhật filter/options/table và tiêu chí hiển thị ở file này khi code đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Pagination theo page của contract mới
