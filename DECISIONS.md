# Quyết định dự án

## DEC-TRF-001 — Thành phần tổng ngày để đánh giá step-up

- **Ngày:** 15/11/2026.
- **Người quyết định:** Codex, theo chỉ đạo của người điều hành trong cuộc trao đổi BE-TRF-01 ngày 15/11/2026: “Tự đưa ra quyết định phù hợp dựa trên tài liệu hiện có và chỉnh các tài liệu liên quan”. Quyết định này không phải xác nhận review của BA, Product, Security hay các provider.
- **Phạm vi:** đối với điều kiện step-up dựa trên tổng chuyển tiền trong ngày, tính tổng dự kiến gồm số tiền chuyển thành công đã ghi nhận trong ngày + số tiền của các lệnh đã tạo và còn đang xử lý có hiệu lực + số tiền của lệnh đang xét; mỗi lệnh chỉ được tính một lần. Lệnh đã FAILED không tiếp tục đóng góp vào tổng dự kiến. Phạm vi khách hàng/tài khoản, ngày nghiệp vụ, ngưỡng số, toán tử so sánh và thay đổi cấu hình chưa được quyết định trong mục này.
- **Lý do:** tránh hai lệnh đồng thời cùng nhìn thấy tổng đã hoàn tất thấp rồi bỏ qua step-up khi tổng dự kiến vượt ngưỡng; có thể yêu cầu OTP bảo thủ nếu lệnh đang xử lý sau đó thất bại. Phù hợp với phương án cộng reservation tại `digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md` §§4.3, 6.1, đồng thời giữ yêu cầu `428` trước khi tạo transfer theo SRS AC-033 và `07` §6.
- **Các phương án:** chỉ tính lệnh đã thành công — không chọn do không tự giải quyết race của các lệnh đồng thời; tính cả lệnh đang xử lý — chọn cho phạm vi trên. Cơ chế guard/reservation hoặc queue là **đề xuất kỹ thuật**, chưa được quyết định, chưa chứng minh lock order/atomicity và chưa là phê duyệt triển khai.
- **Nguồn và phạm vi ảnh hưởng:** `03. SRS.md` AC-033; `07. API contracts.md` §6; `design/03. fraud-detection-rules.md` §§4.3, 6.1; `design/05. Modules contract.md` §10.1, MOD-OI-01; Transfer `design.md` và BE-TRF-01. Các ngưỡng/điều kiện khác nhau trong `design/03` và `design/04` không được mục này phân xử.
- **Theo dõi:** Identity, Limit, Transfer, Architect, BA/Product, Security và QA review ngữ nghĩa còn lại, contract/protocol, race `428`/idempotency, migration và evidence PostgreSQL/provider thật. MOD-OI-01 và release money path vẫn Blocked cho đến khi các phần này được review và chứng minh. Không coi quyết định này là sign-off thay các owner.

## DEC-TRF-002 — Ngưỡng tiền mặc định yêu cầu step-up

- **Ngày:** 15/11/2026.
- **Người quyết định:** Codex, theo chỉ đạo người điều hành trong cuộc trao đổi BE-TRF-01: “Về ngưỡng tiền, chọn ngưỡng phù hợp nhất và sửa các tài liệu liên quan.” Quyết định này không đại diện review hay sign-off của các owner.
- **Quyết định:** cấu hình mặc định MVP yêu cầu step-up khi **số tiền một lệnh ≥ 10.000.000 VND** hoặc **tổng chuyển dự kiến trong ngày > 20.000.000 VND**. Hai điều kiện độc lập; các trigger rủi ro khác vẫn được đánh giá. Thành phần tổng ngày theo DEC-TRF-001; ngưỡng step-up không phải hạn mức chuyển tiền và không tự chấp thuận lệnh vượt limit. Ngưỡng phải cấu hình được, có kiểm soát thay đổi; không tạo hai cấu hình độc lập cho cùng trigger.
- **Biên:** `9.999.999,99` VND không kích hoạt điều kiện đơn lệnh; `10.000.000,00` kích hoạt. Tổng ngày `20.000.000,00` không kích hoạt điều kiện tổng ngày; `20.000.000,01` kích hoạt. Các kỳ vọng này chỉ xét điều kiện tương ứng, giả định các trigger khác không kích hoạt và giao dịch hợp lệ theo policy tiền tệ/hạn mức.
- **Lý do và phương án:** chọn phương án đã được Security ghi `OI-SEC-02` Resolved tại `design/04` §4.4/§13, khớp default step-up hiện có và tránh thay đổi ngưỡng bảo mật đã mô tả; `design/03` từng nêu OTP trên 5 triệu, nay không áp dụng làm trigger OTP riêng. Cả hai là thiết kế mô phỏng; SRS/API yêu cầu ngưỡng cấu hình, không ấn định con số. Không tuyên bố đây là quy định ngân hàng thực tế hay thay thế đánh giá rủi ro.
- **Ngoài phạm vi:** scope customer/account, ngày nghiệp vụ/timezone, PIN, hạn mức tối thiểu/tối đa từng giao dịch, daily cap, phương thức xác thực khác, rule fraud/velocity, và protocol xử lý đồng thời không được quyết định tại đây. Xác thực và lỗi vẫn theo `07` §6; việc triển khai, versioning cấu hình và phối hợp provider phải review/test.
- **Nguồn và ảnh hưởng:** `03. SRS.md` §2.13.6, AC-033; `07. API contracts.md` §6; `design/03. fraud-detection-rules.md` §§4–6, 11; `design/04. security.md` §§4.4, 13; `design/05. Modules contract.md` §10.1; `docs-backend/deployment/configuration.md` và Transfer BE-TRF-01. Không tự đóng MOD-OI-01: guard/reservation, race `428`, scope/ngày và evidence provider/PostgreSQL vẫn cần được chứng minh.
## AUD-01/AUD-02 — Quyết định triển khai độc lập

These entries record the current implementation and proposals for coordination. They do not imply owner approval.

## Scope

- Independent AUD-01/AUD-02 component work may proceed without owner approval.
- Public API, caller integration, RBAC, export, C3, checkpoint and external anchor remain blocked until owner contracts are reviewed.

## Decisions used for independent component work

- `AuditRecorder` accepts only C1/C2 events through `Propagation.MANDATORY`; C3/C4 are rejected before database locking.
- JDBC/native SQL remains in `audit.adapter.out` for hash-chain locking, JSONB projection, fixed audit projections and bounded verification scans. Spring Data JPA is not forced onto append-only or PostgreSQL-specific paths.
- `AuditQueryPort` and `AuditRecordWriterPort` are outbound persistence ports in `audit.application.port`. They are not public cross-module APIs.
- `AuditModuleApi` is not created or exposed before owner review of event DTOs, actor context, error mapping and transaction semantics.
- Audit read projection fails closed. Raw JSON does not leave the internal persistence boundary; public API/RBAC is not implemented in this independent scope.
- Writer canonical payload limit remains 32 KiB. Reader allows bounded PostgreSQL `jsonb::text` representation expansion, then enforces compact JSON <= 32 KiB before projection. This does not alter hash input or stored evidence.
- Audit hash v1 remains a proposal. Current tests characterize implementation behavior; they are not production protocol approval or cross-language golden-vector sign-off.
- Testcontainers PostgreSQL evidence is disposable component evidence. `ddl-auto=none` in isolated Audit tests does not prove full production `ddl-auto=validate` readiness.
- Missing provider dependencies are not replaced with production stubs. Fake data is allowed only in tests and is not used to claim RBAC or caller integration.

## Open owner decisions

- Hash v1 formula, field set, delimiter and golden vectors: DB/Common and Architecture.
- `AuditModuleApi`, actor role/scope and principal source: Identity, Architecture and business callers.
- C3 standalone audit and security fallback: Identity, Security and Architecture.
- Export byte/resource/CSV policy: DB, Security and Architecture.
- Migration role/FK coordination, scheduled verification, checkpoint and external anchor: DB, Architecture and Operations.
- Transfer/Ledger/Fraud/Admin caller integration fixtures: module owners.
