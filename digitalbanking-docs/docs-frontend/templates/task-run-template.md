# Phiếu chạy task <TASK-ID> / <RUN-ID>

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | <thời gian Asia/Bangkok, người thực hiện> |
| Task cha / con | <ID và link tracker> |
| FE SHA / working tree | <commit nền, dirty/clean; SHA bản thay đổi khi có> |
| BE SHA / môi trường | <SHA/API environment hoặc Không áp dụng — Mock> |
| Contract | <info.version, SHA hoặc hash của YAML> |
| Mức chứng minh | <Đọc source / Mock / API / Browser> |
| Nguồn đã đọc | <file + heading FR/AC/FET/operationId/design/standards> |
| Phạm vi được phép | <đầu ra cụ thể; quyết định người dùng liên quan> |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| <ID> | <nguồn thiếu/mâu thuẫn> | <Chờ trả lời hoặc quyết định thực tế> | <Có/Chưa cho phần nào> |

## 3. Thay đổi và kịch bản

<File/code/design/fixture đã sửa; hướng dẫn mở UI và tái lập; dữ liệu tổng hợp/seed ID.>

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Pass/Fail/Blocked/Chưa chạy | Evidence / command / timestamp |
|---|---|---|---|---|
| <ID> | <hành vi yêu cầu từ nguồn> | <quan sát thực tế> | <kết quả> | <artifact> |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide cần đồng bộ | <file hoặc Không áp dụng kèm lý do> |
| Tracker / GAP | <dòng task và trạng thái> |
| Review UI của người dùng | <Chưa duyệt hoặc người/ngày/nguồn chat> |
| Review tích hợp / PR | <Chưa có hoặc kết quả thật> |
| Readiness / xác nhận merge | <Chưa có hoặc phạm vi và xác nhận thật> |

## 6. Kết quả lần chạy

<Trạng thái; phần đạt/chưa đạt; giới hạn bằng chứng; defect/GAP; bước tiếp được phép; câu hỏi cần người dùng chốt trước phần phụ thuộc.>
