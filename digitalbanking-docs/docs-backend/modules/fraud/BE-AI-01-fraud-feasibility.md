# BE-AI-01 — Nghiên cứu AI/ML hỗ trợ fraud

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-FRAUD-02; **Task:** BE-AI-01  
> **Phiên bản:** 0.2; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; research chưa bắt đầu  
> **Owner:** Thịnh; **Reviewer đề xuất:** Vũ (money path), Đạt (admin/security), Phương (data/common); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §5; [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]]; [[digitalbanking-docs/docs-backend/modules/fraud/BE-FRD-01-rules-and-review.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]].

## 1. Mục tiêu và non-goals

Đánh giá offline trên synthetic data, so với BE-FRD-01 rule baseline. AI không vào transfer T2, không thay mandatory auth/limit/idempotency, không tạo admin endpoint, không thêm runtime dependency trong MVP.

## 2. Common/data boundary

Reuse Common Clock/config only where needed. Không dùng Common crypto/PII classes để biến dataset thành production data. Không gửi dữ liệu tới LLM/model/provider ngoài nếu chưa có approval, consent/data-processing decision và security review. Model output untrusted; validate schema, bounds, modelVersion và fallback trước mọi use.

Synthetic dataset không chứa PII/account/token/OTP/session/raw text/production IDs. Seed, generator version, split hash và artifact hash phải lưu trong report; không commit data/model artifact lớn.

## 3. Evaluation

- Split theo customer và time, chống leakage.
- Baseline rule cùng fixtures/version.
- Threshold chỉ tune trên validation.
- Test set chạy một lần.
- Report precision/recall/FPR, class balance, coverage, slice metrics, p50/p95 latency và feature-generation latency.
- Test missing feature, invalid output, timeout, unavailable model, drift giả lập.
- Kết luận chỉ `Stop`, `Research only` hoặc đề xuất `Approved expansion`; approval mở task riêng.

Synthetic labels không chứng minh real-world fraud quality, fairness hoặc business value.

## 4. Future interface — chưa triển khai

```java
public interface FraudRiskScoringPort {
    Optional<FraudRiskScore> score(FraudRiskInput input);
}
```

Port advisory, không quyết định money state. Timeout/invalid output fallback về rule baseline. Không gọi remote model trong T2.

## 5. Governance và acceptance

Admin owner Đạt quản model/threshold approval nếu nhóm mở rộng. Audit owner Thịnh chốt allowlist cho modelVersion/decision source/fallback reason. Không ghi raw features. Implementation task sau này phải có security, cost, latency, rollback, monitoring, approval và evidence riêng.

## 6. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial offline feasibility proposal |
| 0.2 | 12/11/2026 | Aligned Common data/security boundary and untrusted model-output handling |
