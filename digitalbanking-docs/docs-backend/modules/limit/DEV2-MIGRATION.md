# Chuyển feature limit/admin lên nền dev2 sạch

> **Ngày:** 16/11/2026
> **Nhánh làm việc:** codex/limit
> **Nền:** origin/dev2 tại 56210cc — Fixing dependent transfer docs
> **Nhánh dự phòng giữ nguyên:** phuong/limit tại f285b08
> **Trạng thái:** Đã chuyển 6 commit feature, giải quyết conflict và kiểm chứng lại trên nền sạch; chưa push/merge vào dev2.

## 1. Commit được chuyển

| Commit gốc | Commit trên nền sạch | Nội dung |
|---|---|---|
| 7cc9277 | d9a9423 | Delivery plan và liên kết docs |
| c8f0253 | bd3e4b9 | Limit E1 |
| 168ddbf | 037b49d | Limit E2 |
| 9148b0c | f56f09b | Provider E4 LIM-07/08 |
| ce7b3c4 | 3890c37 | Hồ sơ thiết kế/thực thi limit |
| f285b08 | f72646b | Endpoint E4 LIM-09/10, đã bỏ patch lên implementation audit bị loại khi resolve |

Không lấy merge 019f792. Các commit thuộc cụm bị loại a685783, c570986, 14c5929, e59aeac, 16840ae, b614959, 833ee24 không là tổ tiên của nhánh mới. Không lọc theo tên tác giả trên toàn bộ repo: những commit đã thuộc baseline dev2 vẫn được giữ.

Cập nhật contract/transfer docs mới của 56210cc được giữ. Conflict changelog được ghép thay vì ghi đè: API giữ 1.2 draft và thêm 1.3 E4; modules contract giữ 0.2/0.3 của dev2 và thêm 0.4 mapping E4.

## 2. Dependency audit được xử lý thế nào

Dev2 hiện chưa có implementation audit thay thế. Không phục hồi AuditRecorder, AuditActionCatalog, AuditEventValidator, V2__create_audit_log, fraud hoặc notification từ nhánh cũ. Adapter ApprovalAuditService phụ thuộc writer cũ cũng không được mang sang.

Giữ candidate public contract audit.api.AuditModuleApi do feature tạo. Admin nhận ObjectProvider cho contract này: thiếu implementation production thì fail-closed 503 trước mutation, không tự dùng fake. Owner audit cung cấp implementation mới theo contract sau.

Các test dùng SqlAuditFixture và bảng limit_test.audit_event chỉ trong src/test. Đây là test double dùng SQL để chứng minh consumer/caller transaction commit hoặc rollback cùng approval/config/usage. Không có hash chain, append-only, retention hay bảo đảm audit production; kết quả này không nghiệm thu audit thật. Các test fixture cũ tham chiếu AuditRecorder được chuyển sang contract double, không bỏ/skip ca rollback.

## 3. Kiểm chứng mới

Chạy từ digitalbanking-backend/:

```sh
./gradlew clean test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2
./gradlew build -x test --offline --console=plain --max-workers=2
```

- Clean test: BUILD SUCCESSFUL trong 52 giây; **213 tests / 0 failure / 0 error / 0 skip**.
- Package: BUILD SUCCESSFUL; kiểm tra JAR không chứa implementation audit/fraud/notification cũ, V2 migration cũ hoặc test fixture.
- Có clean trước test để loại class/resource còn sót từ checkout cũ; không chạy full regression ngoài scope.
- Số 213 khác 294 trên nền cũ vì 82 tests của implementation audit bị loại không thuộc nền sạch, và bổ sung 1 vector thiếu audit provider. Không dùng kết quả 294 cũ để xác nhận readiness của nhánh mới.
- XML/HTML ở build/test-results/test và build/reports/tests/test; log tạm /tmp/limit-dev2-scoped.log, /tmp/limit-dev2-package.log. Lần chạy sau có thể ghi đè.

## 4. Điều kiện bàn giao/merge

- PR phải từ codex/limit sang dev2, không merge lại phuong/limit vào nhánh sạch.
- Owner audit review candidate API và nối implementation mới; chạy contract/integration thật cho C2/C3, hash chain và quyền ghi. Audit hiện Blocked.
- Identity/session/step-up, StaffDirectory, customer/account/ownership, GET E3 và transfer/ledger thật vẫn Blocked như trước. Fake trong test không đóng các acceptance đó.
- Chưa thêm FK tới các bảng provider chưa có; historical approval references còn cần review.
- Migration limit/admin giữ V3–V6; V2 không được tạo placeholder. Team cần thống nhất version của migration mới trước khi ghép các module viết lại. Không tự tái sử dụng DB đã chạy V2 cũ; dùng DB kiểm chứng riêng hoặc review migration dữ liệu, không tự xóa DB của người dùng.

AGENTS.md được giữ nguyên từng byte và không nằm trong commit feature/điều chỉnh. Không push hoặc sửa lịch sử nhánh shared.
