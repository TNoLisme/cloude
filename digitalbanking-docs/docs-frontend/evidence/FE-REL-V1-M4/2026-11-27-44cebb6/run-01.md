# Phiếu chạy FE-REL-V1-M4 / đồng bộ nguồn BE-Security

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 27/11/2026 Asia/Bangkok / Codex |
| Task | Kiểm tra thay đổi BE/Security cần thiết để nối bốn màn M4 |
| FE SHA / working tree | Nền `44cebb6`, clean lúc bắt đầu; bản thay đổi tài liệu chưa commit |
| BE SHA / môi trường | BE dev2@`740ab5f` đã ghép; QA M4 image SHA và HTTP kết quả tại run-03 trước đó |
| Contract | Common 07 §3.4–3.6, §8.2/8.6/8.7/8.8; OpenAPI v1.1.0 |
| Mức chứng minh | Đối chiếu source và QA HTTP từ run-03; chưa FE browser |
| Nguồn đã đọc | AGENTS.md, FE README/tracker/roadmap/lifecycle/template, FE handoff 12/13, common 07, SRS §4.2/§8, Security §5.1, SecurityFilterChainConfig, năm controller GET M4 và run-03 |
| Phạm vi được phép | Chat 27/11/2026: người phụ trách FE cho biết BE/Security cho phép tự chốt handoff và yêu cầu sửa phần cần thiết để nối bốn màn M4; không tự nhận BE/Security trực tiếp duyệt commit này |

## 2. Kiểm tra sẵn sàng

| Vấn đề | Căn cứ | Quyết định/phạm vi | Kết quả |
|---|---|---|---|
| BE endpoint/quyền M4 | SecurityFilterChainConfig và controllers có GET customer/account, approval, fraud, audit; QA role 200/403/404 đạt | Theo common 07/OpenAPI v1.1.0, không tạo endpoint/permission mới | Không cần thay BE code để bắt đầu nối FE |
| Security §5.1 lệch common 07 | Dòng gộp CUSTOMER_READ/ACCOUNT_READ/TRANSACTION_READ cho Auditor; FRAUD_READ cho Operations | Người phụ trách FE chốt common 07/BE làm baseline M4 | Đã tách/sửa ma trận Security theo contract v1 |
| SRS §4.2/§8 còn khác | SRS ghi Auditor được đọc customer profile; common 07/BE chặn | Quyền BA không được người dùng báo ủy quyền; không tự ghi BA approval | Giữ việc BA đồng bộ SRS mở, không mở quyền BE |
| FE | AuthApiApp vẫn hiển thị phiên chung staff; các preview dùng client v2/mock | M4 cần API v1 và browser FET | Chưa nối; không merge M4 |

## 3. Thay đổi

Sửa `docs-common/design/04. security.md` §5.1 để Auditor không có `CUSTOMER_READ`, Operations đọc fraud flag bằng `FRAUD_REVIEW`, Admin/Auditor bằng `FRAUD_READ`. Cập nhật handoff BE/Security và GAP-07 tracker với kết quả đối chiếu source. Không sửa code BE vì matcher/controller đã đúng với v1 và QA HTTP cơ bản đã đạt. Không sửa SRS của BA hay OpenAPI.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected | Actual | Trạng thái | Evidence |
|---|---|---|---|---|
| BE source M4 | Có endpoint/matcher v1 theo common 07 | Đã có, không phát hiện thiếu phần cần code change | Pass source review | SecurityFilterChainConfig + năm controller, QA run-03 |
| Security matrix | Khớp common 07 cho M4 | Đã sửa §5.1 | Pass tài liệu | diff Security §5.1 |
| API/browser đầy đủ | Response có dữ liệu/scope/masking, FE route/cache/browser | Chưa chạy trong lần này | Chưa đạt merge | M4 tracker/run-03 |
| Kiểm tra diff | Không whitespace error | `git diff --check` exit 0; chỉ có cảnh báo LF/CRLF của hai handoff | Pass | Chạy tại repo ngày 27/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| BE handoff | `management/12-m4-be-handoff.md` ghi source/QA và không cần sửa BE code cho GET |
| Security handoff | `management/13-m4-security-handoff.md` ghi Security §5.1 đã đồng bộ, SRS BA còn mở |
| Tracker | GAP-07 ghi trạng thái chính xác; M4 chưa hoàn thành |
| Merge | Chưa đủ điều kiện; không có FE browser và response có dữ liệu/scope sâu |

## 6. Kết quả

BE/Security code hiện có đủ để FE bắt đầu nối bốn màn M4 theo v1. Thay đổi cần thiết ở nguồn Security là sửa ma trận tài liệu để không mâu thuẫn với common 07 và runtime. Việc M4 chưa nối nằm ở FE; BA SRS còn cần đồng bộ, cùng QA sâu và browser trước merge.
