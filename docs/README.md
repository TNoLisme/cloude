# Digital Banking Simulator MVP

## Mục đích

Bộ tài liệu đặc tả MVP cho đề tài **Digital Banking Simulator** môn Cloud Application Development.

MVP tập trung một vertical slice hoàn chỉnh: khách hàng tự đăng ký bằng SĐT + OTP (hoặc Operator mở hộ tại quầy), đăng nhập bằng SĐT, thiết lập PIN, khôi phục mật khẩu qua OTP SMS/Email, cấp số dư demo, khách hàng chuyển tiền nội bộ, hệ thống bảo vệ consistency, chống request trùng, ghi audit và gắn cờ giao dịch đáng ngờ.

## Tài liệu

| Tài liệu | Nội dung |
|---|---|
| [MVP Requirements & Architecture](./mvp-requirements-and-architecture.md) | Business scope, actor, use case, domain, modular monolith, data ownership, flow và cấu trúc thư mục |
| [API & Team Contract](./api-and-team-contract.md) | OpenAPI contract, endpoint, request/response, error, auth, FE call sequences, Zustand rules và merge gates |
| [OpenAPI Contract](../contracts/openapi.yaml) | Machine-readable API paths, request/response schemas, headers, status codes và security scheme |
| [MVP Decision Record](./mvp-decision-record.md) | Các lựa chọn đã chốt cho demo, cấu hình baseline và checklist khóa contract |
| [Quality, Security & Cloud](./quality-security-and-cloud.md) | NFR, consistency, idempotency, concurrency, audit, risk flagging, test, CI/CD, observability và cloud |
| [Architecture Defense & FinOps](./architecture-defense-and-finops.md) | Bài bảo vệ kiến trúc, NFR định lượng, ước tính FinOps và roadmap microservices; roadmap không thuộc MVP implementation scope |

## Quyết định đã chốt

- Backend: Java 21 + Spring Boot.
- Kiến trúc backend MVP: modular monolith, một deployable và một PostgreSQL database.
- Frontend: React + TypeScript + Vite.
- Frontend state: Zustand cho client/UI state; server data vẫn đi qua API layer và query/cache strategy.
- FE và BE phát triển độc lập qua OpenAPI contract.
- Onboarding: Customer tự đăng ký (SĐT + email + OTP SMS) hoặc Operator tạo hộ tại quầy (OTP về SĐT khách); hệ thống mở ngay một tài khoản mặc định `ACTIVE`. Không còn bước duyệt hồ sơ.
- Định danh: `phone` (đăng nhập) và `email` đều bắt buộc và UNIQUE. Khôi phục mật khẩu qua OTP, chọn kênh SMS hoặc Email; thành công thì thu hồi mọi phiên đăng nhập.
- Xác thực giao dịch: PIN 6 số cho mọi transfer; thêm OTP SMS khi số tiền > 5,000,000 VNĐ. Trạng thái giao dịch: `AWAITING_OTP`, `COMPLETED`, `EXPIRED`, `FAILED`.
- Quản lý tài khoản: Operator tra cứu chính xác khách theo SĐT/email, khóa/mở tài khoản (`ACTIVE ⇄ BLOCKED`).
- Money scope: internal transfer và seed balance do Operator cấp. Không có external payment, cash deposit, cash withdrawal hay real banking integration.
- Cloud provider: chưa khóa. Thiết kế phải portable giữa managed PostgreSQL, container runtime, object/log/monitoring services của provider.
- Kafka, Outbox, Saga, read replica, broad operator account search và risk review workflow là post-MVP options. Không nằm trong MVP acceptance gate.

## Trạng thái

Đây là baseline đặc tả trước implementation. Mọi thay đổi API, scope hoặc ownership phải cập nhật contract và ghi rõ trong pull request.
