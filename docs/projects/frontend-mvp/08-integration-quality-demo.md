# FE 08 — Tích hợp, chất lượng và demo

Trạng thái: kế hoạch kiểm chứng; chưa có lần chạy. Nguồn [roadmap](00-roadmap.md), [BE integration](../backend-mvp/07-fe-contract-integration.md), [BE acceptance](../backend-mvp/08-quality-demo-deployment-acceptance.md).

## Ma trận nghiệm thu

| Nhóm | Mock/component | Browser + BE thật |
|---|---|---|
| Foundation | URL/header/Problem, cache isolation, route guard, 204, single-flight refresh | Cookie/CSRF/proxy và phiên thực |
| Auth | Validation, roles, OTP/429, anti-enumeration copy | Register/login/PIN, SMS/EMAIL recovery, refresh bị thu hồi |
| Account | Empty/error/blocked/format tiền | Ownership, balance từ DB |
| Transfer | PIN, OTP, 4 status, timeout và cùng key | Một debit qua retry/confirm; đúng ngưỡng 5M |
| History | Cursor/reset filter/UTC/loading | Source mọi status, destination chỉ COMPLETED |
| Operator | Exact search, seed/key, reason/status | Counter creation, seed, block/unblock có audit |
| Audit/risk | Read-only role/filter/fallback | Audit hợp lệ, 2 rule có flag theo dataset |
| Responsive | Viewport và keyboard/focus | Kiểm tra hành trình chính trên trình duyệt |

Typegen/check dùng cùng OpenAPI và generator BE 07. Typecheck, production build, unit/component, contract fixture validation, E2E là gate riêng. Tên script/lệnh được ghi đúng theo package manager thực tế khi scaffold; không ghi pass nếu chưa chạy. Test runner đề xuất nằm trong roadmap, chưa được cài trong task docs.

## Kịch bản demo

1. Customer mới đăng ký bằng phone/email + OTP local, login và setup PIN.
2. Operator tra cứu chính xác, seed tiền demo bằng key ổn định.
3. Customer xem balance; resolve người nhận; transfer 50.000 bằng PIN; xem detail/history và balance mới.
4. Transfer 6.000.000 bằng PIN + OTP; thử confirm/retry để chứng minh một lần chuyển.
5. Inject response timeout sau commit trong môi trường test BE, FE hiển thị chưa biết kết quả và reconcile cùng key/ID.
6. Sai OTP lần 5 trả 409 STATE_CONFLICT; gửi OTP lỗi trả 503 và FAILED/OTP_DISPATCH_FAILED; refresh history xác nhận.
7. Operator khóa đích; transfer bị từ chối; mở khóa; Auditor xem audit. Risk dataset kích hoạt hai rule đã có.
8. Recovery hai kênh, xác nhận session cũ không refresh được; test role switching và cache clearing.

Account numbers đầy đủ lấy từ fixture seed BE, không khôi phục từ masked response. OTP local mailbox chỉ test harness được phép đọc với guard theo backend; không ship mailbox UI hay credential thực. Dữ liệu disposable; không reset shared DB.

## Kiểm tra UI và dữ liệu nhạy cảm

Customer/Auth ở 360/768/1024/1440px; Staff functional acceptance ở 1024/1440 và kiểm tra không mất nội dung/nút ở 360/768. Kiểm tra zoom, tab order, focus dialog, label và thông báo lỗi; trạng thái không chỉ màu.

Kiểm tra storage/URL/log/test artifact không có password/PIN/OTP/access token. Query/mutation devtools và tracing phải tránh capture payload nhạy cảm. Ghi rõ screenshots/video có dữ liệu giả; che hoặc không ghi secret fields. Logout/user switch không hiển thị response/cache phiên cũ.

## CI và bằng chứng bàn giao

Pipeline dự kiến: install theo lockfile → OpenAPI/typegen drift → typecheck → component/fixture tests → build → E2E với isolated BE/database. Failure endpoint không được ngầm thay bằng mock. Build production không bật mock worker/mailbox UI. Same-origin ingress route FE deep link và `/api/v1` được smoke-test sau deploy.

Báo cáo mỗi lần chạy gồm commit FE/BE, version contract/toolchain, môi trường, fixture, command, outcome, screenshot, mismatch và skipped checks. BE p95 500ms không phải số đo thời gian tải trang FE; đo và báo riêng, không tự khẳng định đạt NFR backend từ UI test.

## Sổ trạng thái tích hợp ban đầu

| Nhóm API | Trạng thái | Bằng chứng |
|---|---|---|
| Identity/session/PIN/recovery | planned | Chưa có code/test |
| Customer/accounts/operator | planned | Chưa có code/test |
| Transfer/history | planned | Chưa có code/test |
| Audit/risk | planned | Chưa có code/test |

Chỉ chuyển verified khi có test với BE thật. Bootstrap sau reload và contract wording discrepancies theo roadmap phải được xử lý trước gate liên quan. Handover ghi giới hạn best-effort OTP/risk và mất intent memory qua reload.
