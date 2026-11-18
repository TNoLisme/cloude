# Báo cáo Tổng hợp Tuần 1 (`Week 1`)

> **Dự án:** Digital Banking MVP — Single-Bank, Core Simulated
> **Mã tài liệu:** `WORK-W1-SUMMARY`
> **Tuần:** 1 (Phân tích Yêu cầu, Mô hình Dữ liệu & Kiến trúc Nền tảng)
> **Phiên bản:** 1.0
> **Cập nhật:** 18/11/2026
> **Trạng thái:** Hoàn thành
> **Owner:** Toàn nhóm dự án (Phương, Đạt, Vũ, Thịnh, Thắng)

---

## 1. Mục tiêu Tuần 1

- Chốt phạm vi sản phẩm **Digital Banking MVP** (mô hình ngân hàng đơn lẻ, sổ cái nội bộ mô phỏng, đảm bảo tính đúng đắn tiền tệ, lũy đẳng, bảo mật và kiểm toán bất biến).
- Hoàn thành bộ tài liệu đặc tả yêu cầu (`01. Introduction`, `02. BRD`, `03. SRS`).
- Thiết kế mô hình cơ sở dữ liệu quan hệ trên PostgreSQL 16 (`04. Database design & analysis`), chốt ngăn xếp công nghệ (`05. Technical stack`) và kiến trúc Modular Monolith 11 module (`06. System architecture`).

## 2. Kết quả Bàn giao (`Deliverables`)

| Đầu việc | Phụ trách | Trạng thái | Tài liệu bàn giao |
|---|---|---|---|
| Xác định bối cảnh, mục tiêu, phạm vi và quy ước định danh | Phương & Toàn nhóm | **Done** | [[digitalbanking-docs/docs-common/01. Introduction.md]] |
| Phân tích yêu cầu nghiệp vụ (`BR-*`), quy trình và quy tắc (`BRL-*`) | Vũ, Đạt & Phương | **Done** | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Đặc tả yêu cầu chức năng (`FR-*`), phi chức năng (`NFR-*`), Use Cases và `AC-001..034` | Toàn nhóm | **Done** | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Thiết kế ERD, DDL 16 bảng trong schema `banking`, indexing và phân quyền DB | Phương & Đạt | **Done** | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Lựa chọn công nghệ baseline (Java 21, Spring Boot 3.3, PostgreSQL 16, React 18) | Phương & Thắng | **Done** | [[digitalbanking-docs/docs-common/05. Technical stack.md]] |
| Thiết kế kiến trúc hệ thống C4, phân ranh giới 11 module và luồng giao dịch `T0–T3` | Phương & Thịnh | **Done** | [[digitalbanking-docs/docs-common/06. System architecture.md]] |

## 3. Quyết định Trọng tâm Đã Chốt

1. **Kiến trúc Modular Monolith:** Phân tách thành 11 module độc lập (`common`, `identity`, `customer`, `account`, `transfer`, `ledger`, `limit`, `audit`, `fraud`, `notification`, `admin`) giao tiếp qua `<provider>.api`, kiểm soát bằng ArchUnit.
2. **Mô hình giao dịch 4 pha (`T0–T3`):** Tách biệt xác thực Step-Up (`T0`) trước khi tạo giao dịch (`T1`), thực thi hạch toán nguyên tử (`T2`), và gửi thông báo bất đồng bộ qua Outbox (`T3`).
3. **Bảo vệ bằng chứng:** Bảng `ledger_entry` và `audit_log` chỉ cho phép `SELECT, INSERT` đối với tài khoản runtime `banking_app`.

## 4. Liên kết

- Tiếp theo: [[digitalbanking-docs/docs-common/work/week2/README.md]]
- Chỉ mục công việc: [[digitalbanking-docs/docs-common/work/README.md]]
