# Nhật ký ghép và tích hợp FE–BE v1

Tài liệu này ghi mốc thực tế của nhánh tích hợp `codex/fe-be-v1-integration`. Kế hoạch và gate theo [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]]; vấn đề chi tiết theo [[digitalbanking-docs/docs-frontend/management/11-fe-be-integration-issues.md]]. Mỗi dòng phải dẫn phiếu FE-RUN và SHA thực tế; không dùng mock hay báo cáo BE độc lập làm bằng chứng API/browser FE.

## Quy tắc nguồn và vòng đời

- Đích FE–BE là contract BE `dev2` OpenAPI **v1.1.0** `/api/v1`, theo quyết định người phụ trách FE ngày 22/11/2026. Ghim SHA BE, FE và hash YAML mỗi lần tích hợp. FE không tự thay đổi response, quyền hoặc API để phù hợp fixture; nếu BE/common/security khác nhau, ghi vấn đề, owner và chờ xác nhận phần phụ thuộc. Bàn giao BE được ưu tiên theo yêu cầu 23/11, không tự coi mâu thuẫn security đã được phê duyệt.
- Mỗi task: mở phiếu theo [[digitalbanking-docs/docs-frontend/templates/task-run-template.md]] → đối chiếu contract/AC/FET → code/test phần rõ → chạy checks và API/browser nếu đủ điều kiện → cập nhật tracker, nhật ký, sổ vấn đề, feature/design/guide → một commit riêng. Ghi cả lỗi và kiểm tra chưa chạy.
- UI chưa duyệt thì giữ gate của phần tương ứng. Merge source là một mốc kỹ thuật; tích hợp từng chức năng và merge vào nhánh đích cần API thật, browser/evidence và xác nhận merge cụ thể theo FE-TEST-03.

## Mốc thực tế

| Ngày | Task / phạm vi | FE/BE SHA và contract | Kết quả / giới hạn | Bằng chứng |
|---|---|---|---|---|
| 23/11/2026 | M0 ghép source và giải bốn conflict | merge `282de76`, parents `5a1f310`/`740ab5f` | Source đã ghép trên nhánh cách ly; FE typecheck/build và BE compile đạt; generated types v1 lệch sau merge, diff-check toàn bộ báo whitespace lịch sử; không API/browser. | [[digitalbanking-docs/docs-frontend/evidence/FE-REL-V1-SOURCE/2026-11-22-5a1f310/run-01.md]] |
| 23/11/2026 | M1 lát schema/fixture độc lập | `371ea10`, BE `740ab5f`, v1.1.0 | Generated types v1 đồng bộ, tách fixture v1/v2; 195 tests, typecheck/lint/build/API check đạt. Auth v1 mock 200 cũ bỏ; client/session BE thật chưa nối. | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-V1/2026-11-23-282de76/run-01.md]] |
| 23/11/2026 | Push nhánh tích hợp | `origin/codex/fe-be-v1-integration@371ea100ead82ed6422494874912e8d7fa4524aa` | `git push -u origin codex/fe-be-v1-integration` exit 0; `git ls-remote` xác nhận SHA. Không tạo PR hay merge main. | [[digitalbanking-docs/docs-frontend/evidence/FE-REL-V1-TRACKING/2026-11-23-371ea10/run-01.md]] |

## Mẫu dòng cho task tiếp theo

| Ngày | Task / phạm vi | FE/BE SHA và contract | Kết quả / giới hạn | Bằng chứng |
|---|---|---|---|---|
| — | — | FE SHA, BE SHA, YAML version/hash, môi trường synthetic | Kết quả thực tế; API/browser và UI được duyệt hay chưa; issue ID | Phiếu run, log/screenshot thật hoặc báo chưa chạy |
