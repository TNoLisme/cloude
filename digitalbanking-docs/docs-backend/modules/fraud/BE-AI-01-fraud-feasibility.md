# BE-AI-01 — Nghiên cứu Khả thi AI/ML Hỗ trợ Phát hiện Gian lận (`Fraud AI/ML Feasibility`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-FRAUD-02` (`Task: BE-AI-01`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành thiết kế khung nghiên cứu (Hạng mục nghiên cứu offline ngoài phạm vi luồng tiền `T2` của MVP)
> **Owner:** Thịnh · **Reviewer:** Vũ (`transfer`), Đạt (`admin`, `identity`), Phương (`common`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] (§5), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]], [[digitalbanking-docs/docs-backend/modules/fraud/BE-FRD-01-rules-and-review.md]], [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]

---

## 1. Mục tiêu và Giới hạn Phạm vi (`Non-Goals`)

Định nghĩa khung đánh giá thử nghiệm ngoại tuyến (`offline evaluation`) cho mô hình chấm điểm rủi ro AI/ML trên tập dữ liệu giả lập (`synthetic dataset`), đặt cạnh bộ quy tắc tất định `BE-FRD-01`:
- **Giới hạn bắt buộc trong MVP:** Không đưa mô hình AI/ML hoặc lời gọi mạng tới dịch vụ chấm điểm bên ngoài vào giao dịch chuyển tiền `T2`; không thay thế các bước kiểm tra bắt buộc về xác thực, hạn mức, idempotency hay bộ quy tắc tất định `BE-FRD-01`; không thêm thư viện ML nặng vào gói runtime của ứng dụng trong giai đoạn MVP.

---

## 2. Ranh giới Dữ liệu và Bảo mật

- Tập dữ liệu thử nghiệm phải là dữ liệu giả lập hoàn toàn (`NFR-032`), không chứa PII thật, số tài khoản, mật khẩu, PIN, OTP, session token hay văn bản mô tả tự do.
- Tuyệt đối không gửi dữ liệu giao dịch hoặc hồ sơ khách hàng ra dịch vụ AI/LLM bên ngoài.
- Mọi đầu ra của mô hình chấm điểm (nếu thử nghiệm) đều được coi là dữ liệu không tin cậy (`untrusted input`), phải kiểm tra schema, khoảng giá trị (`0.0`–`1.0`), phiên bản mô hình (`modelVersion`) và luôn có cơ chế quay về (`fallback`) bộ quy tắc tất định `BE-FRD-01`.

---

## 3. Phương pháp Đánh giá Offline

- Phân tách tập dữ liệu huấn luyện / kiểm định / kiểm thử theo khách hàng và theo mốc thời gian để chống rò rỉ dữ liệu tương lai (`data leakage`).
- So sánh trực tiếp với baseline quy tắc `BE-FRD-01` trên cùng bộ dữ liệu và phiên bản cấu hình.
- Ghi nhận các chỉ số: `Precision`, `Recall`, `False Positive Rate (FPR)`, độ trễ trích xuất đặc trưng (`feature extraction latency`), độ trễ suy luận (`p50`/`p95`) và hành vi khi thiếu đặc trưng hoặc quá hạn thời gian chờ (`timeout`).

---

## 4. Hợp đồng Giao tiếp Dự kiến cho Giai đoạn Mở rộng (`Post-MVP`)

```java
public interface FraudRiskScoringPort {
    Optional<FraudRiskScore> score(FraudRiskInput input);
}
```

Cổng này chỉ mang tính chất tham khảo bổ trợ (`advisory`) cho giai đoạn sau MVP; khi quá hạn chờ hoặc lỗi định dạng sẽ tự động bỏ qua và giữ nguyên kết quả của `FraudRuleEvaluator`.

---

## 5. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.2` | 11–12/11/2026 | Khởi tạo khung đánh giá khả thi AI/ML ngoại tuyến và ranh giới bảo mật dữ liệu. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong kỹ thuật tiếng Việt. |
