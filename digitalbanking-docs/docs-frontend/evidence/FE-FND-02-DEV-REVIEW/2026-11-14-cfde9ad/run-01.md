# Phiếu chạy task FE-FND-02-DEV-REVIEW / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 14/11/2026, Asia/Bangkok / Codex theo yêu cầu người phụ trách FE |
| Task cha / con | FE-FND-02 / FE-FND-02-DEV-REVIEW — đối chiếu readiness dev, không triển khai tích hợp |
| FE SHA / working tree | frontend@cfde9ad205f4bc341d979f58b28bc2a065438fe2; dirty từ trước, gồm tooling/Money/ID/v2 contract/types/mock và tài liệu chưa commit. Không gán các thay đổi này cho HEAD |
| BE SHA / môi trường | origin/dev@24606b529197d6dd3e6908a7cd271b69b20dd4d2, merge PR #10; đọc Git objects, không checkout/start backend |
| Contract | V1 1.0.0, SHA256 chuẩn hóa LF 12338cee5a7b0ade0235ba204e8593ae7cbeaa2879cb3ba9681f7774371684e9; v2 2.0.0 Draft FE/mock chỉ có trong working tree FE |
| Mức chứng minh | Đọc source/contract/report; không API thật, không browser, không chạy lại Gradle |
| Phạm vi được phép | Chat 14/11/2026: “đọc nhánh dev để xem có gì thay đổi và chốt được với fe không”. Đọc/đối chiếu và ghi báo cáo; không merge/push hoặc đổi quyết định version đã chốt |

Nguồn FE đã đọc: [[digitalbanking-docs/docs-frontend/README.md]], [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]], [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]], [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]], [[digitalbanking-docs/docs-frontend/templates/task-run-template.md]], [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]], [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]], [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md]], [[digitalbanking-docs/docs-frontend/design/screens/AUTH-LOGIN.md]], [[digitalbanking-docs/docs-common/07a. API contracts v2.md]]. Nguồn gốc đối chiếu: 07/API v1, Security và quy tắc nguồn trên dev; không dùng legacy làm nghiệp vụ đích.

Nguồn dev được ghim theo commit để tránh nhầm với file trên frontend:

- [Tổng hợp trạng thái common](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md).
- [Quy tắc ưu tiên nguồn](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/README.md) và [verification DOC-02](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/docs-backend/modules/common/report/BE-COM-DOC-02-verification.md).
- [API v1](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/openapi/digital-banking-v1.yaml), [ngữ nghĩa 07](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/docs-common/07.%20API%20contracts.md), [Security](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-docs/docs-common/design/04.%20security.md).
- [SecurityFilterChainConfig](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/SecurityFilterChainConfig.java), [SessionAuthenticationFilter](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/SessionAuthenticationFilter.java), [SessionAuthenticator](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/config/SessionAuthenticator.java).
- [ProblemResponse](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/error/ProblemResponse.java), [ErrorCode](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/error/ErrorCode.java), [correlation filter](https://github.com/TNoLisme/cloude/blob/24606b529197d6dd3e6908a7cd271b69b20dd4d2/digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/context/CorrelationAndRequestIdFilter.java).

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| Quy tắc nguồn mới trên dev | README dev ghi owner chốt 12/11: docs-common/01–11 → design → BE/FE, YAML theo 07. Common DOC-02 chọn DB_SESSION/Path=/ theo 07, không chọn Security cấp dưới | Quyết định FE trong chat 14/11 vẫn là giữ v1 và tạo v2 riêng theo Security. Chưa có xác nhận BE/Security cho v2 | Được ghi nhận khác biệt; không tự hủy ADR-002 hoặc đổi runtime v1 |
| GAP-05 / V2-01..05 | Dev chỉ có v1, không có route/schema v2; DB_SESSION được hardcode trong auth filter, Problem challenge chấp nhận LOGIN_NEW_DEVICE; identity chưa có adapter/controller | Chat 14/11: “Theo Security: SESSION_TOKEN, Path=/api, LOGIN_MFA; cần đồng bộ lại API/YAML trước phần phụ thuộc”; sau đó “Giữ nguyên /api/v1 hiện tại; tạo contract /api/v2 theo đúng quy tắc versioning cho baseline Security” | FE/mock v2 theo quyết định đã có được tiếp tục; tích hợp v2 còn chặn |
| GAP-01/02/03/04/08/09/10/11/12 | API v1 không đổi; không có business implementation bổ sung. Common filter/crypto/error không cung cấp endpoint hoặc quyết định nghiệp vụ còn thiếu | Giữ các quyết định phạm vi trước đó; không có quyết định mới từ yêu cầu đọc dev | Không đóng GAP; phần độc lập đã rõ vẫn được thực hiện |
| GAP-07 | Có permission gates trong common, nhưng chưa có identity DTO/role seed adapter hoặc ownership/maker-checker business integration để chứng minh một matrix thống nhất | Chưa có phê duyệt matrix mới của BA/BE/Security | Đối chiếu làm đầu vào; chưa chốt menu/action mapping |
| Readiness thực | Báo cáo common ghi 203 tests đạt, 3 task Blocked, chưa review/sign-off và NO-GO release/cloud | Không có phê duyệt nghiệm thu/merge từ yêu cầu này | Chỉ chứng minh nền tảng common theo phạm vi báo cáo |

## 3. Thay đổi và kịch bản

Chỉ bổ sung phiếu này và nhật ký tracker. Không sửa source FE/BE, handler mock, contract, ADR, design hoặc cấu hình kết nối. Không checkout dev; toàn bộ source dev đọc bằng git show/git ls-tree/git grep. Giữ các thay đổi có sẵn của frontend.

### 3.1. Dev đã thay đổi gì

- So với merge-base của HEAD và dev: 339 files changed, 20,482 insertions, 3,897 deletions. Phần lớn là nền tảng backend refactor và tái cấu trúc tài liệu backend theo module.
- Runtime refactor có 28 Java files common (config 14/context 4/crypto 4/error 6), thêm cấu hình, RFC9457 error layer, correlation/context, crypto/JCS/masking, HTTP security và observability. Có BackendApplication riêng ngoài 28 file này.
- Trong src/main của backend refactor, controller nghiệp vụ chưa có; controller operational có GET /api/v1/health. SessionAuthenticator mới là SPI interface, chưa có production implementation. Migration hiện chỉ CREATE SCHEMA IF NOT EXISTS banking.
- Tài liệu common ghi 5/8 implementation task Done trong phạm vi local, 3 Blocked (05/07/08); 203 tests pass/23 suites là kết quả report của BE, không phải lần chạy FE này.
- API v1 vẫn 30 operations/46 error codes, không đổi so với working tree FE sau chuẩn hóa LF. Không có thay đổi source digitalbanking-frontend trên dev trong diff từ merge-base. Tám feature FE chỉ đổi liên kết tới tài liệu backend đã di chuyển; architecture đổi sơ đồ và format, không thêm delta nghiệp vụ FE được phát hiện trong diff này. Có tài liệu staffing mới và đổi tên file timeline; không tự lấy đó làm nghiệm thu runtime.
- Không lấy PROGRESS.md của cây legacy be làm readiness cho digitalbanking-backend refactor.

### 3.2. Có thể thống nhất ở mức thiết kế FE/shared client

| Nội dung | Căn cứ dev / tác động FE | Giới hạn |
|---|---|---|
| Problem Details | ErrorCode có 46 mã; ProblemResponse giữ code/correlationId/timestamp/retryable, lỗi form có field/code/message. FE parse/map theo API-ERR-01, không wrap envelope khác | Danh mục này vốn có trong YAML; implementation common mới không phải 46 API nghiệp vụ đã chạy |
| Correlation và response headers | Gửi X-Correlation-ID hợp lệ; server trả correlation. CORS expose correlation/request IDs, Retry-After, Idempotent-Replayed, Content-Disposition, X-Export-SHA256, X-Export-Audit-Id | Expose header không chứng minh mỗi endpoint đã sinh header hoặc export đã chạy |
| Cookie/CSRF | Credentials include; request ghi dùng cookie có X-CSRF-Token. Common từ chối request đồng thời mang Bearer và DB_SESSION | Không giữ logic Bearer/refresh legacy khi nối cookie auth. Chưa có session lifecycle thật để kiểm |
| Quyền | Common gate có account/transfer/audit/limits/roles/fraud và CHECKER; dùng để đối chiếu GAP-07 | Không tự suy ra ownership hoặc matrix final từ một filter. Limits approve yêu cầu CHECKER và một trong LIMIT_MAKER/LIMIT_MANAGE; roles approve yêu cầu CHECKER và ROLE_MANAGE |
| Môi trường | Default CORS http://localhost:5173; demo bắt buộc explicit origins, cookie secure=true. FE preview đang hướng dẫn 127.0.0.1:5174 | Trước tích hợp phải thống nhất origin/hostname/proxy/HTTPS; cookie SameSite và Secure cần kiểm browser, không chỉ thêm CORS |

Các ngưỡng policy còn nhãn Đề xuất trong nguồn vẫn mở; không hardcode policy FE theo AppProperties hoặc fixture.

### 3.3. Chưa thể chốt phần tích hợp session v2

| Thành phần | Dev hiện tại | FE/mock đã được người dùng chọn |
|---|---|---|
| Version | /api/v1; không có /api/v2 trong runtime/OpenAPI dev | Giữ v1, thêm /api/v2 riêng |
| Cookie | DB_SESSION, Path=/ | SESSION_TOKEN, Path=/api |
| Purpose | LOGIN_NEW_DEVICE trong v1 và common Problem validator | LOGIN_MFA trong v2 |
| Session runtime | SPI có sẵn; chưa có identity adapter hoặc login/getSession/logout controller | Types/mock v2 Draft; client/UI migration chưa hoàn tất |

Không chỉ đổi biến môi trường cookie là đủ: filter đang nhận DB_SESSION cố định, public auth matchers cố định /api/v1 và validator challenge có enum v1. BE cần review route/mapping/DB/cookie coexistence và cập nhật theo version, giữ v1 tương thích. Điều kiện cụ thể giữ tại V2-01..05 trong 07a. Dev không tự tạo ngoại lệ cho quyết định v2 của FE và FE không tự coi backend đồng ý triển khai v2.

Đầu vào cần BE/Security xác nhận trước integration: có nhận triển khai v2 theo 07a/YAML v2 không; timeline/readiness cho identity adapter và login/session/logout; mapping purpose DB và chính sách hai cookie/logout; HTTPS/origin/CSRF thực; matrix quyền chung. Đây là checklist bàn giao, chưa gửi thông báo cho người khác và chưa có câu trả lời.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Snapshot nhánh | Ghim đúng FE/dev, không đổi nhánh | frontend cfde9ad / dev 24606b5; working tree có sẵn dirty | Pass — đọc source | git rev-parse HEAD origin/dev; git status --short; 14/11/2026 |
| Phạm vi thay đổi | Phân biệt delta từ merge-base với chênh lệch hai đầu nhánh | 339 files; frontend source không đổi trên dev; FE docs đổi link | Pass — đọc diff | git diff HEAD...origin/dev --stat; git diff --name-status HEAD...origin/dev -- digitalbanking-docs/docs-common digitalbanking-docs/openapi digitalbanking-docs/docs-frontend digitalbanking-frontend |
| V1 baseline | Không tự thay v1, đối chiếu nội dung máy đọc | sameV1=true; SHA256 LF ở mục 1; 30 operations, 46 codes | Pass — contract tĩnh | Script PowerShell/Node tại mục 4.1; 14/11/2026 13:26:55 +07 |
| Business readiness | Không nhận common suite là E2E | Main source chỉ có common + bootstrap; controller operational; không v2 | Pass — giới hạn đã nhận diện | git ls-tree -r --name-only origin/dev -- digitalbanking-backend/src/main/java; đọc toàn bộ Java files để tìm controller; git grep /api/v2 origin/dev -- digitalbanking-backend/src/main digitalbanking-docs/openapi trả 1/no matches |
| Source/error alignment | ErrorCode runtime khớp đủ 46 mã YAML và đúng HTTP/retryable của 07 | 46/46 mã và metadata khớp | Pass — so sánh tĩnh | Script mục 4.1, exit 0; 14/11/2026 13:26:55 +07 |
| Docs links/checks | Phiếu và tracker liên kết đúng, không whitespace error trong phần cập nhật | 43 wikilink local có đích; 12 permalink dev có Git object; fences/whitespace đạt | Pass — tài liệu | git diff --check -- digitalbanking-docs/docs-frontend/management/03-work-tracker.md và script mục 4.2, exit 0; 14/11/2026 13:28:06 +07. Git cảnh báo LF→CRLF theo cấu hình working copy, không có lỗi whitespace |
| Delta 8 feature FE | Không nhầm sửa link thành đổi nghiệp vụ | Cả 8 file chỉ rút gọn wikilink backend (đường dẫn và đuôi .md) | Pass — so sánh tĩnh | Script mục 4.3, exit 0; 14/11/2026 13:28:16 +07 |
| FET-01/13/session API/browser | Login/bootstrap/logout/CSRF bằng BE v2 thật | Backend chưa có implementation và runtime v2 | Blocked / chưa chạy | Không có server/API/browser evidence; 203 tests là số BE báo cáo, không phải kiểm lại |

Lệnh đọc ban đầu có một đường dẫn design không tồn tại FE-SCR-01-login.md (exit 1); đã sửa lại đúng AUTH-LOGIN.md theo feature và đọc thành công. Không coi lệnh đọc lỗi là check đạt.

Script đối chiếu feature lần đầu exit 1 vì bộ chuẩn hóa kiểm tra bỏ đường dẫn nhưng chưa bỏ đuôi .md của wikilink cũ. Đã sửa script kiểm tra cho cả hai dạng và chạy lại đạt (mục 4.3); không sửa feature/source để làm check đạt.

### 4.1. Lệnh kiểm tra contract đã chạy

Chạy từ C:\project\cloude bằng PowerShell, Node và js-yaml đã cài trong frontend. Không cài dependency mới:

```powershell
@'
const cp=require('node:child_process'),fs=require('node:fs'),assert=require('node:assert/strict'),crypto=require('node:crypto'),yaml=require('./digitalbanking-frontend/node_modules/js-yaml');
const sha='24606b529197d6dd3e6908a7cd271b69b20dd4d2';const show=p=>cp.execFileSync('git',['show',sha+':'+p],{encoding:'utf8'});const apiPath='digitalbanking-docs/openapi/digital-banking-v1.yaml';const devRaw=show(apiPath).replace(/\r\n/g,'\n');assert.equal(devRaw,fs.readFileSync(apiPath,'utf8').replace(/\r\n/g,'\n'));const doc=yaml.load(devRaw);const source=show('digitalbanking-backend/src/main/java/com/simulatedbank/banking/common/error/ErrorCode.java');const runtime=Object.fromEntries([...source.matchAll(/^\s+([A-Z_]+)\((\d{3}), (true|false),/gm)].map(m=>[m[1],[m[2],m[3]]]));assert.deepEqual(Object.keys(runtime).sort(),[...doc.components.schemas.ErrorCode.enum].sort());
const semantics=show('digitalbanking-docs/docs-common/07. API contracts.md').split('### 4.3. Error code catalog')[1].split('#### 4.3.7.')[0];const expected=Object.fromEntries([...semantics.matchAll(/^\| `([A-Z_]+)` \| (\d{3}) \| ([^|]+)\|/gm)].map(m=>[m[1],[m[2],m[3].trim().startsWith('Không')?'false':'true']]));assert.equal(Object.keys(expected).length,46);assert.deepEqual(runtime,expected);
let ops=0;for(const entry of Object.values(doc.paths))for(const v of Object.values(entry))if(v.operationId)ops++;assert.equal(ops,30);
console.log('PASS: unchanged V1 LF hash '+crypto.createHash('sha256').update(devRaw).digest('hex'));console.log('PASS: 30 operations; all 46 runtime error codes/HTTP/retryable match OpenAPI/07');console.log('TIME '+new Date().toISOString());
'@ | node
```

Kết quả thực tế: exit 0; hai dòng PASS, hash như mục 1; TIME 2026-11-14T06:26:55.000Z. Không thay thế runtime contract/integration test.

### 4.2. Lệnh kiểm tra tài liệu đã chạy

```powershell
git diff --check -- digitalbanking-docs/docs-frontend/management/03-work-tracker.md
@'
const fs=require('node:fs'),cp=require('node:child_process'),assert=require('node:assert/strict');const files=['digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-14-cfde9ad/run-01.md','digitalbanking-docs/docs-frontend/management/03-work-tracker.md'];let wikilinks=0,permalinks=0;for(const p of files){const raw=fs.readFileSync(p,'utf8');assert(!raw.split('\n').some(x=>/[ \t]+$/.test(x.replace(/\r$/,''))),'Trailing whitespace '+p);assert.equal((raw.match(/^```/gm)||[]).length%2,0,'Code fences '+p);const text=raw.replace(/```[\s\S]*?```/g,'');for(const m of text.matchAll(/\[\[([^\]|]+)(?:\|[^\]]+)?\]\]/g)){assert(fs.existsSync(m[1].split('#')[0]),m[1]);wikilinks++;}if(p.includes('DEV-REVIEW'))for(const m of text.matchAll(/https:\/\/github\.com\/TNoLisme\/cloude\/blob\/([a-f0-9]{40})\/([^\s)]+)/g)){cp.execFileSync('git',['cat-file','-e',m[1]+':'+decodeURIComponent(m[2].split('#')[0])]);permalinks++;}}
console.log('PASS: '+wikilinks+' local wikilink targets; '+permalinks+' pinned dev Git paths; code fences and trailing whitespace');console.log('TIME '+new Date().toISOString());
'@ | node
```

Check chỉ kiểm đích file/link và cấu trúc ghi trên bảng, không thay review ngữ nghĩa hay kiểm tra mọi heading của bộ tài liệu.

### 4.3. Lệnh kiểm tra delta feature đã chạy

```powershell
@'
const cp=require('node:child_process'),assert=require('node:assert/strict');const paths=cp.execFileSync('git',['diff','--name-only','HEAD...origin/dev','--','digitalbanking-docs/docs-frontend'],{encoding:'utf8'}).trim().split('\n');assert.equal(paths.length,8);for(const p of paths){const diff=cp.execFileSync('git',['diff','HEAD...origin/dev','--',p],{encoding:'utf8'});const lines=diff.split('\n').filter(x=>/^[+-][^+-]/.test(x));assert.equal(lines.length,2);const normalize=s=>s.slice(1).replace(/\[\[digitalbanking-docs\/docs-backend\/modules\//g,'[[').replace(/\.md\]\]/g,']]');assert.equal(normalize(lines[0]),normalize(lines[1]));}console.log('PASS: all 8 changed FE feature docs only shorten backend wikilinks; no feature behavior delta');console.log('TIME '+new Date().toISOString());
'@ | node
```

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / standards / design / local guide | Không đổi hành vi hoặc UI; không đổi các tài liệu này. Baseline v2/ADR-002 giữ nguyên |
| Tracker / GAP | Thêm nhật ký review dev và liên kết phiếu; GAP-05/07 và các GAP còn lại không đóng |
| Review UI của người dùng | Không có UI mới trong lần chạy; không suy ra phê duyệt tooling/data/v2 từ yêu cầu đọc dev |
| Review tích hợp / PR | Chưa có; source common vẫn ghi chưa có reviewer sign-off |
| Readiness / xác nhận merge | Chưa có; không merge/push/commit trong lần chạy |

## 6. Kết quả lần chạy

Đã có căn cứ đối chiếu shared transport/error/headers/CSRF với common trên dev; đây là đầu vào triển khai FE, không phải nghiệm thu API. Session v2 và business integration chưa sẵn sàng, không đóng GAP. Phiếu phân tích Chờ review; task cha FE-FND-02 tiếp tục Đang làm. Phần mock v2 và shared client độc lập đã rõ vẫn có thể tiếp tục theo quyền đã cấp, nhưng mọi hành vi phụ thuộc quyết định còn mở phải chờ chốt. Không thực hiện phần implementation mới trong yêu cầu đọc nhánh này.
