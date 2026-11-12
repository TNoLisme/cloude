# Kế hoạch triển khai module `common`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-COM-IMP-PLAN
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Planned — chưa xác nhận implementation
> **Owner:** Phương; phân chia owner Vũ/Phương trong tài liệu 11 vẫn mang nhãn đề xuất
> **Reviewer:** Chưa review

## 1. Cách thực hiện một task

1. Đọc trang này, file task được giao và đúng các mục nguồn được dẫn; không cần nạp toàn bộ backlog.
2. Kiểm tra code, cấu hình, test và report dependency hiện tại. Không coi mô tả hiện trạng ngày 12/11/2026 là hiện trạng của phiên sau.
3. Triển khai phần còn thiếu, viết/chạy kiểm thử phù hợp ngay trong task, cập nhật docs bị ảnh hưởng.
4. Sao chép [mẫu report](../report/VERIFICATION-TEMPLATE.md) thành `report/<TASK-ID>-verification.md`; điền toàn bộ tiêu chí chung và riêng trước khi kết luận.

`BE-COM-DOC-01/02` là hồ sơ công việc tài liệu đã có; không viết lại lịch sử và không dùng chúng làm bằng chứng runtime. Task implementation không thay đặc tả hoặc ấn định class/chữ ký Java. Tái sử dụng code đúng hiện có, không triển khai lại chỉ để khớp tên minh họa.

## 2. Nguồn và ranh giới bắt buộc

- Thứ tự: **docs-common/01–11 → docs-common/design → docs-backend**. Đọc hướng dẫn tại [[digitalbanking-docs/docs-common/README.md]]. `07` quyết định ngữ nghĩa API; OpenAPI là bản máy đọc cần đồng bộ. Task/report không phải nguồn đặc tả mới.
- Không suy thứ tự ưu tiên giữa các file 01–11 từ số hiệu. Nếu chúng mâu thuẫn hoặc thiếu quyết định cần thiết, ghi đúng hai nguồn, tác động và phần bị chặn; tiếp tục phần độc lập, không tự chọn policy hay sửa nguồn để làm test pass.
- Giữ nhãn **Đề xuất/Deferred/Open**. Bảy nhóm đã phân xử tại [[digitalbanking-docs/docs-backend/modules/common/README.md]] §1.5 không cần chọn lại nguồn. Rotation nhiều khóa vẫn thiếu thiết kế; không tự bổ sung format/schema hay tuyên bố đã hoàn thành.
- `common` không phụ thuộc module nghiệp vụ, không sở hữu bảng/repository nghiệp vụ, không điều phối transaction tiền. Identity sở hữu session/OTP, transfer sở hữu idempotency, audit sở hữu ghi audit/hash chain. Chỉ sửa điểm tích hợp cần thiết trong module khác, ghi rõ trong report.
- Phân công và mốc theo [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §2–§8 và [[digitalbanking-docs/docs-common/10 Master timelines.md]] §3–§4: nền common W2–W3; tích hợp W3–W5; quality gate W6; Cloud W7–W8. Đây là mốc kế hoạch, không phải bằng chứng tiến độ.

## 3. Danh sách task và phụ thuộc

| Task | Mục tiêu | Mốc | Phụ thuộc nghiệm thu | Trạng thái |
|---|---|---|---|---|
| [BE-COM-IMP-01](BE-COM-IMP-01.md) | Cấu hình nền, thời gian và ranh giới | W2–W3 | Baseline tài liệu đã phân xử | Done — [verification](../report/BE-COM-IMP-01-verification.md); chờ review |
| [BE-COM-IMP-02](BE-COM-IMP-02.md) | Mô hình lỗi và exception mapping | W3 | 01; wiring trace/security hoàn thiện ở 03/05 | Done — [verification](../report/BE-COM-IMP-02-verification.md); chờ review |
| [BE-COM-IMP-03](BE-COM-IMP-03.md) | Request context và correlation | W3 | 01–02 | Done — [verification](../report/BE-COM-IMP-03-verification.md); chờ review |
| [BE-COM-IMP-04](BE-COM-IMP-04.md) | Crypto, JCS và masking | W3 | 01 | Done — [verification](../report/BE-COM-IMP-04-verification.md); chờ review |
| [BE-COM-IMP-05](BE-COM-IMP-05.md) | HTTP security và tích hợp identity | W3 | 01–03; contract/code identity liên quan | Planned |
| [BE-COM-IMP-06](BE-COM-IMP-06.md) | Logging, metrics và health | W3–W5 | 02–05 | Planned |
| [BE-COM-IMP-07](BE-COM-IMP-07.md) | Tích hợp và quality gate common | W3–W6 | 01–06 và các module tham gia từng luồng | Planned |
| [BE-COM-IMP-08](BE-COM-IMP-08.md) | Readiness, Cloud verification và bàn giao | W6–W8 | Readiness từ W6; deploy sau quality gate, có môi trường/owner hạ tầng | Planned |

Thứ tự thực hiện gợi ý: 01 → 02 → 03 → 04 → 05 → 06; tích hợp 07 theo từng module sẵn sàng, không dồn đến W6. Chuẩn bị tài liệu 08 từ W6; chỉ đóng task sau bằng chứng W7–W8. Nếu dependency chưa có, có thể làm phần độc lập và ghi Blocked cho tiêu chí phụ thuộc; không tạo nghiệp vụ giả trong production để đạt tiêu chí.

## 4. Tiêu chí chung áp dụng cho cả 8 task

| ID | Tiêu chí chấp nhận | Bằng chứng bắt buộc |
|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Danh sách nguồn có mục cụ thể, quyết định và gap trong report |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | Danh sách file thay đổi, kiểm tra dependency/phạm vi; lý do cho sửa ngoài common |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | Test ID, lệnh thực chạy, môi trường, revision, kết quả/exit code và artifact; thiếu môi trường ghi Blocked |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report theo mẫu, link evidence đã kiểm tra, danh sách docs thay đổi và việc còn lại |

Chỉ ghi **Done** khi COM-G01..04 và mọi AC riêng đều **Verified**, không còn lỗi hoặc blocker ảnh hưởng tiêu chí. Không xóa/hạ tiêu chí hay chuyển thành N/A/Deferred để đóng task; thay đổi phạm vi cần quyết định được ghi lại. Code/test tồn tại, build pass hoặc tài liệu ghi “Hoàn thành” không tự chứng minh hành vi đã đạt. Dùng trạng thái evidence theo [[digitalbanking-docs/docs-common/09. Test plan.md]] §2.2, §8, §10. Kết luận implementer và review/sign-off là hai phần riêng; không tự nhận review thay người khác.
