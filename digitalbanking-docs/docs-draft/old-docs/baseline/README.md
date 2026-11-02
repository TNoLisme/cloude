# Digital Banking Simulator MVP

## Purpose

Tài liệu baseline cho Digital Banking Simulator môn Cloud Application Development. Các tài liệu này mô tả scope MVP, API contract, quyết định kiến trúc, bảo mật, kiểm thử và hướng dẫn bảo vệ.

## Documents

- [MVP Requirements & Architecture](./mvp-requirements-and-architecture.md): Business scope, actor, use case, domain, modular monolith, data ownership và flow.
- [API & Team Contract](./api-and-team-contract.md): API contract, endpoint, request/response, error, auth và FE integration rules.
- [OpenAPI Contract](../../contracts/openapi.yaml): Machine-readable API paths, request/response schemas, headers, status codes và security scheme.
- [MVP Decision Record](./mvp-decision-record.md): Các lựa chọn đã chốt cho demo, cấu hình baseline và checklist contract closure.
- [Quality, Security & Cloud](./quality-security-and-cloud.md): NFR, consistency, idempotency, concurrency, audit, risk, tests, CI/CD, observability và cloud.
- [Architecture Defense & FinOps](./architecture-defense-and-finops.md): Bài bảo vệ kiến trúc, NFR định lượng, ước tính FinOps và roadmap.

## Approved baseline

- Backend: Java 21 + Spring Boot, modular monolith, một deployable và một PostgreSQL database.
- Frontend: React + TypeScript + Vite; Zustand cho client/UI state.
- FE/BE phát triển độc lập qua OpenAPI contract.
- Onboarding: Customer tự đăng ký hoặc Operator tạo hộ; OTP xác minh số điện thoại; tài khoản mặc định `ACTIVE` ngay.
- Phone và email đều bắt buộc, duy nhất; đăng nhập bằng phone; khôi phục mật khẩu qua SMS hoặc Email OTP.
- Mọi transfer cần PIN 6 số; OTP SMS bổ sung khi amount > 5,000,000 VNĐ.
- Operator exact lookup, seed balance, block/unblock account.
- Simulated VND, scale 0; internal transfer và Operator seed balance.
- Same-origin proxy là mặc định; exact-origin CORS chỉ dùng khi khác origin.
- Kafka, Outbox, Saga, read replica, microservices và risk review workflow là post-MVP options.

## Authority and updates

`../../contracts/openapi.yaml` là nguồn sự thật cho HTTP API shape. Tài liệu baseline ghi quyết định MVP đã duyệt. Mỗi lần update tạo spec riêng trong [`../projects/`](../projects/) theo [Backend Feature Delivery Workflow](../backend-development-workflow.md). Không sửa baseline hoặc API contract ngầm; ghi rõ và xin duyệt thay đổi có ảnh hưởng trước khi code.
