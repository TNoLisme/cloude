# Handoff M4 cho Security và BA

| Thuộc tính | Giá trị |
|---|---|
| Ngày lập | 27/11/2026 |
| Người yêu cầu | Người phụ trách FE; chat 27/11/2026 yêu cầu giải quyết GAP và merge M4 |
| Trạng thái | Baseline handoff FE đã chốt theo ủy quyền người phụ trách FE báo lại ngày 27/11/2026; không ghi là Security/BA trực tiếp phê duyệt |

## Quyết định FE đã chốt

Ngày 27/11/2026, người phụ trách FE báo BE và Security cho phép mình tự chốt handoff. Người phụ trách FE chấp nhận baseline đề xuất cho lát M4: chỉ đọc; không phone search, fraud decision, audit export hay mutation. FE dùng role trong phiên để mở khu vực staff; BE kiểm tra permission trên từng API; FE coi `403` là kết quả có thẩm quyền, hiển thị lỗi và loại dữ liệu tương ứng khỏi cache; không thêm capability vào `UserSummary` v1. Đây là quyết định handoff của người phụ trách FE theo ủy quyền được báo lại trong chat, không phải tuyên bố Security/BA đã trực tiếp duyệt hoặc sửa tài liệu nguồn.

## Ranh giới quyết định và việc còn mở

Quyết định handoff ở trên đủ để FE triển khai theo contract hiện có trong phạm vi được quyền; không cần chờ câu trả lời chính sách khác để ghi baseline FE. Những điểm sau là điều kiện kiểm chứng nguồn/runtime và đồng bộ owner:

1. **Role → permission:** common 07 và BE-INT-03 là baseline đối chiếu; SRS §4.2/Security §5.1 còn khác ở Auditor `CUSTOMER_READ`. M4 không mở customer profile cho Auditor. Không tự sửa tài liệu Security/BA dưới danh nghĩa owner.
2. **Enforcement:** thử trực tiếp từng API với quyền đủ/thiếu; role trong FE chỉ điều khiển điều hướng, không cấp quyền. Kiểm tra 403, cache và đổi phiên trước khi đưa lát vào merge.
3. **Approval:** chỉ GET metadata theo OpenAPI, visibility maker/checker do server kiểm, 404 ngoài scope; không render `payload/result`, không approve/reject. Tài liệu BE đang bất nhất trạng thái runtime; chỉ nhập lát sau khi xác minh endpoint.
4. **Fraud:** chỉ GET read theo 07 §8.7, không POST review trong M4. Xác minh endpoint/response; phần ngoài scope không được ghi hoàn thành FR-044–049 nếu AC yêu cầu mutation.
5. **Audit:** chỉ GET list/detail read-only theo 07 §8.6; không export trong M4. Xác minh runtime/masking; export/retention/legal hold vẫn ngoài lát này.

## Ma trận đề nghị cho M4 read-only

| Role | Customer profile exact-ID | Account exact-ID | Approval metadata | Fraud read | Audit list/detail |
|---|---:|---:|---:|---:|---:|
| CUSTOMER | Self APIs theo M2; không vào staff routes | Self APIs theo M2; ownership server-side | Không | Không | Không |
| OPERATIONS | CUSTOMER_READ | ACCOUNT_READ | Theo APPROVAL_READ/scope; cần xác nhận runtime | FRAUD_REVIEW | AUDIT_READ |
| ADMIN | CUSTOMER_READ | ACCOUNT_READ | Theo APPROVAL_READ/scope; cần xác nhận runtime | FRAUD_READ | AUDIT_READ |
| AUDITOR | Không CUSTOMER_READ | ACCOUNT_READ | Không theo current OpenAPI; xác nhận nếu có khác biệt | FRAUD_READ | AUDIT_READ |

Các route và action là phân biệt riêng. FE dùng role chỉ để điều hướng theo baseline handoff; server là nơi quyết định quyền trên từng request. Bảng là phạm vi FE handoff bám common/BE hiện có, không thay thế policy của source owner.

## Tài liệu nguồn cần owner cập nhật

- BE/BA/Security: `docs-common/03. SRS.md` §4.2, `docs-common/design/04. security.md`, và nếu đổi semantics thì `docs-common/07. API contracts.md` + OpenAPI theo versioning.
- BE Identity: permission seed/authority map và bằng chứng runtime/session refresh.
- FE: `management/03-work-tracker.md` GAP-07-A..D, `management/11-fe-be-integration-issues.md`, FE-05/06/07 feature và phiếu M4.

Ghi quyết định handoff của FE theo thông tin ủy quyền nêu trên; không ghi Security/BA đã trực tiếp duyệt. Nếu policy nguồn cần thay đổi, owner cập nhật tài liệu/commit tương ứng. Trước merge, chạy FET-10/12/13 trên từng lát/role có runtime với direct URL, quyền thiếu, cross-scope, 403/404, masking và cache; lát không có runtime/evidence thì loại khỏi merge và ghi blocker cụ thể.
