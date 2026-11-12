# <TASK-ID> — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản task:** <link file task; revision hoặc snapshot>
> **Ngày / người thực hiện:** <thời điểm có timezone; implementer>
> **Trạng thái task:** <In progress / Blocked / Done>
> **Kết luận verification:** Not verified
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

- Mục tiêu, phần thực hiện và phần ngoài phạm vi: <điền>.
- Revision được kiểm tra: <commit SHA; nếu có uncommitted changes, kèm diff/snapshot xác định đúng nội dung đã test>.
- Môi trường: <OS, Java/build tool, PostgreSQL, profile; chỉ các thành phần liên quan>.
- Nguồn chuẩn đã đọc: <file + mục; FR/NFR/AC liên quan; không chỉ dẫn README backend>.
- Dependency: <task/module, report/revision, mức sẵn sàng thực tế; phần còn thiếu>.

## 2. Thay đổi thực tế

| File/thành phần | Thay đổi và mục đích | AC được đáp ứng |
|---|---|---|
| <link> | <mô tả hành vi, không chỉ tên class> | <ID> |

<Ghi docs đã cập nhật; lý do của thay đổi ngoài common; khác biệt nguồn và quyết định xử lý. Không ghi đã phê duyệt nếu chưa có bằng chứng.>

## 3. Ma trận tiêu chí chấp nhận

Sao chép **đủ COM-G01..04 và mọi AC riêng** từ task, giữ nguyên ID và nội dung. Một AC gồm nhiều điều kiện chỉ Verified khi tất cả đạt; có thể tách hàng nhưng không bỏ điều kiện.

| AC ID | Tiêu chí / kết quả kỳ vọng từ nguồn | Test hoặc cách kiểm tra | Kết quả thực tế | Evidence ID | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | <chép từ tasks/README> | <điền> | Chưa kiểm tra | — | Not verified |
| COM-G02 | <chép từ tasks/README> | <điền> | Chưa kiểm tra | — | Not verified |
| COM-G03 | <chép từ tasks/README> | <điền> | Chưa kiểm tra | — | Not verified |
| COM-G04 | <chép từ tasks/README> | <điền> | Chưa kiểm tra | — | Not verified |
| <TASK-AC-ID; thêm đủ hàng> | <chép nguyên tiêu chí> | <test ID/method hoặc inspection> | Chưa kiểm tra | — | Not verified |

Trạng thái theo `09. Test plan` §2.2: Planned, Implemented, Verified, Blocked, Not accepted, Not verified, Deferred. Không có evidence hợp lệ thì không Verified. Dependency/môi trường thiếu là Blocked; chạy có kết quả không đạt là Not accepted. Deferred chỉ khi có quyết định phạm vi, không dùng để né AC bắt buộc.

## 4. Nhật ký kiểm chứng và evidence

| Evidence ID | Thời điểm / môi trường / revision | Working directory + lệnh hoặc bước thực chạy | Expected → actual; exit code, số pass/fail/skip nếu có | Artifact tái kiểm tra được |
|---|---|---|---|---|
| E01 | <điền> | <lệnh thật; không điền lệnh dự kiến> | <điền> | <link log/test report/diff đã lưu> |

- Test mapping: <ID từ nguồn + tên test thực tế; nếu nguồn trùng test ID thì ghi cả tên tài liệu>.
- Inspection: <phạm vi/file đã xem, phát hiện và bằng chứng; không trình bày inspection như runtime test>.
- Các check chưa chạy/skipped: <tên, lý do, AC ảnh hưởng; không tính skipped là pass>.
- Retest sau sửa: <lỗi gốc, sửa đổi, lần chạy lại và evidence mới; giữ dấu vết lần fail>.

Artifact phải thuộc đúng revision/config được báo cáo và tồn tại ở nơi truy cập lại được; không chỉ trỏ file tạm đã mất hoặc chép lời “PASS”. Chỉ lưu dữ liệu synthetic đã che thông tin nhạy cảm; không ghi secret thật, cookie/token hoặc dump env. Evidence dùng lại phải chỉ rõ nguồn và xác minh còn áp dụng; thay đổi sau kiểm tra cần chạy lại phần bị ảnh hưởng.

## 5. Gap, lỗi và quyết định còn mở

| ID | AC ảnh hưởng | Gap/lỗi và tác động | Trạng thái | Đầu mối / điều kiện tiếp tục | Quyết định hoặc evidence liên quan |
|---|---|---|---|---|---|
| <ID hoặc Không có sau kiểm tra> | <ID> | <điền> | <điền> | <chưa phân công nếu chưa có> | <link> |

<Ghi rõ mock/test double và phạm vi không được chúng chứng minh; giới hạn local so với Cloud; ngưỡng còn Đề xuất; rotation hoặc dependency owner khác nếu liên quan. Không tự đóng open issue nguồn.>

## 6. Kết luận và bàn giao

- Tổng AC: <số>; Verified: <số>; chưa đạt/chưa kiểm chứng: <liệt kê ID>.
- Kết luận implementer: <đạt phạm vi task / chưa đạt; dẫn evidence và giới hạn>.
- Trạng thái đề nghị: <Done chỉ khi đủ mọi AC chung/riêng Verified; không đồng nghĩa module/toàn hệ thống đã nghiệm thu>.
- Việc tiếp theo: <task/owner nhận bàn giao, điều kiện cần và gap còn lại>.
- Review/sign-off thực tế: <chưa review hoặc người/ngày/quyết định có bằng chứng; không tự ghi thay reviewer>.

Mẫu này chưa chứa kết quả kiểm chứng. Khi tạo report, thay toàn bộ placeholder bằng thông tin thực tế hoặc ghi rõ chưa có; không sửa mẫu thành report của riêng một task.
