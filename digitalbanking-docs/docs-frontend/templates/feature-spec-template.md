# Template đặc tả tính năng FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-TPL-FEATURE |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, QA |

Sao chép nội dung dưới cho feature mới, thay các placeholder và ghi FR/UC/AC thực tế. Mọi quy tắc shared được dẫn tới heading standards, trạng thái triển khai tại tracker.

## 1. Mục tiêu và phạm vi

[Người dùng, mục tiêu, đầu ra FE và ranh giới phối hợp BE.]

## 2. Nguồn và phụ thuộc

[FR/NFR/UC/AC với link SRS, API semantics/OpenAPI, BE module và heading quy tắc dùng chung.]

## 3. Màn hình và điều hướng

[Screen ID theo FE-ARCH-03, route, menu/direct URL, permission/scope và liên kết file design/screens tương ứng. File design mới theo [[digitalbanking-docs/docs-frontend/templates/screen-design-template.md]].]

## 4. Luồng người dùng

[Điều kiện đầu vào, các bước tương tác, phản hồi server và kết quả kết thúc.]

## 5. Trạng thái giao diện

[Loading, refetch, empty, error, forbidden, session expired và trạng thái riêng.]

## 6. Thành phần và dữ liệu

[Component, DTO, state owner, query key, invalidation và dữ liệu nhạy cảm.]

## 7. Tương tác API

[operationId, method/path có thật trong YAML, thời điểm gọi, status code và DTO.]

## 8. Validation và thông báo

[Field/rule nguồn, error code mapping và phần copy riêng cần review.]

## 9. Trường hợp đặc biệt

[Chọn trường hợp áp dụng như double click, back/reload, timeout, concurrent update và multi-tab.]

## 10. Tiêu chí nghiệm thu

[Hành vi Given/When/Then hoặc checklist kiểm chứng được, AC và FET ID liên quan.]

## 11. Liên kết triển khai

[Task ID tại tracker, source/PR permalink và evidence theo commit.]

## 12. Vấn đề cần chốt

[GAP ID trong tracker, ảnh hưởng và nguồn cần quyết định. Owner/trạng thái giữ tại tracker.]
