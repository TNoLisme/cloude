# Quy ước bằng chứng frontend

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-EVIDENCE |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / QA, Security |

## 1. Tổ chức

Tạo thư mục theo task khi có kết quả thật. Bằng chứng nhiều lần chạy tách theo ngày và commit để giữ được lịch sử.

~~~text
evidence/
  FE-03-02/
    2026-11-08-<short-fe-sha>/
      README.md
      screenshots/
      test-report/
~~~

Sơ đồ là quy ước đặt tên. Tên file timestamp không được dùng thay commit của code. Artifact lớn có thể giữ ở CI storage và ghi URL/retention/CI run trong manifest.

## 2. Manifest bắt buộc

| Trường | Nội dung cần ghi |
|---|---|
| Task / test / acceptance | FE-xx-yy, FET-xx, FR/AC liên quan |
| Phiên bản | FE SHA, BE SHA, OpenAPI info.version và commit |
| Môi trường | OS/runtime, browser/version, viewport, API environment an toàn |
| Dữ liệu | Synthetic fixture ID, seed version, role và scope đã mask |
| Cách chạy | Command/script/CI run, tiền điều kiện và bước tái lập |
| Expected / actual | Kết quả cụ thể, Pass/Fail/Blocked và lý do |
| Artifact | Link ảnh/video/report/BE invariant và timestamp |
| Review | Người kiểm tra, người review và ngày |
| Giới hạn | Phần chưa kiểm chứng, defect và GAP/task tiếp theo |

## 3. Quy tắc

- Mỗi screenshot/test report gắn case và commit.
- Secret/password/OTP/token/cookie/full PII phải được loại khỏi ảnh/log/video trước khi lưu.
- Mutation smoke chỉ dùng môi trường QA phù hợp theo FE-TEST-04.
- Evidence mocked có nhãn Mock, API thật có nhãn API, browser có nhãn Browser.
- Source review ghi đúng mức đọc source.
- Artifact fail/blocked giữ lý do để tái lập.
- Kết quả đã có được gắn trực tiếp vào task tương ứng trong tracker, kèm permalink của commit được kiểm chứng.

## 4. Liên kết

[[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]], [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].
