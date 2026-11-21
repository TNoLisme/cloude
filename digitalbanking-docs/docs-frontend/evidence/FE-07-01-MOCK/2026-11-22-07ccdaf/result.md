# FE-07-01-MOCK — kết quả kiểm browser và chỉnh bộ lọc

Ngày 22/11/2026, Chrome Windows mở `/staff/audit?auditPreview=v2` trên Vite mock `127.0.0.1:5174`. Danh sách hiển thị hai bản ghi tổng hợp #8001/#8002. Mở chi tiết #8001 thấy Audit ID, thời gian, actor, action, đối tượng, outcome, correlation ID và request ID. Không có beforeState/afterState object tự do trên màn; nút export vẫn disabled. Không lưu screenshot file.

Tại cửa sổ rộng 812 px, các ô lọc bị chồng nhãn và input. Đổi vùng lọc sang CSS grid tự xuống hàng và kiểm lại trên Chrome ở cùng kích thước. Không đổi contract, data, query, route hoặc quyền. Kịch bản empty/forbidden đạt qua component/MSW test; error/slow chưa được xem trên browser trong run này.

Kiểm tra: focused `AuditPreview.test.tsx` 2/2 pass, typecheck, ESLint, Prettier hai file sửa, contract check v1/v2, Vite build và `git diff --check` đều đạt. Build cảnh báo bundle >500 kB, không ảnh hưởng kết quả build. Lệnh/kết quả thực tế ở [run-02](run-02.md).

UI mock chờ review và thiết kế cuối. GAP-07/12, export, quyền staff runtime, API thật, FE-07-01 cha và FET-11/13 vẫn mở. Không có kết luận về audit chain, file export hoặc BE.
