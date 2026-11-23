# BE-AI-01 — Báo cáo Kiểm chứng Nghiên cứu Khả thi AI/ML Hỗ trợ Fraud

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-FRAUD-02-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã lên kế hoạch (`Planned` — hạng mục nghiên cứu mở rộng ngoài đường găng MVP)
> **Đặc tả công việc:** [BE-AI-01-fraud-feasibility.md](BE-AI-01-fraud-feasibility.md)

---

## 1. Ma trận Đánh giá Dự kiến (`Evaluation Matrix`)

| Hạng mục | Phương pháp thực hiện | Kết quả mong đợi | Trạng thái |
|---|---|---|---|
| Kiểm kê dữ liệu giả lập (`Synthetic Data Inventory`) | Rà soát các trường đặc trưng từ `FraudTransferSnapshot` | Chỉ sử dụng các trường số học/thời gian đã ẩn danh, không chứa PII thật | **Planned** |
| So sánh với Baseline Quy tắc (`Rule Baseline Comparison`) | Chạy đối chiếu offline với `FraudRuleEvaluator` (`BE-FRD-01`) | Báo cáo đầy đủ `Precision`, `Recall`, `FPR` và độ trễ `p50`/`p95` trên cùng tập fixture | **Planned** |
| Kiểm thử chịu lỗi & Fallback | Giả lập timeout, đầu ra rỗng hoặc sai định dạng | Luôn quay về kết quả của bộ quy tắc tất định mà không làm gián đoạn luồng xử lý | **Planned** |
| Khả năng tái lập (`Reproducibility`) | Chạy lại với cùng `seed`, phiên bản cấu hình và mã băm tập dữ liệu | Cho ra kết quả và chỉ số thống kê giống hệt nhau | **Planned** |

---

## 2. Kết luận Hiện hành

Trong phạm vi phát hành **Digital Banking MVP**, hệ thống sử dụng 100% **In-Process Deterministic Rule Engine (`BE-FRD-01`)** theo đúng quyết định kiến trúc `AD-11` (`06. System architecture.md` §3.2) và `DEC-TRF-009`. Nghiên cứu `BE-AI-01` được giữ làm tài liệu định hướng cho giai đoạn mở rộng sau MVP.
