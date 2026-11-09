# Kiểm thử và nghiệm thu FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-TEST |
| Phiên bản / ngày | 1.0 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Nguồn và trách nhiệm

Nguồn [[digitalbanking-docs/docs-common/09. Test plan.md]], [[digitalbanking-docs/docs-common/03. SRS.md]] và [[digitalbanking-docs/docs-backend/testing/01-backend-test-matrix.md]]. FE test chứng minh hành vi browser, BE/QA cung cấp money/ledger/audit invariant và D1–D5 theo test plan chung.

## FE-TEST-01 — Mức kiểm thử

| Mức | Công cụ theo stack đích | Kiểm chứng |
|---|---|---|
| Unit | Vitest | Money/ID/time, error mapping, capability, intent/state transitions |
| Component | Testing Library + MSW | Form, loading/empty/error, submit lock, challenge/expiry, role UI |
| Contract | OpenAPI generation/check và mock bám schema | OperationId/request/response, nullability, page, enum, Problem Details |
| Integration | API thật trên môi trường QA riêng | Session/CSRF, ownership, transfer/replay, approvals/fraud/export |
| Browser E2E | Playwright | Journey critical, direct URL, reload/back, responsive, session isolation |
| Review chất lượng | Browser, bundle analyzer/audit công cụ được chọn | Keyboard/focus/contrast/zoom, bundle và lỗi console |

Mock dùng dữ liệu tổng hợp, test behavioral qua role/label, chờ trạng thái quan sát được. Lỗi transport và race response cũ phải được mô phỏng cho feature có rủi ro đó.

## FE-TEST-02 — Ma trận kịch bản

| Test ID | Hành vi | Nguồn | Công việc |
|---|---|---|---|
| FET-01 | Password login → challenge nếu cần → session → reload → logout | FE-01, AC-001/003/004 | FE-01-01 |
| FET-02 | OTP sai/hết hạn/locked, resend, passcode/FIDO fallback | FE-01, AC-002/003/033 | FE-01-01/02 |
| FET-03 | Profile/account ownership, masked data, số dư chính xác | FE-02, AC-005/023/024 | FE-02-01 |
| FET-04 | Own/intra-bank transfer, validation, limit và receipt | FE-03, AC-006–009/014 | FE-03-01 |
| FET-05 | 428 trước create, verify rồi cùng key/body, challenge mismatch | FE-03, AC-003/033 | FE-03-01 |
| FET-06 | Double click/timeout/same-key replay/in-progress/conflict | FE-03, AC-010/011, D2 | FE-03-02 |
| FET-07 | PENDING/unknown, refresh/reload, reconcile rồi terminal | FE-03, AC-012/013/034, D3 | FE-03-02 |
| FET-08 | History filter/page/UTC boundaries/read-only/detail | FE-04, AC-014–016 | FE-04-01 |
| FET-09 | Customer limit và limit maker/checker, stale approval | FE-05, AC-017/028 | FE-05-01/02 |
| FET-10 | Staff customer scope và fraud review theo quyền | FE-06, AC-026/027 | FE-06-01/02 |
| FET-11 | Audit filter/detail/export, 403, masking/correlation | FE-07, AC-020–024 | FE-07-01 |
| FET-12 | Role/privilege change, self-approve từ chối | FE-08, AC-028 | FE-08-01 |
| FET-13 | Cross-user cache, CSRF, direct URL, logout đa tab | FE-AUTH, AC-024/030 | FE-REL-01 |
| FET-14 | 360/768/1440, keyboard/focus/zoom, console và bundle | FE-UI, NFR-028/029 | FE-REL-02 |
| FET-15 | Build sạch, SPA deep link, /api routing, HTTPS cookie | Local guide, AC-032 | FE-REL-03 |

Từng lần chạy ghi Pass/Fail/Blocked, commit FE/BE, contract version, browser/viewport và artifact. Bảng này là kế hoạch test, kết quả hiện tại ở tracker/evidence.

## FE-TEST-03 — Điều kiện hoàn thành

Một công việc Hoàn thành cần đủ đầu ra trong tracker, tiêu chí đặc tả được kiểm chứng, review PR, checks bắt buộc đạt, docs đồng bộ và evidence theo commit. Feature tích hợp có API thật và browser evidence cho luồng critical. Mock-only được ghi mức chứng minh tương ứng và giữ công việc tích hợp mở.

PR gate đích theo stack gồm lint, typecheck, unit/component tests, build, format/check generated types. Scripts chính xác được chốt tại FE-FND-01/02 và ghi local guide khi có package. Integration/E2E chạy theo rủi ro và gate feature. Tỷ lệ coverage 80% trong SRS còn là đề xuất, quyết định áp dụng phải ghi tại nguồn chung.

Must AC bị chặn chặn nghiệm thu phạm vi tương ứng. Ngoại lệ cần owner duyệt với lý do, ảnh hưởng và trigger mở lại theo test plan chung.

## FE-TEST-04 — Môi trường và bằng chứng

Chỉ dùng dữ liệu tổng hợp và môi trường QA disposable cho smoke tạo dữ liệu/tiền mô phỏng. FE/BE commit và schema phải khớp. Ghi backend URL dạng an toàn, phiên bản trình duyệt, seed identifier và cách tái lập, secret lấy từ kênh riêng.

Evidence theo [[digitalbanking-docs/docs-frontend/evidence/README.md]]. Test report có expected/actual, command/script version và timestamp. Known defect có severity, reproduce, ảnh hưởng AC, owner và link task. Ảnh screenshot chứng minh UI, invariant tiền cần backend report liên quan.

## 2. Nghiệm thu và bàn giao

FE-REL-01/02 tổng hợp kết quả FET theo phạm vi duyệt. FE-REL-03 thực hiện Cloud smoke sau M4. Demo theo [[digitalbanking-docs/docs-frontend/guides/02-demo-and-handover.md]], trạng thái theo [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].
