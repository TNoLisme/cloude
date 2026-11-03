# Work Documentation

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Kế hoạch, kết quả và bằng chứng công việc theo tuần
> **Trạng thái thư mục:** Đang viết
> **Cập nhật:** 03/11/2026

## 1. Mục đích

`work/` dùng để theo dõi **cách dự án được thực thi**: mục tiêu từng tuần, đầu việc, người phụ trách, kết quả bàn giao và bằng chứng kiểm chứng.

Thư mục này **không** thay thế các tài liệu đặc tả:

- Yêu cầu và hành vi hệ thống: `docs-common/01–03`.
- Thiết kế kỹ thuật: `docs-common/design/`.
- Hợp đồng API: `docs-common/07` và `openapi/`.

## 2. Cấu trúc

```text
work/
├── README.md          # Hướng dẫn này
├── week1/
│   └── README.md      # Tổng hợp tuần 1
├── week2/
│   └── README.md      # Tổng hợp tuần 2
└── weekN/
    └── README.md      # Bổ sung khi có tuần mới
```

Mỗi thư mục tuần có thể chứa:

- `README.md` — mục tiêu, kế hoạch, kết quả, vấn đề, bàn giao.
- `plan.md` — kế hoạch chi tiết (nếu cần).
- `review.md` — biên bản review/retrospective (nếu cần).
- `evidence/` — log, ảnh chụp, báo cáo test, liên kết bằng chứng.

## 3. Trạng thái

| Tuần | Nội dung | Trạng thái |
|---|---|---|
| [[digitalbanking-docs/docs-common/work/week1/README.md]] | Kế hoạch và kết quả tuần 1 | Đang viết |
| [[digitalbanking-docs/docs-common/work/week2/README.md]] | Kế hoạch và kết quả tuần 2 | Đang viết |

> Các tuần tiếp theo sẽ được thêm khi có kế hoạch thực thi. Tuần chưa có nội dung phải giữ trạng thái **Đang viết**.

## 4. Mẫu README cho một tuần

```markdown
# Week N

> **Dự án:** Digital Banking MVP
> **Tuần:** N
> **Thời gian:** dd/mm/yyyy – dd/mm/yyyy
> **Owner:** ...
> **Trạng thái:** Đang viết | Hoàn thành

## 1. Mục tiêu tuần

- ...

## 2. Phạm vi

- Trong phạm vi: ...
- Ngoài phạm vi: ...

## 3. Kế hoạch và kết quả

| Đầu việc | Owner | Trạng thái | Deliverable | Bằng chứng |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

## 4. Vấn đề và quyết định

| Vấn đề | Ảnh hưởng | Quyết định | Owner |
|---|---|---|---|
| ... | ... | ... | ... |

## 5. Bàn giao

- Tài liệu đã cập nhật: ...
- Thay đổi API/DB: ...
- Việc cần theo dõi ở tuần sau: ...

## 6. Liên kết

- Yêu cầu: ...
- Thiết kế: ...
- Bằng chứng: ...
```

## 5. Quy tắc cập nhật

1. **Cập nhật theo tuần:** mỗi tuần có một thư mục riêng; không ghi lẫn kế hoạch tuần này vào tuần khác.
2. **Liên kết thay vì sao chép:** trỏ tới `BR/FR/NFR/AC`, thiết kế và tài liệu kỹ thuật thay vì chép lại nội dung.
3. **Trạng thái rõ ràng:** dùng `Đang viết`, `Hoàn thành`, hoặc trạng thái đầu việc (`Todo`, `Doing`, `Done`, `Blocked`).
4. **Bằng chứng cụ thể:** mỗi đầu việc hoàn thành phải có bằng chứng (test report, log, ảnh, commit/PR, biên bản review).
5. **Không dùng làm đặc tả:** nếu phát sinh nội dung kỹ thuật mới, cập nhật tài liệu chính thức hoặc `design/` rồi liên kết lại.
6. **Cập nhật README:** khi thêm tuần mới hoặc đổi trạng thái, cập nhật bảng trong README này và README gốc.

## 6. Quan hệ với tài liệu khác

| Tài liệu | Vai trò |
|---|---|
| [[digitalbanking-docs/docs-common/01. Introduction.md]] | Mục tiêu, phạm vi và stakeholder |
| [[digitalbanking-docs/docs-common/02. BRD.md]] | Yêu cầu nghiệp vụ và tiêu chí thành công |
| [[digitalbanking-docs/docs-common/03. SRS.md]] | Hành vi hệ thống và acceptance criteria |
| [[digitalbanking-docs/docs-common/design/README.md]] | Thiết kế kỹ thuật chuyên sâu |
| [[digitalbanking-docs/docs-common/09. Test plan.md]] | Chiến lược và bằng chứng kiểm thử (đang viết) |
| [[digitalbanking-docs/docs-common/10. Master timelines.md]] | Kế hoạch tổng thể và milestone (đang viết) |

## 7. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Tuần 1 | [[digitalbanking-docs/docs-common/work/week1/README.md]] |
| Tuần 2 | [[digitalbanking-docs/docs-common/work/week2/README.md]] |
| README docs-common | [[digitalbanking-docs/docs-common/README.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |
