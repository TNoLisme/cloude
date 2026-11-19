# Vòng đời kiểm tra task FE

| Thuộc tính | Giá trị |
|---|---|
| Mã / ngày | FE-RUN / 13/11/2026 |
| Căn cứ | Yêu cầu người phụ trách FE ngày 13/11/2026; FE-DOC-01, FE-TEST-03/04, UI-DOC-01 |
| Phạm vi | Mọi lần chạy task FE, gồm mock, sửa UI, sửa lỗi, tích hợp và lần bị chặn |

## 1. Trước khi sửa

Tạo phiếu từ [[digitalbanking-docs/docs-frontend/templates/task-run-template.md]] trong `evidence/<task>/<ngày>-<FE SHA>/run-<số>.md`. Giữ phiếu cũ khi chạy tiếp. Ghi commit nền và working tree; nếu code chưa commit phải ghi rõ để không gán kết quả cho commit chưa chứa thay đổi. Khi đã commit, bổ sung SHA thực tế/CI artifact cho bản được kiểm chứng.

Đọc task và GAP, nguồn gốc, feature/standards/design liên quan. Liệt kê đầu ra, AC/FET, API operationId và trường hợp phải kiểm tra. Đối chiếu xem đầu vào đã được chốt chưa. Nếu chưa rõ: ghi câu hỏi, nguồn/đoạn thiếu, ảnh hưởng và hỏi người dùng; để task/phần phụ thuộc Bị chặn. Không viết handler hoặc UI ngầm giả định câu trả lời. Phần độc lập có thể tiếp tục và phải ghi ranh giới trong phiếu.

## 2. Khi thực hiện

Task cha có thể có task con `-MOCK`, `-UI` và `-API` khi cần để theo dõi mức chứng minh. Các task con phải liên kết đầu ra/AC của task cha; không sửa tiêu chí task cha để làm nó dễ hoàn thành hơn. Giữ tiến độ implementation trong tracker.

Ghi thay đổi, liên kết source, fixture và kịch bản được hỗ trợ. Dữ liệu demo là tổng hợp; không lấy giá trị fixture làm quy tắc nghiệp vụ. Mock chỉ bật trong dev/test theo cấu hình rõ ràng, không rơi về BE khi thiếu handler. Khi sửa UI phải cập nhật file design và shared docs tương ứng trong cùng thay đổi.

## 3. Sau khi thực hiện

Chạy checks thật phù hợp thay đổi và các gate bắt buộc đã được hiện thực trong package. Ghi command, expected/actual, Pass/Fail/Blocked, timestamp, môi trường và artifact; lệnh chưa chạy ghi Chưa chạy cùng lý do. Khi mock: kiểm tra schema/method/path, trạng thái UI liên quan và không có request lọt sang BE. Khi tích hợp: dùng API thật, có FE/BE SHA, browser critical flow và BE invariant evidence khi task yêu cầu.

Kiểm tra ngược đầu ra/AC/FET ban đầu. Cập nhật design/feature/guide khi có thay đổi, liên kết evidence trong tracker, giữ GAP và lỗi chưa giải quyết. Không ghi tài liệu hoặc task Hoàn thành chỉ vì đã viết code, build đạt hoặc mock trả 200.

## 4. Đánh giá cuối lần chạy

| Kết quả | Điều kiện / bước tiếp |
|---|---|
| Bị chặn | Thiếu quyết định hoặc đầu vào; ghi câu hỏi cụ thể và chờ trả lời cho phần phụ thuộc |
| Đang làm | Có đầu ra còn thiếu; ghi phần tiếp theo trong cùng phạm vi |
| Chờ review UI/mock | Checks mock liên quan đạt, có hướng dẫn xem UI và tài liệu đồng bộ; chờ người dùng góp ý/duyệt |
| UI/mock được duyệt | Ghi người/ngày/nguồn chat; mở hoặc giữ task tích hợp API thật |
| Đang kiểm chứng tích hợp | BE sẵn sàng và đang kiểm tra API/browser theo AC |
| Hoàn thành | Đủ FE-TEST-03: đầu ra/AC/checks/review/docs/evidence đạt; phần tích hợp có API thật và browser evidence |

Trạng thái `UI/mock được duyệt` là mốc evidence/task con, không thay trạng thái Hoàn thành của task cha. Sau khi người dùng duyệt UI mới chuyển phần đó sang tích hợp; merge cần xác nhận mốc cụ thể sau khi có kết quả review được.

## 5. Phản hồi cuối mỗi lần chạy

Thông báo task và mức chứng minh; thay đổi chính; cách mở preview nếu có; checks đã chạy và kết quả; liên kết phiếu/tài liệu; phần còn thiếu hoặc câu hỏi chờ chốt; bước tiếp theo đã được phép. Nếu chưa có app/mock chạy được phải nói rõ, không đưa URL chưa kiểm tra.
