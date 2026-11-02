# Bảng Phân Công Nhiệm Vụ 4 Thành Viên (P0 Acceptance)

> **Mục đích:** Tóm tắt ngắn gọn các đầu việc **CÒN THIẾU / CẦN LÀM** và các **KỸ THUẬT LÀM CHỦ BẢO VỆ ĐỒ ÁN** để từng thành viên nhìn vào là nhận việc ngay. Nhóm đã tối ưu hóa tổ chức từ 5 thành viên xuống **4 thành viên** (3 Backend + 1 Frontend/Integration).

---

## 1. Bảng tóm tắt nhanh (1 Màn hình)

| Vai trò | Mảng phụ trách | Kỹ thuật làm chủ (Bảo vệ đồ án) | Trọng tâm CẦN LÀM (Chưa xong) | Bàn giao & Phối hợp |
| :---: | :--- | :--- | :--- | :--- |
| **BE-A**<br>*(Gộp 1+2)* | **Xác thực, Bảo mật & Vòng đời Tài khoản** | • `Authentication & RBAC (3 Roles)`<br>• `Token Rotation & Revocation`<br>• `Pessimistic Locking (Số dư)` | Test xoay vòng Token, khóa 5 lần sai PIN/OTP, chống trùng SĐT/email, chống đọc trộm tài khoản (IDOR), chống nạp tiền đúp, khóa tài khoản, CI Backend. | • **Bàn giao:** CI Backend, test bảo mật & tài khoản.<br>• **Phối hợp:** Gửi chuẩn xác thực OTP/PIN và trạng thái khóa cho BE-3 và FE. |
| **BE-3**<br>*(Lõi)* | **Chuyển tiền, Dòng tiền & Nhất quán tài chính** | • `Idempotency-Key Pattern`<br>• `Strong Consistency (ACID Rollback)`<br>• `Deadlock Prevention (Sorted Locking)` | Test hoàn tác khi đứt gánh, mất mạng gửi lại không trừ tiền lần 2, chống rút âm khi đồng thời, chống treo khi chuyển chéo, chặn khi tài khoản bị khóa. | • **Bàn giao:** Test hoàn tác & dòng tiền, ma trận mã lỗi.<br>• **Phối hợp:** Nhận chuẩn từ BE-A; gửi ma trận lỗi và state cho FE. |
| **BE-B**<br>*(Gộp 2+4)* | **Kiểm toán, Hạ tầng dữ liệu & Tích hợp** | • `Immutable Audit Trail`<br>• `Distributed Tracing (Correlation-ID)`<br>• `Financial Invariant SQL Verification` | Dựng container DB/Kafka test dùng chung, test audit bất biến, test sập DB & restart app, tạo 100 account mẫu + câu truy vấn bảo toàn số dư cho k6, nghiệm thu toàn bộ. | • **Bàn giao:** Môi trường DB test, dữ liệu mẫu k6, báo cáo nghiệm thu.<br>• **Phối hợp:** Cung cấp DB test cho BE-A/3; gửi dữ liệu mẫu cho FE; nhận kết quả cả nhóm. |
| **FE/BE**<br>*(Mặt tiền)* | **Giao diện 3 vai trò, Chạy tải & Cảnh báo rủi ro** | • `Multi-role Web Experience`<br>• `Offline Retry UX with Idempotency`<br>• `Load Testing with k6 (50 VUs)`<br>• `Rule-based Anomaly/Fraud Flagging` | Rà soát khớp nối API 100%, test trình duyệt 3 vai trò (Customer/Operator/Auditor), xử lý mất mạng trên UI, logic & UI cảnh báo gian lận (>5M / >5 lần/10p), chạy tải k6 10 phút, CI Frontend. | • **Bàn giao:** CI Frontend, UI cảnh báo rủi ro, báo cáo k6, ảnh minh chứng UI.<br>• **Phối hợp:** Nhận dữ liệu mẫu từ BE-B; nộp kết quả nghiệm thu cho BE-B. |

---

## 2. Checklist nhận việc chi tiết từng người

### 👤 BE-A — Xác thực, Bảo mật & Vòng đời Tài khoản
* **Kỹ thuật làm chủ:** `RBAC 3 Roles`, `Token Rotation`, `Brute-force PIN/OTP Lockout`, `Pessimistic Locking (SELECT ... FOR UPDATE)`.
* **Bàn giao:** Quy trình CI Backend, Bộ test bảo mật, phiên làm việc và bảo vệ số dư tài khoản.
* **Phối hợp:** Gửi chuẩn xác thực OTP/PIN và trạng thái khóa dòng cho BE-3 và FE.

- [ ] Test xoay vòng Token đồng thời (chỉ 1 phiên thắng, thu hồi phiên cũ)
- [ ] Test khóa tạm thời sau 5 lần nhập sai mã PIN/OTP
- [ ] Test xử lý lỗi khi nhà mạng không gửi được OTP (báo lỗi an toàn)
- [ ] Test chống đăng ký trùng số điện thoại/email khi gửi cùng lúc
- [ ] Test bảo mật tài khoản (chống đọc trộm tài khoản người khác - IDOR)
- [ ] Test nạp tiền đồng thời (chống cộng tiền 2 lần khi bấm nạp liên tục bằng Pessimistic Lock)
- [ ] Test tính năng khóa/mở tài khoản (chặn ngay lập tức nạp và chuyển tiền)
- [ ] Tạo quy trình CI tự động kiểm tra code Backend (Auth & Account)
- [ ] Quét rà soát hệ thống bảo đảm log không in mật khẩu, PIN hay OTP

---

### 👤 BE-3 — Chuyển tiền, Dòng tiền & Nhất quán tài chính
* **Kỹ thuật làm chủ:** `Idempotency-Key Pattern`, `ACID Transaction Rollback`, `Deadlock Prevention (Lock order by ID)`, `Non-negative Balance`.
* **Bàn giao:** Bộ test hoàn tác & tranh chấp dòng tiền, Ma trận mã lỗi chuyển tiền.
* **Phối hợp:** Nhận chuẩn khóa từ BE-A, nhận OTP từ BE-A; gửi mã lỗi cho FE.

- [ ] Test giả lập lỗi sau khi trừ tiền $\rightarrow$ chứng minh hoàn tác 100%, không mất tiền
- [ ] Test mất mạng khi đang chuyển $\rightarrow$ thử lại cùng mã không trừ tiền lần 2 (`Idempotency`)
- [ ] Test 2 lệnh rút tiền cùng lúc vượt số dư $\rightarrow$ số dư không bao giờ âm
- [ ] Test 2 người chuyển tiền chéo nhau cùng lúc $\rightarrow$ không bị treo hệ thống (`Deadlock Prevention`)
- [ ] Test xung đột: vừa bấm xác nhận chuyển tiền thì tài khoản bị khóa
- [ ] Test chuyển $\le$ 5 triệu đi ngay; $>$ 5 triệu bắt buộc nhập OTP

---

### 👤 BE-B — Kiểm toán, Hạ tầng dữ liệu & Tích hợp
* **Kỹ thuật làm chủ:** `Immutable Audit Trail`, `Distributed Tracing (Correlation-ID)`, `Disaster Recovery & Failure Scenario`, `Financial Invariant Query`.
* **Bàn giao:** Môi trường test database dùng chung, Bộ dữ liệu mẫu & câu lệnh kiểm tra số dư, Báo cáo nghiệm thu toàn đồ án.
* **Phối hợp:** Cung cấp môi trường test cho BE-A/3; gửi dữ liệu mẫu cho FE; tổng hợp kết quả cả nhóm.

- [ ] Dựng môi trường container cơ sở dữ liệu/Kafka dùng chung để cả nhóm chạy test
- [ ] Test bảo đảm nhật ký kiểm toán không thể sửa/xóa và che dữ liệu nhạy cảm (Masking PII)
- [ ] Test kịch bản sập cơ sở dữ liệu (báo lỗi an toàn) & khởi động lại ứng dụng tự hồi phục
- [ ] Viết script tạo 100 tài khoản mẫu có sẵn tiền để phục vụ test tải k6
- [ ] Viết câu truy vấn SQL kiểm tra bảo toàn tổng số dư sau khi test tải ($\sum Balance_{before} = \sum Balance_{after}$)
- [ ] Quét lỗ hổng an toàn container ứng dụng (Trivy / Container Scan)
- [ ] Kiểm tra tích hợp trước khi ghép code và làm báo cáo nghiệm thu cuối cùng

---

### 👤 FE/BE — Giao diện 3 vai trò, Chạy tải & Cảnh báo rủi ro
* **Kỹ thuật làm chủ:** `Multi-role Web Experience (Customer/Operator/Auditor)`, `Offline Retry UX`, `Load Testing with k6`, `Rule-based Anomaly/Fraud Detection`.
* **Bàn giao:** Quy trình CI Frontend, Giao diện & logic Rule-based Fraud Flagging, Báo cáo đo tải k6 & ảnh chụp minh chứng giao diện.
* **Phối hợp:** Nhận dữ liệu mẫu từ BE-B để test tải; nộp báo cáo cho BE-B.

- [ ] Rà soát tự động đảm bảo toàn bộ giao diện khớp 100% với API chuẩn
- [ ] Chạy kiểm thử trình duyệt thực tế 3 vai trò (chụp ảnh minh chứng Customer, Operator, Auditor)
- [ ] Xử lý giao diện khi mất mạng: giữ dữ liệu để thử lại an toàn với Idempotency-Key
- [ ] Viết kịch bản và chạy đo tải (50 người dùng trong 10 phút) lấy số liệu độ trễ P95/P99 và throughput
- [ ] Triển khai hiển thị và kiểm tra 2 luật rủi ro gian lận (`> 5 triệu` hoặc `quá 5 lần/10 phút`) trên UI
- [ ] Tạo quy trình CI tự động kiểm tra code giao diện

---

## 3. Ba quy ước làm việc cốt lõi

1. **Độc lập tuyệt đối:** Mỗi người chỉ làm việc trên module của mình, không sửa chéo code người khác.
2. **Không chờ đợi:** Giao diện kết nối và mã lỗi đã chốt từ đầu, mọi người code và test ngay lập tức.
3. **Kiểm soát ghép code & Cập nhật:** Mọi tiến độ cập nhật vào [`progress.md`](file:///d:/work/Xgame/XCreative/yuiyL/Cloud/cloude/docs/progress.md). BE-B kiểm tra tính tương thích trước khi nghiệm thu bản chung.
