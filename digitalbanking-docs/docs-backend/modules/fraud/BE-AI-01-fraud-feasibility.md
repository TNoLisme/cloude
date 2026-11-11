# BE-AI-01 — Nghiên cứu AI/ML hỗ trợ fraud

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-FRAUD-02; **Task:** BE-AI-01  
> **Phiên bản:** 0.1; **Ngày:** 11/11/2026  
> **Trạng thái tài liệu:** Draft — chờ review; **Thực nghiệm:** Planned; **Kết luận:** Not assessed  
> **Người đề xuất / Owner:** Thịnh; **Reviewer đề xuất:** Vũ (money path), Đạt (admin/security), Phương (data); chưa có approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §5; [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] §1, §4, §8; [[digitalbanking-docs/docs-backend/modules/fraud/BE-FRD-01-rules-and-review.md]].

## 1. Mục tiêu, non-goals và thứ tự

Trả lời: trên tập **dữ liệu mô phỏng**, một mô hình fraud-risk có phát hiện được tình huống bổ sung so với rule baseline không, với tỷ lệ cảnh báo giả và latency được đo thế nào? Đầu ra là báo cáo có phương pháp, kết quả, hạn chế và đề xuất `Stop` hoặc `Research only`; chỉ nhóm có thẩm quyền mới quyết định `Approved expansion`.

Không dùng model để chặn/chấp thuận tiền, đổi limit/auth, tạo API admin, thêm dependency backend hoặc gửi PII ra service. AI không bắt buộc để hoàn thành MVP rule-based. Thịnh có thể chuẩn bị đề xuất dataset và evaluation harness trước khi fraud integration xong; mọi so sánh với BE-FRD-01 phải ghi rõ version rule và chỉ thực hiện khi baseline tương ứng sẵn sàng.

## 2. Data contract v0.1 — đề xuất AI-P01

Tạo generator offline với seed cố định; không lấy DB thật hay khách hàng thật. Mỗi row synthetic gồm:

| Field | Kiểu / ràng buộc | Mục đích |
|---|---|---|
| `scenario_id` | UUID giả, riêng từng run | Trace và chống trùng split |
| `customer_key` | pseudonymous synthetic integer | Chia tập theo customer, không là ID thực |
| `occurred_at` | Instant UTC tổng hợp | Time split, window |
| `amount_vnd` | số nguyên dương | Giá trị giao dịch; không dùng float |
| `count_120s`, `sum_day_vnd` | integer >= 0 | Feature rule/velocity tổng hợp |
| `new_recipient` | boolean | Feature minh họa, không chứa account |
| `label_synthetic` | 0/1 và `label_source` | Nhãn theo kịch bản generator, không phải ground truth thực |

Không có username, account number, số điện thoại, email, CCCD, OTP, session/token, raw text, thiết bị thật hoặc IDs liên kết sang production. Generator và artifact local phải ghi seed/version; artifact test có thể xóa theo quyết định người dùng, không commit dataset lớn hoặc secrets.

## 3. Phương pháp thực nghiệm và baseline

1. Tạo 3 dataset fixtures tách train/validation/test theo `customer_key` và thời gian; kiểm tra không giao customer giữa split, tránh leakage.
2. Baseline rule: BE-FRD-01 với cấu hình ghi rõ `rule_code`, version, effective time và proposal threshold; nếu engine chưa có, mô tả baseline thủ công là **fixture baseline**, không claim chạy BE-FRD-01.
3. Huấn luyện hoặc chạy mô hình nhỏ offline khi môi trường/approach được chọn; chọn threshold chỉ trên validation set. Không dùng test set để điều chỉnh threshold.
4. Chạy test set một lần theo version; báo confusion matrix, precision, recall, false-positive rate, số lượng mẫu, tỷ lệ positive và p50/p95 inference latency; báo cả thời gian tạo feature riêng. Không hứa target threshold khi stakeholder chưa chốt.
5. Chạy các scenario amount/rate boundary, absent features, outliers, drift giả lập. Báo kết quả từng nhóm và so sánh rule baseline trên cùng tập; chênh lệch nhỏ hoặc fixture-generated labels bị bias phải nêu rõ.
6. Lưu môi trường, command, seed, generator/model version, config, split manifests hash và model artifact hash. Không upload data/model cho dịch vụ ngoài chỉ để chạy thử.

Synthetic labels thường bắt chước generator/rules nên không chứng minh fraud detection ngoài đời, không chứng minh fairness hay business value. Phải ghi giới hạn này trong kết luận.

## 4. Interface tích hợp tương lai — không triển khai trong task

```java
public interface FraudRiskScoringPort {
    Optional<FraudRiskScore> score(FraudRiskInput input);
}
public record FraudRiskScore(String modelVersion, BigDecimal score,
                             String fallbackReason, Duration latency) {}
```

Đây là interface **đề xuất** cho task sau, không phải lớp hiện có. `score` advisory, không thay mandatory auth/limit/idempotency. Không gọi remote model trong T2; nếu tương lai cần realtime phải có design latency/isolation/transaction riêng. Timeout, unavailability, invalid output hoặc thiếu feature phải trả fallback an toàn về rule baseline; không được để AI bỏ qua mandatory controls. Không ghi raw features/PII vào log/audit.

## 5. Phụ thuộc admin/audit và governance

Nếu sau này có quản trị model: Đạt sở hữu admin workflow; đề xuất change version/threshold có review, maker/checker, rollback, audit C2 theo hợp đồng được duyệt. BE-AUD-01 cần catalog/allowlist cập nhật trước khi ghi modelVersion, decision source, safe reason; không tự nhét raw inference vào snapshots. Task feasibility không tạo endpoint và không sửa approval enum.

## 6. Kế hoạch test và nghiệm thu

- Data contract: không có PII thật; mọi count/amount hợp lệ; seed cố định tái tạo cùng data digest.
- Split: `customer_key` không trùng giữa train/validation/test; không dùng tương lai tạo feature cho quá khứ.
- Reproducibility: chạy hai lần cùng seed/config/version, so data, split, predictions và metric digest; nếu tool không deterministic ghi rõ giới hạn và tolerance.
- Evaluation: confusion matrix/precision/recall/FPR, latency p50/p95, feature coverage và slices; có rule baseline cùng input.
- Failure: thiếu field, invalid score, timeout giả lập, model unavailable không làm thay đổi transfer/ledger/limit; đây là test offline/harness, không phải bằng chứng production integration.
- Privacy: quét artifact/logs, không có token/OTP/PII; không gửi data ra ngoài.

Lệnh tạo/chạy phụ thuộc tool được chọn sau; ghi **chính xác command thực tế** vào [report](BE-AI-01-verification-report.md), không mô tả một lệnh giả như đã tồn tại. Bản spec không chọn tool/model/provider; không có run evidence.

## Quyết định và handoff

AI-P01 dataset/benchmark là proposal của Thịnh. Nếu kết quả không đủ dữ liệu/tính khả thi, kết luận `Research only` hoặc `Stop`; nếu muốn đưa vào hệ thống, tạo task riêng gồm FR/API/DB, security, cost, latency, fallback, rollback, monitoring, approval và nghiệm thu. Nhóm quyết định scope trước khi integration; không sửa BE-FRD-01 để đưa AI vào MVP một cách ngầm định.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Đề xuất data contract và evaluation offline; chưa chạy |
