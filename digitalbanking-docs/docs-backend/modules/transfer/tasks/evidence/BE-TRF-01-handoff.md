# BE-TRF-01 — Hồ sơ bàn giao và trạng thái nghiệm thu

**Trạng thái: baseline thiết kế v1 đã chốt theo ủy quyền; review và bằng chứng tích hợp cần thiết chưa có.** [DEC-TRF-001–015](../../../../../../DECISIONS.md) ghi các lựa chọn về ngưỡng, retry, validation, recovery, audit và concurrency theo chỉ đạo người điều hành; không thay review của provider/QA. Mốc rà soát 16/11/2026; kiểm tra revision và working tree khi tạo evidence thực thi.

## Tài liệu bàn giao

- [Task và tiêu chí nghiệm thu](../BE-TRF-01.md); [backlog](../README.md).
- [Matrix contract và trace](BE-TRF-01-contract-matrix.md): operation, ownership, ranh giới và điểm chờ provider/consumer review.
- [Danh mục rủi ro và yêu cầu quyết định](BE-TRF-01-risk-register.md): phương án, đầu mối, ảnh hưởng và nguồn; các lựa chọn đã được ghi, còn cần review và evidence.
- [Kế hoạch contract và integration test](BE-TRF-01-test-plan.md): vector, oracle, test double, test PostgreSQL thật và mẫu evidence.
- [Đề xuất phối hợp audit C2/C1](BE-TRF-01-audit-proposal.md): hai phương án giao tiếp và vector cần owner review.
- [Modules contract](../../../../../docs-common/design/05.%20Modules%20contract.md) §§3–14 và [concurrency](../../../../../docs-common/design/02.%20consistency-concurrency.md) §16.2 là nguồn giao tiếp và open issue; [API contracts](../../../../../docs-common/07.%20API%20contracts.md) §§5–6, 8.3 là baseline API. Theo thiết kế Transfer, `docs-common/01–11` ưu tiên hơn `docs-common/design/` và backend; xung đột giữa nguồn có thẩm quyền cần owner phân xử.

## Checklist theo AC

| AC | Hồ sơ đã chuẩn bị | Trạng thái còn thiếu / người theo dõi |
|---|---|---|
| TRF-01.1 | Matrix theo operation, owner, boundary và vector; `05` §13.4 / DEC-TRF-015 chốt baseline v1 về dữ kiện, nullability, outcome, transaction và fallback cho Transfer. | **Chưa Verified liên module:** baseline v1 đã được chọn; interface thực tế, review provider/consumer và cùng contract vectors chưa được xác nhận. Transfer/Vũ theo dõi; không khẳng định provider đã triển khai. |
| TRF-01.2 | DEC-TRF-004 chốt key global, replay status/body gốc 201/422, pending HTTP 409 và correlation; overview/YAML được đồng bộ để review. | **Blocked:** API/DB/FE/QA review, contract vector và integration; không giả định code/runtime đã hỗ trợ. |
| TRF-01.3 | DEC-TRF-001/002/008 chốt ngưỡng, guard customer và tính PENDING qua ngày; `05` có contract và vector race. | **Blocked / chặn release money path:** Identity/Limit/Architect/BA/Transfer/Security review, migration và PostgreSQL chứng minh lock order, binding, expiry, 428/race và không double count. |
| TRF-01.4 | DEC-TRF-003 chốt oracle preflight/sau T1, credit rollback/savepoint và Tfail có fencing; trạng thái không rõ không bị đoán. | **Blocked:** BA/API/QA/Account/Transfer review, evidence failure injection, durable fence, crash và concurrent recovery. |
| TRF-01.5 | DEC-TRF-005/006 chốt TTL 24h từ terminal, purge và recovery khi đủ evidence; thiếu/lệch giữ thực trạng và alert. | **Blocked:** Product/Security/Transfer/Ledger/Account/Limit/Audit/Ops review, provider evidence, migration, test atomicity, purge và T-REC-02. |
| TRF-01.6 | OI-CON-01–13/MOD-OI-01–09 có trạng thái; DEC-TRF-007 chọn audit C2 theo flag ID rồi C1 sau business flush. | **Blocked:** provider review/test audit, review và test policy/version/throughput, worker và notification theo DEC-TRF-009–012. |
| TRF-01.7 | Test plan cùng trace và danh mục reviewer/evidence. | **Blocked:** provider/consumer và QA chưa duyệt vector/oracle; chưa có provider nghiệp vụ thật, kết quả test PostgreSQL tích hợp, artifact hay review để nghiệm thu. |

## Quy trình chuyển sang Verified

1. Transfer/Vũ trình các quyết định và vấn đề còn mở trong risk register để các owner review; cập nhật [DECISIONS.md](../../../../../../DECISIONS.md) khi có quyết định mới, ghi người, ngày, lựa chọn, lý do, nguồn, phạm vi và evidence. DEC-TRF-001–012 chưa thay review hoặc kết quả test.
2. Chủ nguồn cập nhật `03/04/07/09`, OpenAPI, overview, `05` và thiết kế liên quan theo từng quyết định và quyền sửa được giao; liên kết revision/PR và reviewer vào hồ sơ. Nếu phát hiện giao tiếp thực sự chưa được mô tả trong `05`, đề xuất phương án tại đó và yêu cầu provider/consumer review trước khi giả định có API.
3. Provider và Transfer chốt interface/version, vector và lỗi; chạy fake theo cùng contract khi provider chưa có, sau đó chạy provider thật và PostgreSQL nhiều connection/failure injection. Lưu evidence theo `09` §10; mock không chứng minh transaction, lock hay tích hợp.
4. QA và các reviewer liên quan xác nhận từng AC, dẫn tới quyết định, PR và evidence có thể tái lập. Chỉ đánh dấu backlog `[x]` khi tất cả AC được Verified và review hoàn tất.

Evidence test candidate đã thực hiện và giới hạn tại [BE-TRF-04/05](BE-TRF-04-05-candidate.md); fake và fixture synthetic không chứng minh provider/integration thật. Không triển khai provider hoặc money path production trong BE-TRF-01.

## Bàn giao độc lập theo DEC-TRF-015

Baseline v1, dữ kiện recovery tối thiểu, fallback manual khi thiếu evidence, contract vector và compatibility đã ghi tại `05` §13.4. Consumer được phát triển/test ngay với fake test-only. Chốt thiết kế là quyết định của người điều hành; các yêu cầu review, giao diện provider thực và bằng chứng transaction/integration vẫn theo AC gốc, không được báo là hoàn tất hoặc ký thay owner.
