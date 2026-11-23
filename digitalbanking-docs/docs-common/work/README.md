# Work Documentation (`docs-common/work/`)

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `DOC-WORK-INDEX`
> **Phiên bản:** 1.0
> **Phạm vi:** Kế hoạch, kết quả và bàn giao công việc giai đoạn phân tích & thiết kế chung (Tuần 1 – Tuần 2)
> **Cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành

## 1. Mục đích

Thư mục `work/` lưu trữ nhật ký thực thi của giai đoạn xây dựng nền tảng yêu cầu và thiết kế hệ thống chung (Tuần 1 và Tuần 2 theo `10 Master timelines.md`). Các kết quả đặc tả chính thức đã được xuất bản tại:
- Yêu cầu và hành vi hệ thống: `docs-common/01–03`.
- Cơ sở dữ liệu, công nghệ, kiến trúc, API, hạ tầng, kiểm thử: `docs-common/04–11` và `openapi/`.
- Thiết kế kỹ thuật chuyên sâu và hợp đồng giao tiếp 11 module: `docs-common/design/01–05`.
- Tài liệu hiện thực từng module Backend: `docs-backend/modules/`.

## 2. Cấu trúc thư mục

```text
work/
├── README.md          # Chỉ mục và hướng dẫn theo dõi công việc
├── week1/
│   └── README.md      # Tổng hợp kế hoạch và kết quả bàn giao Tuần 1
└── week2/
    └── README.md      # Tổng hợp kế hoạch và kết quả bàn giao Tuần 2
```

## 3. Trạng thái tổng hợp

| Tuần | Phạm vi trọng tâm | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/docs-common/work/week1/README.md]] | Phân tích yêu cầu (`01–03`), thiết kế CSDL (`04`), ngăn xếp công nghệ (`05`) và kiến trúc hệ thống (`06`) | Hoàn thành — v1.0 |
| [[digitalbanking-docs/docs-common/work/week2/README.md]] | Hợp đồng API (`07`, `OpenAPI`), thiết kế chuyên sâu (`design/01–05`), hạ tầng (`08`), kế hoạch kiểm thử (`09`) và khởi tạo khung dự án | Hoàn thành — v1.0 |

## 4. Liên kết tham chiếu

| Tài liệu | Vai trò |
|---|---|
| [[digitalbanking-docs/docs-common/01. Introduction.md]] | Tổng quan, mục tiêu, phạm vi và quy ước định danh |
| [[digitalbanking-docs/docs-common/02. BRD.md]] | Yêu cầu nghiệp vụ và quy tắc kinh doanh |
| [[digitalbanking-docs/docs-common/03. SRS.md]] | Đặc tả yêu cầu hệ thống và tiêu chí nghiệm thu (`AC`) |
| [[digitalbanking-docs/docs-common/design/README.md]] | Thiết kế kỹ thuật chuyên sâu (`01–05`) |
| [[digitalbanking-docs/docs-common/09. Test plan.md]] | Kế hoạch kiểm thử và nghiệm thu `D1–D5` |
| [[digitalbanking-docs/docs-common/10 Master timelines.md]] | Lộ trình tổng thể 8 tuần và phân công WBS |
