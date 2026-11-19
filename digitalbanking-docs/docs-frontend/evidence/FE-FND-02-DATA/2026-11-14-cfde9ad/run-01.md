# Phiếu FE-FND-02-DATA / run-01

## 1. Đầu vào và phạm vi

Ngày 14/11/2026, Asia/Bangkok; Codex. Baseline frontend `cfde9ad205f4bc341d979f58b28bc2a065438fe2`. Working tree chứa thay đổi FE-FND-01-TOOLING chưa commit và đang chờ review; không ghi nhận người dùng đã duyệt task đó chỉ từ yêu cầu tiếp tục. BE: không áp dụng. Contract OpenAPI 1.0.0, SHA256 71EFC546FB6645BE128EC83D391706343A6FF6B1FA6991910E42770D62EF796A; không sửa YAML/ngữ nghĩa API.

Nguồn đã đọc: AGENTS.md; FE README/tracker/roadmap/FE-RUN/template; API-DATA-01/FE-ARCH-01/FE-UI-05/FE-TEST-01/03; FE-02; shared design; 07 mục 2.3–2.4; YAML schemas ID/Money/Currency. Theo yêu cầu “tiếp tục thực hiện task”, thực hiện phần data utilities độc lập của FE-FND-02, không vượt các gate tích hợp đang mở của task cha.

## 2. Kiểm tra đầu vào

Money theo YAML: không âm, tối đa 17 chữ số phần nguyên, đúng hai chữ số thập phân, không separator/exponent/leading zero. ID: chuỗi `[1-9][0-9]{0,18}`. Không tự thêm min/max nghiệp vụ transfer hoặc ép ID theo Number/BIGINT DB ngoài pattern contract. API-DATA-01 cho phép tính tiền bằng integer minor units BigInt. Format tiếng Việt giữ đủ hai chữ số của Money, chưa nối vào UI legacy vốn nhận số nguyên.

GAP-05/07/08 về session/quyền/step-up chưa được giải quyết; không chạm phần phụ thuộc. Các luồng legacy thiếu contract giữ trong đợt tích hợp đầu, chờ BE/BA theo quyết định người dùng ở lần trước. Task tooling và task cha chưa tự đóng. Browser không áp dụng vì không sửa UI/render hiện tại.

## 3. Đầu ra và kiểm tra

| Đầu ra | Expected | Actual / kết quả |
|---|---|---|
| Money utility | Parse/serialize bằng BigInt, boundary đúng schema, không làm tròn | Pass — unit boundary/precision |
| ID utility | Chỉ nhận string đúng schema, giữ ID vượt MAX_SAFE_INTEGER nguyên vẹn | Pass — giữ nguyên các ID 19 chữ số |
| Unit/contract boundary | Decimal chính xác, vượt precision, input sai loại/scale, ID lớn | Pass — 47 tests mới: 29 Money, 18 ID |
| Regression gate | lint/typecheck/API/format/test/build | Pass — pnpm check: 8 file, 66 tests và toàn bộ gate đạt |
| Docs/tracker | Ghi đúng phần độc lập, task cha và integration còn mở | Đã cập nhật; chờ review |

## 4. Kết quả lần chạy

FE-FND-02-DATA Chờ review. Mức chứng minh: unit + schema boundary + regression local, chưa có API thật hoặc browser. Không có commit/push/merge trong lần này nếu chưa được thực hiện và ghi rõ. Reviewer: chờ người phụ trách FE; không tự gán phê duyệt.

## 5. Bằng chứng kiểm tra

Windows/PowerShell, Node 22.14.0/pnpm 10.34.9, dữ liệu tổng hợp từ boundary của schema, không có tài khoản thật. Lệnh targeted: `node node_modules/vitest/vitest.mjs run src/shared/lib` (47 tests đạt). Lệnh gate thực tế: `npm exec --yes --offline --package=pnpm@10.34.9 -- pnpm check` trong digitalbanking-frontend; [checks.log](test-report/checks.log) ghi lint/typecheck/API/format/66 tests/build đạt. Build giữ cảnh báo annotation Zod/bundle lớn từ baseline, không coi đây là release gate đã hoàn thành. [Manifest source](test-report/source-manifest.json) ghi hash input thực tế của working tree chưa commit.

Thực hiện: moneyToMinorUnits/minorUnitsToMoney/formatMoney/isMoney và parseApiId/isApiId. Guard kiểm tra toàn bộ chuỗi khớp pattern để không chấp nhận newline cuối theo đặc tính `$` của JavaScript regex. Serialize từ chối số âm/overflow; amount >0 và limit chính sách không được tự thêm vào utility chung. Formatter không thay null/missing bằng 0.

Các helper chưa được nối vào UI legacy hoặc API transport. Không thay form, quyền, route, session, payload hay trạng thái giao dịch. Đang chờ review phần code/data utilities; FE-FND-02 tiếp tục mở phần client/session/query keys. FE-FND-01-TOOLING vẫn chờ review, không tự coi lời yêu cầu tiếp tục là duyệt task đó.

Đã hỏi người dùng về baseline FE/mock cho GAP-05 (DB_SESSION/Path=/ và LOGIN_NEW_DEVICE theo API, hoặc theo Security, hoặc chờ BE/Security). Chưa có quyết định tại thời điểm ghi phiếu; không làm phần session phụ thuộc trước khi có câu trả lời. Tích hợp BE và phê duyệt Security vẫn mở kể cả khi người dùng chọn baseline mock.
