# Phiếu chạy task FE-FND-02-CONTRACT-AUDIT / run-02

## 1. Định danh và đầu vào

17/11/2026, Asia/Bangkok; Codex. FE nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc. Working tree đã có tài liệu audit và các phiếu 16/11 chưa commit; giữ các thay đổi đó. BE refs và nguồn contract giữ theo source-snapshot.json của run-01; lần này chỉ phân định trách nhiệm câu hỏi, không kiểm runtime hoặc tìm baseline mới.

Đã đọc FE README, tracker task CONTRACT-AUDIT/GAP-05/07/09, roadmap và FE-RUN/template, management/05 mục 4–6. Nguồn nghiệp vụ/semantics là các nguồn 03/04/07/DEC đã ghim ở run-01 và handoff; không thay cách chọn source hoặc lấy legacy làm nghiệp vụ đích. Feature/design không đổi vì chưa thay hành vi UI.

## 2. Kiểm tra sẵn sàng

Người phụ trách FE yêu cầu trong chat: “câu hỏi nào là cần hỏi be để chốt còn nếu của fe thì để tôi chốt sau khi có phản hồi từ be”. Ghi nhận thứ tự: BE trả lời input contract/server trước, người phụ trách FE quyết định UX/client sau; không ghi các lựa chọn FE là đã chốt. Các quyết định thiết kế đã tìm thấy và phê duyệt FE/mock v2 cũ tiếp tục có hiệu lực.

## 3. Thay đổi và kịch bản

Sửa handoff mục 4 thành hai bảng: Q-01..05 chỉ gửi BE và các owner BE điều phối; FE-01..06 để người phụ trách FE chốt sau phản hồi tương ứng. Chỉnh Q-04 thành thông tin API/recovery/retention thực có, đưa lựa chọn UX/navigation/persistence về bảng FE. Đồng bộ điều kiện lộ trình và mẫu ghi kết quả; thêm ghi chú tracker/roadmap. Không sửa source, mock, canonical contract hoặc gửi tin cho BE.

## 4. Đối chiếu hoàn thành

Expected: danh sách gửi BE không yêu cầu BE quyết định layout/thông báo/menu hoặc chọn thay phạm vi phía FE; mỗi câu FE có input BE và owner xác nhận. Actual: handoff có 5 câu BE và 6 mục FE chờ sau phản hồi; review nội dung Q-04 và gate lộ trình đã tách API/policy khỏi UX/persistence.

Lệnh git diff --check -- digitalbanking-docs/docs-frontend: exit 0, chỉ cảnh báo LF/CRLF. Node inline đọc 5 file Markdown (handoff/tracker/roadmap/feature/evidence), kiểm Q-01..05 nằm ở mục 4.1, FE-01..06 nằm ở mục 4.2, 66 wikilink có file đích và code fences cân bằng: Pass, exit 0. Không kiểm ngữ nghĩa toàn bộ docs qua link check. Không chạy suite FE/BE vì không đổi code hoặc contract.

## 5. Tài liệu và review

Handoff và evidence task con vẫn Draft/Chờ review. Chưa có phản hồi BE hoặc quyết định UX mới của người phụ trách FE; không đóng GAP. FE không tự quyết security/storage hay sửa quyền server: những giới hạn đó cần owner chuyên môn xác nhận trước lựa chọn UI tương ứng.

## 6. Kết quả lần chạy

Đầu ra để người dùng gửi BE: handoff mục 4.1. Mục 4.2 dành cho người phụ trách FE sau khi có phản hồi. Chưa commit/push/merge; kết quả chỉ ở mức tài liệu.
