# BE-TRF-01 — Hồ sơ bàn giao và trạng thái nghiệm thu

**Trạng thái: Blocked — còn thiếu quyết định, review và bằng chứng tích hợp cần thiết.** [DEC-TRF-001/002](../../../../../../DECISIONS.md) đã chốt thành phần tổng ngày và ngưỡng step-up theo chỉ đạo người điều hành; hồ sơ này không phải chấp thuận của các owner. Mốc rà soát: 15/11/2026; revision Git cơ sở `d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49`, cùng các thay đổi chưa commit trong working tree. Kiểm tra lại revision và quyết định trước khi dùng hồ sơ.

## Tài liệu bàn giao

- [Task và tiêu chí nghiệm thu](../BE-TRF-01.md); [backlog](../README.md).
- [Matrix contract và trace](BE-TRF-01-contract-matrix.md): operation, ownership, ranh giới và điểm chờ provider/consumer review.
- [Danh mục rủi ro và yêu cầu quyết định](BE-TRF-01-risk-register.md): phương án, đầu mối, ảnh hưởng và nguồn; các mục là việc cần review, không phải quyết định.
- [Kế hoạch contract và integration test](BE-TRF-01-test-plan.md): vector, oracle, test double, test PostgreSQL thật và mẫu evidence.
- [Đề xuất phối hợp audit C2/C1](BE-TRF-01-audit-proposal.md): hai phương án giao tiếp và vector cần owner review.
- [Modules contract](../../../../../docs-common/design/05.%20Modules%20contract.md) §§3–14 và [concurrency](../../../../../docs-common/design/02.%20consistency-concurrency.md) §16.2 là nguồn giao tiếp và open issue; [API contracts](../../../../../docs-common/07.%20API%20contracts.md) §§5–6, 8.3 là baseline API. Theo thiết kế Transfer, `docs-common/01–11` ưu tiên hơn `docs-common/design/` và backend; xung đột giữa nguồn có thẩm quyền cần owner phân xử.

## Checklist theo AC

| AC | Hồ sơ đã chuẩn bị | Trạng thái còn thiếu / người theo dõi |
|---|---|---|
| TRF-01.1 | Matrix theo operation, owner, boundary và vector; `05` §§10.1/11.1 bổ sung giao tiếp guard/reservation, evidence recovery và audit staging còn thiếu dưới dạng đề xuất. | **Blocked:** chữ ký, nullability, error taxonomy, version và review provider/consumer chưa được xác nhận. Transfer/Vũ theo dõi với từng provider và Architect; các interface bổ sung chưa được duyệt hoặc triển khai. |
| TRF-01.2 | Baseline `04/07`: key global, `IDEMPOTENCY_CONFLICT`, replay status/body terminal gốc 201 hoặc 422; `202` là tùy chọn async. Risk register có việc đồng bộ overview và YAML. | **Blocked:** API/DB/BA phân xử `04` §7.7.3 về retry T2 với `07` §5.4 về 409; API/FE/QA review YAML/overview và correlation. Không coi YAML hiện tại khai báo 200 là baseline. |
| TRF-01.3 | DEC-TRF-001 chọn tính cả lệnh đang xử lý; DEC-TRF-002 chọn mặc định ≥ 10 triệu/lệnh hoặc > 20 triệu/ngày. Test plan có ranh giới và hai request/key đồng thời; `05` có phương án guard/reservation dự thảo. | **Blocked / chặn release money path:** Identity, Limit, Architect, BA và Transfer review scope/ngày, protocol, migration cấu hình, binding, expiry và retry; kiểm chứng race. |
| TRF-01.4 | Tách preflight, thay đổi sau T1 và credit technical failure trong test plan; thiết kế có bảng oracle và phương án Tfail dự thảo. | **Blocked:** BA, API, QA, Architect, Account và Transfer phân xử `03` AC-006 so với ví dụ FAILED trong `07`, và AC-012 so với `design/02`, đồng bộ nguồn và duyệt oracle. |
| TRF-01.5 | Các nhánh evidence recovery và rủi ro purge trong risk register/test plan. | **Blocked:** Product/BA/Security/BE duyệt TTL và hướng dẫn client; Transfer/Ledger/Audit/Ops duyệt protocol, evidence, cập nhật atomic response/idempotency và runbook. Trước đó giữ nguyên và alert khi PENDING có ledger; không tự sửa ledger hoặc purge PENDING. |
| TRF-01.6 | OI-CON-01–13, MOD-OI-01–09 và audit C2/C1 được theo dõi trong risk register; hai phương án audit có mô tả giao tiếp và vector review. | **Blocked:** các owner tương ứng chưa xác nhận policy/version/throughput, audit staging và rollback, worker, notification và correlation. |
| TRF-01.7 | Test plan cùng trace và danh mục reviewer/evidence. | **Blocked:** provider/consumer và QA chưa duyệt vector/oracle; chưa có provider nghiệp vụ thật, kết quả test PostgreSQL tích hợp, artifact hay review để nghiệm thu. |

## Quy trình chuyển sang Verified

1. Transfer/Vũ trình các vấn đề còn mở trong risk register tới người có thẩm quyền; ghi ngày, người quyết định, phương án, lý do, nguồn, phạm vi và evidence vào [DECISIONS.md](../../../../../../DECISIONS.md) **sau khi có quyết định**. DEC-TRF-001/002 chốt thành phần tổng ngày và ngưỡng mặc định, chưa thay review protocol hoặc các quyết định khác.
2. Chủ nguồn cập nhật `03/04/07/09`, OpenAPI, overview, `05` và thiết kế liên quan theo từng quyết định và quyền sửa được giao; liên kết revision/PR và reviewer vào hồ sơ. Nếu phát hiện giao tiếp thực sự chưa được mô tả trong `05`, đề xuất phương án tại đó và yêu cầu provider/consumer review trước khi giả định có API.
3. Provider và Transfer chốt interface/version, vector và lỗi; chạy fake theo cùng contract khi provider chưa có, sau đó chạy provider thật và PostgreSQL nhiều connection/failure injection. Lưu evidence theo `09` §10; mock không chứng minh transaction, lock hay tích hợp.
4. QA và các reviewer liên quan xác nhận từng AC, dẫn tới quyết định, PR và evidence có thể tái lập. Chỉ đánh dấu backlog `[x]` khi tất cả AC được Verified và review hoàn tất.

Không có test nghiệp vụ hoặc integration nào được báo là đã chạy trong hồ sơ chuẩn bị này. Không triển khai money path, migration hay provider trong BE-TRF-01.
