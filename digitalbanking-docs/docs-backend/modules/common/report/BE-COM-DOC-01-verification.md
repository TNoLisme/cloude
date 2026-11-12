# BE-COM-DOC-01 — Verification report

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-REPORT-COMMON-01`
> **Phiên bản:** v1.0
> **Ngày:** 12/11/2026
> **Trạng thái:** Hoàn thành — verification tài liệu; chưa xác nhận runtime/sign-off
> **Owner:** Phương
> **Reviewer:** Backend Lead, QA, Security (chờ review)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-backend/modules/common/tasks/BE-COM-DOC-01.md]], [[digitalbanking-docs/docs-backend/modules/common/README.md]].

## 1. Phạm vi verification

Report này ghi kết quả Task 1 tại README v1.0. Quyết định owner và verification bổ sung cho v1.1 nằm tại [[digitalbanking-docs/docs-backend/modules/common/report/BE-COM-DOC-02-verification.md]]; các con số link/trạng thái 8 issue bên dưới là snapshot lịch sử, không phải trạng thái mới nhất.

Kiểm tra đầu ra tài liệu của Task 1, không nghiệm thu implementation. Thiết kế mới ở README là đề xuất cho owner review; các test runtime ở trạng thái Planned. Không chạy Gradle, PostgreSQL, test mật mã, security scan hoặc benchmark trong task tài liệu này.

`git status` trước khi làm đã có thay đổi tại README gốc, docs-common/README.md, docs-common/design/README.md và docs-common/work/README.md. Task này không sửa các file docs-common; chỉ bổ sung một dòng chỉ mục common vào README gốc, giữ các thay đổi có sẵn.

## 2. Kết quả kiểm tra

Baseline Git HEAD khi kiểm tra: `16f3950e437abcc7e671899ea1833bc49ae0ac0f`; nội dung kiểm tra gồm working tree của Task 1, chưa commit. Dùng Python 3 standard library để parse Markdown/JSON, so catalog với nguồn trên đĩa, kiểm tra path bằng Path.is_file; không tải dependency.

| Kiểm tra | Cách kiểm | Kết quả | Trạng thái |
|---|---|---|---|
| Khung chuẩn | Đọc tiêu đề `## 1.` đến `## 7.` và Change Log | Đủ 7 phần, đúng thứ tự | Verified — tài liệu |
| Traceability | Parse riêng bảng README §1.3, đối chiếu SRS §3.1/§10 | Đủ 9 NFR bắt buộc: 013..020 và 025; có BR/FR/AC và phân chia owner | Verified — tài liệu |
| Class/method contracts | Parse public class/record/enum ở code block §2, review signatures | 15 class thuộc 4 gói, có input/output/error/lifecycle; config hiện có tách khỏi đề xuất | Verified — tài liệu |
| Catalog lỗi | Parse 46 dòng README §4.3, so code/HTTP/retryable với `07` §4.3.1–§4.3.6 và tập ErrorCode YAML | 46/46 khớp, không thiếu/thêm code public; điều kiện retry giữ riêng | Verified — tài liệu |
| SQLSTATE | Kiểm bảng §4.4 và review design/02 §7.10/Appendix C | Đủ 55P03, 40P01, 40001, 57014, 23505, 23514; có phân loại context/audit/unique/invariant | Verified — tài liệu |
| Liên kết | Resolve wikilink và Markdown relative link trong 3 tài liệu common; resolve link common mới ở chỉ mục | 33 liên kết trong 3 tài liệu có đích tồn tại; chỉ mục mới trỏ đúng | Verified — đường dẫn |
| Metadata/Markdown/JSON | Kiểm 8 trường metadata, fence cân bằng, JSON parse, Problem required fields, trailing whitespace | Cả 3 tài liệu hợp lệ theo kiểm tra cấu trúc | Verified — cấu trúc |
| Danh mục test/issue | Parse ID ở bảng §7.2/§1.5, kiểm trùng, review oracle/trace | 27 ca COM-* không trùng, 8 OI-COM-* có owner/mốc; runtime tests đều Planned | Verified — danh mục |
| Diff | `git diff --check` và review file thay đổi | Không lỗi whitespace; không sửa Java/runtime/OpenAPI/docs-common | Verified — phạm vi Task 1 |

Lượt kiểm link đầu phát hiện 3 relative link tới Java thừa một cấp `../`; đã sửa và kiểm lại. Không coi liên kết tồn tại là bằng chứng nội dung nguồn đã đúng hoặc behavior đã chạy.

Kết quả thực chạy của kiểm tra tài liệu sau khi sửa link (exit code 0):

```text
PASS: 7 sections; 33 links; 46 public errors
PASS: 9 NFRs; 15 classes; 6 SQLSTATEs; 27 unique test cases; 8 open issues; metadata/fences/JSON/whitespace; 3 indexes
```

`git diff --check` không có output lỗi, exit code 0. Đây là kiểm tra cấu trúc/nội dung và review tĩnh bởi Codex, chưa phải sign-off của Reviewer.

Có thể kiểm lại khung, catalog và link từ workspace root bằng lệnh dưới đây. Đây là kiểm tra tài liệu, không phải test triển khai module:

```bash
python3 - <<'PY'
from pathlib import Path
import re
r = Path.cwd()
b = r / 'digitalbanking-docs/docs-backend/modules/common'
paths = [b/'README.md', b/'tasks/BE-COM-DOC-01.md',
         b/'report/BE-COM-DOC-01-verification.md']
s = paths[0].read_text()
assert re.findall(r'^## (\d+)\.', s, re.M) == list('1234567')
links = 0
for p in paths:
    t = re.sub(r'```.*?```', '', p.read_text(), flags=re.S)
    for dest in re.findall(r'\[\[([^\]|]+)(?:\|[^\]]+)?\]\]', t):
        assert (r/dest.split('#')[0]).is_file(), dest
        links += 1
    for dest in re.findall(r'\[[^\]]+\]\(([^)]+)\)', t):
        if '://' not in dest:
            assert (p.parent/dest.split('#')[0]).is_file(), dest
            links += 1
api = (r/'digitalbanking-docs/docs-common/07. API contracts.md').read_text()
src = api.split('### 4.3. Error code catalog')[1].split('#### 4.3.7.')[0]
src_rows = re.findall(r'^\| `([A-Z_]+)` \| (\d{3}) \| ([^|]+)\|', src, re.M)
expected = {c:(h, 'false' if v.strip().startswith('Không') else 'true')
            for c,h,v in src_rows}
ours = s.split('### 4.3. Catalog')[1].split('### 4.4.')[0]
actual = {c:(h,v) for c,h,v in re.findall(
    r'^\| `([A-Z_]+)` \| (\d{3}) \| (true|false) \|', ours, re.M)}
assert actual == expected
y = (r/'digitalbanking-docs/openapi/digital-banking-v1.yaml').read_text()
enum = y.split('    ErrorCode:\n')[1].split('    Problem:\n')[0]
assert set(actual) == set(re.findall(r'^        - ([A-Z_]+)$', enum, re.M))
print(f'PASS: 7 sections; {links} links; {len(actual)} public errors')
PY
git diff --check
```

## 3. Review nội dung và giới hạn

- Docs-common là nguồn có thẩm quyền cao nhất; `07` quyết định API semantics, YAML kiểm tra máy đọc. Không tự thêm data/meta hoặc mã lỗi ngoài catalog.
- Common một chiều, không sở hữu bảng/transaction nghiệp vụ; identity/transfer/audit giữ trách nhiệm domain. Handler không import module nghiệp vụ để ghi audit hoặc query/retry DB.
- Có lifecycle filter trước security, invalid-header path, server-generated requestId, cleanup finally, propagation async/job và error-dispatch test.
- Có AES-GCM/IV/tag, HMAC/SHA, JCS UTF-16/ECMAScript, audit hash delimiter/hash_version, masking/log redaction và audit allowlist.
- Có phân biệt SQLSTATE theo context, audit failure trước generic mapping, retry whole transaction tại owner, invariant failure không bị biến thành 409 client.
- README §1.5 ghi 8 issue nguồn/đề xuất cùng owner/mốc chốt: envelope, Problem fields, mã lỗi, masking, rotation, policy thresholds, JCS/error aliases, cookie/config. Issue chưa đóng tại nguồn.
- Mã T-AUD-01/02 có nghĩa khác giữa design/01 và design/02; README §7.1 ghi rõ, bảng test dùng nguồn + ID để tránh nhầm.

## 4. Đầu ra

- [[digitalbanking-docs/docs-backend/modules/common/README.md]] — thiết kế 7 phần.
- [[digitalbanking-docs/docs-backend/modules/common/tasks/BE-COM-DOC-01.md]] — mô tả/checklist 8 đầu việc.
- Report này — bằng chứng kiểm tra tài liệu, không tuyên bố module đã triển khai.
- Liên kết chỉ mục tại [[digitalbanking-docs/docs-backend/README.md]], [[digitalbanking-docs/docs-backend/modules/_overview/README.md]], [[digitalbanking-docs/README.md]].

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 12/11/2026 | Kiểm tra tài liệu Task 1, ghi phạm vi evidence và issue cần owner review |
