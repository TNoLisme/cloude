# BE-COM-DOC-02 — Verification quyết định trước implement

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-REPORT-COMMON-02`
> **Phiên bản:** v1.0
> **Ngày:** 12/11/2026
> **Trạng thái:** Hoàn thành — verification tài liệu, chưa implement/runtime sign-off
> **Owner:** Phương
> **Reviewer:** Backend Lead, QA, Security (review triển khai tiếp theo)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-backend/modules/common/tasks/BE-COM-DOC-02.md]], [[digitalbanking-docs/docs-backend/modules/common/README.md]] §1.5.

## 1. Phạm vi

Xác minh chốt nguồn theo chỉ đạo owner, không nghiệm thu runtime. README common v1.1 và các đoạn backend liên quan 8 nhóm dùng `docs-common/01–11` trước design/backend. Không sửa nguồn `01–11` hoặc `design/` để tự đóng issue. Các thay đổi tồn tại sẵn ở docs-common README được giữ nguyên.

Giữ nguyên report Task 1 như snapshot v1.0, chỉ thêm dẫn tới report này. Chữ ký implementation mới vẫn là đề xuất; lựa chọn nguồn cho 7 nhóm đã được owner phân xử, không còn chờ chọn lại nguồn.

## 2. Kết quả kiểm tra

Baseline HEAD: `16f3950e437abcc7e671899ea1833bc49ae0ac0f`. Kiểm working tree, dùng Python 3 standard library; không tải dependency. Snapshot SHA-256 trước lần cập nhật này gồm 107 file docs-common/backend runtime/OpenAPI (bỏ .git/build/.gradle), so lại sau sửa tài liệu: không file nào đổi. Snapshot phục vụ kiểm phạm vi trong phiên làm việc, không phải runtime evidence.

| Hạng mục | Kết quả | Trạng thái |
|---|---|---|
| Quy tắc nguồn | Root README, convention/backend config và common thống nhất `01–11` trước design/backend; OpenAPI theo `07` | Verified — nội dung |
| Bảng quyết định | 8 ID giữ nguyên, 7 nhóm đã phân xử, chỉ OI-COM-05 còn mở vì thiếu thiết kế | Verified — nội dung/cấu trúc |
| Baseline cấu hình | Mẫu tài liệu 10 phút, 30/customer/phút; cookie DB_SESSION/Path=/; không trần session 3/1; giữ Đề xuất | Verified — tài liệu |
| Lỗi/envelope/JCS/masking | Summary/convention dùng Problem của `07`, không mã generic, không auto wrap; JCS UTF-16 và account `**** ****` | Verified — nội dung |
| Khung/traceability/class | Đủ 7 phần, 9 NFR yêu cầu, 15 chữ ký class (ApiResponse chỉ là dấu vết, chưa triển khai) | Verified — cấu trúc |
| Error catalog | 46/46 code/HTTP/retryable khớp `07`; tập code khớp OpenAPI | Verified — cấu trúc |
| Liên kết | 44 liên kết có đích tồn tại trong 5 tài liệu common | Verified — đường dẫn |
| Test design | 28 ID COM-* không trùng, tất cả Planned; thêm COM-CFG-03 kiểm scope customer/baseline/session | Verified — danh mục |
| Phạm vi | 107 file snapshot nguồn/runtime/OpenAPI không đổi; diff không lỗi whitespace; chỉ sửa docs backend và quy tắc/chỉ mục README gốc | Verified — SHA-256/diff |

Kiểm tra link không thay kiểm chứng ngữ nghĩa nguồn. Có thể chạy lại kiểm tra quyết định/link/catalog từ workspace root bằng lệnh sau (không chạy implementation):

Kết quả thực chạy, exit code 0:

```text
PASS: 7 resolved groups; 1 rotation gap; 28 Planned tests; 44 links; 46 public errors
PASS: 9 NFRs; 15 classes; metadata/fences/JSON; baseline 10 min + 30/customer/min; 107 source/runtime/OpenAPI files unchanged
```

Lệnh kiểm tra lại:

```bash
python3 - <<'PY'
from pathlib import Path
import re
r = Path.cwd()
b = r/'digitalbanking-docs/docs-backend/modules/common'
s = (b/'README.md').read_text()
assert re.findall(r'^## (\d+)\.', s, re.M) == list('1234567')
rows = re.findall(r'^\| `OI-COM-\d{2}` \|.*$', s, re.M)
assert len(rows) == 8
assert sum('Đã phân xử' in row for row in rows) == 7
opened = [row for row in rows if 'Còn mở — thiếu thiết kế' in row]
assert len(opened) == 1 and 'OI-COM-05' in opened[0]
tests = re.findall(r'^\| (COM-[A-Z]+-\d{2}) \|', s, re.M)
assert len(tests) == len(set(tests)) == 28
paths = sorted(b.rglob('*.md')); links = 0
for p in paths:
    text = re.sub(r'```.*?```', '', p.read_text(), flags=re.S)
    for dest in re.findall(r'\[\[([^\]|]+)(?:\|[^\]]+)?\]\]', text):
        assert (r/dest.split('#')[0]).is_file(), dest
        links += 1
    for dest in re.findall(r'\[[^\]]+\]\(([^)]+)\)', text):
        if '://' not in dest:
            assert (p.parent/dest.split('#')[0]).is_file(), dest
            links += 1
api = (r/'digitalbanking-docs/docs-common/07. API contracts.md').read_text()
src = api.split('### 4.3. Error code catalog')[1].split('#### 4.3.7.')[0]
expected = {c:(h, 'false' if v.strip().startswith('Không') else 'true')
    for c,h,v in re.findall(r'^\| `([A-Z_]+)` \| (\d{3}) \| ([^|]+)\|', src, re.M)}
ours = s.split('### 4.3. Catalog')[1].split('### 4.4.')[0]
actual = {c:(h,v) for c,h,v in re.findall(
    r'^\| `([A-Z_]+)` \| (\d{3}) \| (true|false) \|', ours, re.M)}
assert actual == expected
y = (r/'digitalbanking-docs/openapi/digital-banking-v1.yaml').read_text()
enum = y.split('    ErrorCode:\n')[1].split('    Problem:\n')[0]
assert set(actual) == set(re.findall(r'^        - ([A-Z_]+)$', enum, re.M))
print(f'PASS: 7 resolved groups; 1 rotation gap; {len(tests)} Planned tests; '
      f'{links} links; {len(actual)} public errors')
PY
git diff --check
```

## 3. Kết luận thiết kế

- OI-COM-01/02/03/04/06/07/08: đã phân xử theo `01–11` trong README §1.5.
- OI-COM-05: còn mở vì thiếu thiết kế nhận diện khóa cũ/rotation; `04` §11.2 OQ-15 vẫn Open. Không tự thêm key ID/blob/schema hoặc nhận là rotation đã xong.
- Baseline chọn nguồn: lockout 10 phút, transfer 30 request/phút/**customer**, cookie DB_SESSION/Path=/, không áp trần session 3/1 từ design/04. Ngưỡng vẫn mang nhãn Đề xuất theo `03`/`07`, cần đóng OI nghiệp vụ trước policy release có hiệu lực.
- Runtime chưa đổi: AppProperties/application.yml còn 15 phút/20 mỗi user/keys trần 3/1. Không dùng chúng làm bằng chứng lựa chọn nguồn mới đã được triển khai.
- Bổ sung test Planned COM-CFG-03 để kiểm baseline và customer bucket scope; không chạy Gradle/PostgreSQL/security/crypto tests trong công việc tài liệu.
- Không sửa toàn bộ thiết kế chuyên sâu hoặc audit các khác biệt ngoài 8 nhóm; quy tắc ưu tiên đã ghi rõ để tra cứu tiếp.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 12/11/2026 | Verification quyết định ưu tiên docs-common/01–11 và đồng bộ tài liệu backend liên quan common trước implement |
