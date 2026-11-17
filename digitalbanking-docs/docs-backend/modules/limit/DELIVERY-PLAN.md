# Module `limit` — Epic và feature

> **Dự án:** Digital Banking MVP
> **Ngày:** 15/11/2026
> **Trạng thái:** Đề xuất phân rã công việc — chưa xác nhận triển khai
> **Phạm vi:** Backend `limit` và các điểm tích hợp trực tiếp; không bao gồm giao diện

## 1. Cách dùng kế hoạch

**4 epic, 10 feature. Mỗi feature là một đơn vị dự kiến cho một vòng SDD và một PR.** Epic là nhóm kết quả để theo dõi, không phải một lần giao việc cho agent.

Khi bắt đầu feature, chỉ đọc phần tương ứng, dependency và các mục nguồn được dẫn. Vòng SDD của feature sẽ làm rõ thiết kế, lập task và triển khai; trang này không ấn định class, chữ ký hàm, SQL hay payload API. Nếu một feature cần xây thêm một năng lực độc lập của module khác, tách việc đó cho owner liên quan trước khi mở rộng PR.

Mỗi feature bao gồm kiểm thử phù hợp, cập nhật tài liệu bị ảnh hưởng và bằng chứng nghiệm thu. Không dồn kiểm thử vào một feature cuối. Khi thiếu consumer thật, có thể kiểm chứng phần cung cấp bằng test harness; phần tích hợp vẫn để chưa nghiệm thu.

**Thứ tự nguồn:** `docs-common/01–11` → `docs-common/design/` → tài liệu Backend/Frontend. Giữ nguyên nhãn Must/Should, Đề xuất/Open của nguồn; kế hoạch này không tự nâng mức ưu tiên hay chốt policy. Khi các tài liệu 01–11 mâu thuẫn, ghi điểm cần quyết định, không suy ưu tiên từ số hiệu.

## 2. Ranh giới module

- `limit` sở hữu `limit_config`, `limit_usage`, cách xác định hạn mức hiệu lực, kiểm tra/cập nhật usage và vòng đời cấu hình hạn mức.
- `transfer` điều phối T0/T1/T2/T3, idempotency và kết quả giao dịch tiền. `limit` tham gia transaction T2 của caller; không tự mở transaction tiền độc lập.
- `admin` sở hữu `approval_request`, workflow maker/checker và các endpoint quản trị; thay đổi dữ liệu hạn mức qua giao diện do `limit` cung cấp.
- `identity` sở hữu session, quyền và step-up; `audit` sở hữu ghi audit. Các feature tích hợp dùng các năng lực này, không xây lại chúng trong `limit`.
- FE, workflow approval cho role/retention, fraud engine, Cloud deployment và cơ chế đối soát toàn hệ thống nằm ngoài backlog này. Các kết nối cần thiết với chúng phải được ghi khi bàn giao.

Phân công phối hợp theo tài liệu 11: Vũ (`transfer`, `limit`), Phương (`ledger`, `common`), Đạt (`account`, `admin`, `identity`), Thịnh (`audit`), Thắng (FE). Cách chia bốn module Vũ/Phương trong §2 vẫn mang nhãn **đề xuất**, cần nhóm xác nhận; kế hoạch này không thay phân công.

## 3. Bản đồ công việc

| Epic | Kết quả quan sát được | Feature | Căn cứ nghiệp vụ |
|---|---|---|---|
| **E1 — Dữ liệu và quy tắc hạn mức** | Lưu được dữ liệu đúng ràng buộc; xác định được các hạn mức áp dụng | LIM-01, LIM-02 | FR-016, FR-031–033 |
| **E2 — Thực thi hạn mức trong chuyển tiền** | Chuyển tiền không vượt hạn mức, không cộng usage sai khi lỗi/retry/đồng thời | LIM-03, LIM-04, LIM-05 | FR-016; AC-009–013, AC-034 |
| **E3 — Khách hàng tra cứu hạn mức** | Customer xem được hạn mức và phần còn lại của chính mình | LIM-06 | FR-031 **Should**; AC-017 |
| **E4 — Thay đổi hạn mức có kiểm soát** | Đề xuất chưa làm đổi hạn mức; checker quyết định và thay đổi có audit | LIM-07–10 | FR-032 **Should**; FR-033/035/051 **Must**; AC-017/019/028 |

E1 và E2 là nền cho enforcement bắt buộc. Việc xem/cấu hình hạn mức có mức ưu tiên theo SRS; một khi triển khai cấu hình, validation, maker/checker và audit là bắt buộc.

## 4. E1 — Dữ liệu và quy tắc hạn mức

### LIM-01 — Nền dữ liệu cấu hình và usage

**Kết quả:** `limit` có nơi lưu cấu hình theo version và usage theo ngày, đúng mô hình nguồn chuẩn.

**Ranh giới:** migration, đọc/ghi dữ liệu thuộc hai bảng của `limit` và các ràng buộc liên quan. Chưa có enforcement chuyển tiền, REST API hay workflow approval. Phối hợp thứ tự migration/FK với owner dữ liệu được tham chiếu; không tạo bảng giả của module khác để chạy được.

**Nghiệm thu:**

- PostgreSQL chấp nhận dữ liệu hợp lệ, từ chối dữ liệu sai ràng buộc; một scope chỉ có một cấu hình `ACTIVE`, một counter cho một scope/ngày.
- Lưu được lịch sử version, trạng thái chờ duyệt và usage tách biệt; không ghi đè lịch sử cấu hình.
- Migration và kiểm thử persistence chạy trên PostgreSQL; không dùng mock để chứng minh constraint.

**Phụ thuộc:** schema/FK và thứ tự migration liên module đã thống nhất. **Tra cứu:** nguồn B §4.5.1–4.5.2, nguồn C §6.1–6.3.

### LIM-02 — Xác định hạn mức hiệu lực

**Kết quả:** có một cách nhất quán để xác định cấu hình customer/account áp dụng tại một thời điểm và kiểm tra hạn mức từng lần.

**Ranh giới:** quy tắc chọn cấu hình, thời gian hiệu lực và kết hợp các scope. Chưa khóa/cộng usage, chưa cung cấp endpoint; không thêm scope `GLOBAL` từ bản tóm tắt backend.

**Nghiệm thu:**

- Chỉ chọn cấu hình `ACTIVE` đang trong khoảng hiệu lực; loại cấu hình pending/rejected/superseded hoặc ngoài thời gian hiệu lực.
- Khi cả customer/account có cấu hình, kiểm tra cả hai; không để account override làm nới customer limit. Giá trị bằng hạn mức được chấp nhận, vượt hạn mức bị từ chối.
- Bao phủ customer-only, account-only, cả hai và không có cấu hình. Trường hợp thiếu cấu hình khi enforcement phải bám quyết định D1, không tự coi là vô hạn hoặc tự thêm mặc định.

**Phụ thuộc:** LIM-01; D1 cho nhánh enforcement thiếu cấu hình. **Tra cứu:** nguồn A §2.4/2.6, nguồn B §4.5.1, nguồn D §7.2.13/8.5.1.

## 5. E2 — Thực thi hạn mức trong chuyển tiền

### LIM-03 — Kiểm tra hạn mức ngày dưới khóa

**Kết quả:** hai giao dịch đồng thời không thể cùng dùng phần hạn mức ngày còn lại dựa trên dữ liệu cũ.

**Ranh giới:** tạo/lấy usage, khóa và kiểm tra trong T2 do caller quản lý. Chưa cộng usage, chưa nối toàn luồng transfer. Giữ thứ tự khóa chung; customer-level usage còn là điểm đồng bộ được fraud sử dụng.

**Nghiệm thu:**

- Đọc lại cấu hình áp dụng và kiểm tra từng lần/ngày trong T2, không dùng kết quả kiểm tra sớm ở T0 làm quyết định cuối.
- Tạo/khóa customer usage, rồi account usage khi có cấu hình account, đúng nguồn E; cạnh tranh tạo counter đầu ngày không sinh counter trùng.
- Cùng một T2 dùng một ngày nghiệp vụ theo timezone cấu hình; test thời điểm chuyển ngày và T2 đi qua nửa đêm chứng minh ngày không đổi giữa chừng.

**Phụ thuộc:** LIM-01–02; D2 về timezone. **Tra cứu:** nguồn B §4.5.2/7.7, nguồn E §5.3/7.3/7.5/9.2; `T-LIM-02/03`.

### LIM-04 — Ghi nhận usage cùng transaction tiền

**Kết quả:** usage chỉ phản ánh giao dịch hoàn tất, cùng kết quả commit/rollback của T2.

**Ranh giới:** phần cộng usage sau khi caller xử lý tiền thành công, dùng cùng scope/ngày và khóa của LIM-03. Không tự quản lý trạng thái transfer, retry hoặc ledger.

**Nghiệm thu:**

- T2 hoàn tất thì số tiền và số giao dịch tăng đúng ở các counter áp dụng; khách hàng chuyển từ nhiều tài khoản vẫn dùng chung counter customer.
- Business rejection không cộng usage; lỗi kỹ thuật/audit khiến T2 rollback thì không để lại phần usage đã tăng.
- Kiểm thử PostgreSQL bằng các transaction độc lập chứng minh check và increment dùng chung khóa: tổng giao dịch được chấp nhận không vượt trần; usage khớp tổng đã hoàn tất (`T-LIM-01/02`).

**Phụ thuộc:** LIM-03. **Tra cứu:** nguồn B §4.5.2/6.6/7.7, nguồn C §7.2/8.2–8.4, nguồn E §9.2; nguồn F §7.2.

### LIM-05 — Nối limit vào luồng transfer thật

**Kết quả:** chuyển tiền thực tế thực thi limit; retry/recovery không làm cộng usage hai lần.

**Ranh giới:** nối các năng lực đã có của `limit` vào T0/T2 và mapping kết quả tại `transfer`. Không xây lại transfer orchestrator, idempotency engine hay chính sách step-up. Đây là PR tích hợp phối hợp owner `transfer`.

**Nghiệm thu:**

- Vượt hạn mức trả `LIMIT_EXCEEDED` theo hợp đồng transfer, không debit/credit, không ledger và không tăng usage; đúng ngưỡng và đủ điều kiện khác thì hoàn tất.
- Snapshot usage phục vụ T0 không thay enforcement T2; hai transfer đồng thời từ cùng hoặc khác tài khoản của một customer không vượt hạn mức customer/account.
- Retry cùng giao dịch và recovery không cộng usage lại; lỗi sau bước tăng usage nhưng trước commit không để tiền và usage lệch nhau. Có bằng chứng từ transfer thật cho các ca này.

**Phụ thuộc:** LIM-03–04 và luồng T2/idempotency/recovery của `transfer` cùng các module tham gia đã sẵn sàng cho từng ca. **Tra cứu:** nguồn A §2.4/10, nguồn C §7.2–7.4, nguồn D §8.3.1, nguồn E §5.1/5.3/5.5/9.2; nguồn F `T-LIM-01–03`, `T-IDEM-*`, `T-REC-*` phù hợp.

## 6. E3 — Khách hàng tra cứu hạn mức

### LIM-06 — API tra cứu hạn mức của chính khách hàng

**Kết quả:** customer đọc được cấu hình áp dụng, usage trong ngày và hạn mức còn lại qua `GET /api/v1/limits`.

**Ranh giới:** query và endpoint customer, gồm lọc theo account và kiểm tra ownership. Không thêm API quản trị hay UI; không tạo/khóa counter chỉ để hiển thị.

**Nghiệm thu:**

- Response đúng nguồn D: cấu hình đang hiệu lực, usage đúng scope/ngày, remaining không âm; effective remaining dùng phần còn lại của từng scope, không lấy một daily limit chung trừ một counter chung.
- Customer chỉ đọc dữ liệu của mình; lọc account của người khác bị chặn. Không có cấu hình trả danh sách rỗng và effective fields `null` theo hợp đồng.
- Read không giữ khóa enforcement, không thay đổi dữ liệu; test được usage trước/sau giao dịch commit, ranh giới ngày và hợp đồng response/lỗi.

**Phụ thuộc:** LIM-01–02, quy ước ngày của LIM-03, identity/account cho quyền và ownership. Có thể phát triển query trước LIM-05; nghiệm thu dữ liệu sau transfer thật cần LIM-05. **Tra cứu:** nguồn A FR-031/AC-017 và §4.3, nguồn D §7.2.13/8.5.1; nguồn E §9.2.

## 7. E4 — Thay đổi hạn mức có kiểm soát

### LIM-07 — Chuẩn bị cấu hình chờ duyệt qua giao diện nội bộ

**Kết quả:** `admin` có thể nhờ `limit` kiểm tra đề xuất và lưu version `PENDING_APPROVAL` gắn với approval.

**Ranh giới:** nghiệp vụ phía `limit`: kiểm tra giá trị/phạm vi chính sách, tạo cấu hình pending và liên kết approval. Chưa có endpoint maker; không trực tiếp tạo/sửa `approval_request` thuộc `admin`.

**Nghiệm thu:**

- Giá trị hợp lệ tạo được cấu hình chờ duyệt; số không hợp lệ, quan hệ hạn mức sai hoặc ngoài policy bị từ chối.
- Đề xuất pending không đổi cấu hình đang áp dụng, không đổi usage; còn đủ thông tin để tra cứu và quyết định về sau.
- Trong transaction do `admin` điều phối, lỗi tạo một phần khiến toàn bộ đề xuất rollback; kiểm chứng phía provider được bằng PostgreSQL trước khi tích hợp endpoint.

**Phụ thuộc:** LIM-01–02, D1 về policy, hợp đồng và FK approval với `admin`. **Tra cứu:** nguồn A FR-032/033 và UC-011, nguồn B §4.5.1/4.5.3, nguồn D §8.5.2.

### LIM-08 — Áp dụng hoặc từ chối version đã đề xuất

**Kết quả:** `limit` xử lý quyết định trên cấu hình pending mà vẫn giữ lịch sử và tính nguyên tử.

**Ranh giới:** chuyển trạng thái/version cấu hình qua giao diện nội bộ; caller `admin` chịu trách nhiệm xác thực checker và ghi quyết định approval/audit trong cùng transaction. Chưa có endpoint checker.

**Nghiệm thu:**

- Approve tái kiểm tra policy, chuyển version cũ/mới đúng nguồn chuẩn; reject giữ nguyên hạn mức đang áp dụng và đánh dấu bản đề xuất bị từ chối. Không tăng/reset usage khi đổi cấu hình.
- Không áp dụng lại một đề xuất đã xử lý; các quyết định cạnh tranh trên cùng scope không tạo hai cấu hình active hay mất lịch sử. Kiểm chứng cả lần cấu hình đầu tiên.
- Lỗi trong transaction của caller rollback thay đổi cấu hình. Trường hợp hiệu lực tương lai và bật scope account giữa ngày có kiểm chứng theo quyết định D3/D4 trước khi kết luận hoàn thành.

**Phụ thuộc:** LIM-07; D3/D4. **Tra cứu:** nguồn B §4.5.1–4.5.3, nguồn D §8.5.3, nguồn E §9.2; AC-017/028.

### LIM-09 — Tích hợp endpoint maker tạo đề xuất

**Kết quả:** maker đủ quyền tạo được một yêu cầu đổi hạn mức có audit, chưa tác động enforcement.

**Ranh giới:** phần `LIMIT_CHANGE` của `POST /api/v1/admin/limits`, phối hợp owner `admin`. Dùng workflow approval, step-up và audit có sẵn; không nhận xây approval platform hoặc endpoint danh sách/chi tiết dùng chung.

**Nghiệm thu:**

- Quyền, CSRF khi dùng cookie và step-up được kiểm tra theo nguồn D; chưa verify thì chưa tạo approval/config pending.
- Thành công tạo approval `PENDING`, config `PENDING_APPROVAL` và audit `LIMIT_CHANGE_REQUESTED` nguyên tử; response/Location đúng hợp đồng. Lỗi giữa các bước không để dữ liệu dở dang.
- Kiểm thử với admin/identity/audit thật cho ca hợp lệ, thiếu quyền, thiếu step-up và đề xuất không hợp lệ; cấu hình đang áp dụng không đổi.

**Phụ thuộc:** LIM-07; admin approval, identity step-up, customer/account và audit. **Tra cứu:** nguồn A §4.4/UC-011, nguồn D §8.5.2, nguồn B §4.5.3; AC-017/019/028.

### LIM-10 — Tích hợp endpoint checker và kiểm chứng thay đổi có hiệu lực

**Kết quả:** checker khác maker approve/reject, và cấu hình được đọc/thực thi đúng sau quyết định.

**Ranh giới:** phần `LIMIT_CHANGE` của `POST /api/v1/admin/limits/{id}/approve`, phối hợp owner `admin`. Không thêm quản trị role/retention, giao diện staff hoặc một engine step-up mới.

**Nghiệm thu:**

- Đúng quyền/step-up và checker khác maker mới được quyết định; `{id}` là approval ID. Tự duyệt bị chặn; mã lỗi theo quyết định D5.
- Approve tạo kết quả approval `APPLIED`, config và audit `LIMIT_CHANGE_APPROVED` cùng transaction; reject tạo các trạng thái tương ứng và audit `LIMIT_CHANGE_REJECTED`, không áp dụng hạn mức. Approve lại/duyệt đồng thời chỉ có một quyết định có hiệu lực; audit lỗi rollback toàn bộ thay đổi.
- Kiểm chứng từ endpoint qua dữ liệu thật: GET limits và transfer tiếp theo dùng cấu hình đúng thời gian hiệu lực; usage trước đó được giữ nguyên, remaining không âm khi hạ hạn mức xuống dưới mức đã dùng. Có ca future-effective theo D3 và scope account giữa ngày theo D4.

**Phụ thuộc:** LIM-08–09; LIM-05–06 cho kiểm chứng tác động; admin/identity/audit thật; D5. **Tra cứu:** nguồn A §4.4/UC-011/AC-017/028, nguồn D §8.5.1–8.5.3, nguồn B §4.5; nguồn F §7/10.

## 8. Thứ tự thực hiện và cách đóng feature

- **Nền:** LIM-01 → LIM-02.
- **Money path:** LIM-03 → LIM-04 → LIM-05.
- **Tra cứu:** LIM-06 sau nền và quy ước ngày; không phải chờ xong toàn bộ E2 để bắt đầu.
- **Thay đổi:** LIM-07 → LIM-08; LIM-09 chỉ cần LIM-07 và các dependency tích hợp. LIM-10 ghép các nhánh sau khi provider/consumer liên quan sẵn sàng.

Với một dev/agent, thứ tự mặc định là **01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10**. Nếu transfer chưa sẵn sàng, làm phần độc lập của 06/07/08; không tạo nghiệp vụ transfer giả trong production để đóng 05. Chuỗi này là thứ tự kỹ thuật, không tự thay milestone W4–W5 hoặc quality gate W6 của tài liệu 10–11.

Một feature được nghiệm thu khi đạt các tiêu chí riêng, có bằng chứng test phù hợp truy được về nguồn và không còn blocker ảnh hưởng tiêu chí. Dùng PostgreSQL cho constraint/locking/transaction; mock chỉ dùng cho phần thích hợp. PR ghi ngắn: phạm vi thay đổi, nguồn quyết định, các ca đã chạy, kết quả và dependency còn mở. Phân biệt phần provider đã kiểm chứng với tích hợp chưa kiểm chứng; không dùng build pass hoặc test double để kết luận toàn luồng đã đạt. Review của con người được ghi riêng.

## 9. Điểm nguồn cần lưu ý trước vòng SDD

Bản `modules/_overview/06-limit.md` là bản dẫn xuất, hiện khác `04`/`07`: có `GLOBAL` và schema `scope_type/scope_id`, tên trường usage khác, tên permission khác; mutation mới chỉ mô tả áp dụng mà chưa đủ bước pending/reject. **Không lấy các chi tiết đó làm baseline code.** Đọc mô hình tại B và hợp đồng tại D; cập nhật bản dẫn xuất khi feature liên quan chốt thiết kế.

Các mục dưới đây là dependency quyết định, không phải đặc tả mới do kế hoạch tự đặt ra. Chỉ chặn phần bị ảnh hưởng; tiếp tục phần độc lập và giữ trạng thái chưa nghiệm thu nếu còn thiếu.

| ID | Cần làm rõ/chốt | Feature ảnh hưởng | Nguồn và đầu mối phối hợp |
|---|---|---|---|
| D1 | Max/default của policy và xử lý chuyển tiền khi không có cấu hình. GET trả rỗng/null đã được định nghĩa, nhưng không suy từ đó ra enforcement vô hạn hoặc fallback GLOBAL. | 02, 05, 07–10 | D §11.3 `OI-API-05`; BA và owner limit/transfer |
| D2 | Giá trị timezone ngân hàng: nguồn 01–11 còn ghi `Asia/Ho_Chi_Minh` là đề xuất, cấu hình triển khai có default này. Giữ timezone cấu hình; xác nhận giá trị dùng cho nghiệm thu. | 03–06, ca liên quan ở 10 | A `BRL-012` qua BRD, B §4.5.2, D §8.5.1; BA/BE |
| D3 | Chuyển tiếp cấu hình có `effectiveFrom` tương lai: D cho phép tương lai nhưng approve supersede bản cũ ngay, còn query chỉ lấy ACTIVE đang hiệu lực. Cần tránh khoảng trống hiệu lực; chưa tự thêm scheduler hay đổi state machine. | 08, 10; ca đọc hiệu lực ở 02/06 | B §4.5.1 và D §8.5.1–8.5.3; owner limit/admin, BA |
| D4 | Nếu bắt đầu có hạn mức account giữa ngày, usage account tính phần đã chuyển trước đó thế nào? E chỉ tạo/khóa account counter khi có cấu hình; cần thống nhất với yêu cầu usage trong ngày, không tự chọn reset hay backfill. | 08, 10; kiểm chứng counter ở 04 | B §4.5.2, D §7.2.13/8.5.1, E §5.3/9.2; owner limit/transfer |
| D5 | Mã lỗi maker tự duyệt: A UC-011 ghi 403, D §8.5.3 ghi `409 ROLE_CONFLICT`. Thống nhất mapping và đồng bộ nguồn liên quan trước test hợp đồng. | 10 | Hai nguồn cùng nhóm ưu tiên 01–11; owner admin/API |

Retry POST admin có thể tạo đề xuất trùng đang là **Open** `OI-API-19` tại D §11.3. Không thêm cơ chế idempotency admin vào các feature này khi chưa có quyết định mở rộng; phân biệt với yêu cầu một approval không được áp dụng hai lần, vốn thuộc LIM-08/10.

## 10. Bản đồ tra cứu nguồn

| Nguồn | Tài liệu | Dùng cho |
|---|---|---|
| **A** | [[digitalbanking-docs/docs-common/03. SRS.md]]; nghiệp vụ gốc tại [[digitalbanking-docs/docs-common/02. BRD.md]] | FR-016, FR-031–035, FR-051; UC-011; AC-009/017/019/028; BRL-012 ở BRD |
| **B** | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] | §4.5 cấu hình/usage/approval, §6.6 dữ liệu dẫn xuất, §7.7 transaction/locking |
| **C** | [[digitalbanking-docs/docs-common/06. System architecture.md]] | §6 module và ownership; §7 runtime; §8 transaction và consistency |
| **D** | [[digitalbanking-docs/docs-common/07. API contracts.md]] | §7.2.13–14 schema; §8.3 transfer; §8.5 limits/approval; §11 open issues |
| **E** | [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | §5.3 T2; §7 locking; §9.2 limit/business date; §14.2 test catalog |
| **F** | [[digitalbanking-docs/docs-common/09. Test plan.md]] | Test strategy, evidence và tiêu chí nghiệm thu; không phải kết quả test |
| **Kế hoạch/nhân sự** | [[digitalbanking-docs/docs-common/10 Master timelines.md]], [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] | Mốc, phân công và điểm phối hợp |
| **Bổ trợ** | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]], [[digitalbanking-docs/docs-backend/deployment/configuration.md]] | Bản máy đọc phải khớp D; cấu hình triển khai phải khớp nguồn chuẩn |

## 11. Phase bổ sung — Chuẩn hóa code và persistence của `limit`

> **Trạng thái:** Đã thực hiện refactor và kiểm thử trong phạm vi code hiện có; tích hợp provider thật và nghiệm thu money path vẫn Blocked. Xem [REF-VERIFICATION.md](REF-VERIFICATION.md).
> **Phạm vi:** Code `limit` của E1–E4, kể cả E3 đã làm; phần approval/endpoint của E4 thuộc `admin` được phối hợp với owner `admin`. Các module khác chỉ thay đổi khi cần contract chung đã được thống nhất. Phase này không tự mở rộng nghiệp vụ hoặc nhận thay việc phát triển module khác.

### 11.1. Kết quả rà soát hiện tại

| Vấn đề | Bằng chứng trong source hiện tại | Việc cần xử lý |
|---|---|---|
| Persistence dùng JDBC | `limit/adapter/out/LimitJdbcRepository.java` dùng `JdbcTemplate` cho config, usage, lock và lifecycle; `admin/adapter/out/ApprovalJdbcRepository.java` dùng `JdbcTemplate` cho approval của E4. | Chuyển toàn bộ đường persistence production trong phạm vi sang Spring Data JPA, kể cả native query. Các test limit/admin cũng còn dùng `JdbcTemplate` và cần được rà soát, chuyển theo kế hoạch kiểm thử bên dưới. |
| Entity/mapping chưa đầy đủ | E1/E2/E4 map `ResultSet` trực tiếp sang record; `LimitConfiguration`, `LimitUsage` và `ApprovalRequest` không phải JPA entity. Entity đọc của E3 `LimitReadEntity` chỉ ánh xạ `limit_id`, còn dữ liệu trả qua native projection. | Ánh xạ đầy đủ các cột cần thiết của `limit_config`, `limit_usage` và, với owner admin, `approval_request`; kiểm chứng ID, nullable, enum, tiền, thời gian và JSONB theo `04`/schema thật. Không coi projection ID hiện có là entity mapping đầy đủ. |
| Mapping và boilerplate chưa nhất quán | Các service cũ tự khai báo constructor; `LimitJdbcRepository`/`ApprovalJdbcRepository` tự map từng trường và `LimitAdminController.ApprovalResponse.from` tự dựng response. E3 đã có MapStruct/Lombok nhưng chưa thống nhất với phần cũ. | Dùng Lombok cho boilerplate phù hợp và MapStruct cho các chuyển đổi entity ↔ snapshot/DTO và response phù hợp; giữ record bất biến khi có ích, không ép Lombok lên mọi record, không dùng `@Data` cho JPA entity. |
| Ranh giới lớp trong module | `LimitRuleService`, `LimitEnforcementService`, `LimitChangeService` phụ thuộc trực tiếp `LimitJdbcRepository`; `LimitCustomerService` và `LimitViewMapper` của E3 phụ thuộc `LimitReadRepository`/`Row` của adapter-out. `LimitAdminController` tự map response. | Application làm việc qua outbound port và model/snapshot của chính module; adapter-out sở hữu JPA entity/repository; web DTO và mapper ở adapter-in, theo `06` §6.1 và convention backend. Không lộ entity/projection persistence qua `api` hoặc HTTP. |
| Bảo vệ ranh giới chưa đầy đủ | `ModuleBoundaryArchTest` kiểm tra `common` một chiều, domain, inbound → outbound và cycle; chưa kiểm tra tổng quát import sang package ngoài `api` của module khác hoặc application → adapter-out. Rà soát import trực tiếp hiện thấy `admin`/`limit` gọi module khác qua `*.api`, chưa có bằng chứng để kết luận toàn bộ cross-module đã được enforce. | Bổ sung rule phù hợp cho module đang sửa, có kiểm chứng cả generated code và ngoại lệ SPI `common` được tài liệu hóa; không gọi tắt sang repository/entity module khác. |
| Rủi ro transaction khi thay cơ chế persistence | `LimitJdbcRepository` hiện có `ON CONFLICT`, `RETURNING`, `FOR UPDATE`, advisory lock, kiểm tra isolation/transaction và conditional update; E2 có guard handle hết hiệu lực trước/ở `afterCommit`. E4 dựa vào transaction chung giữa admin, limit, step-up và audit. | Kiểm chứng ngữ nghĩa trên PostgreSQL trước khi chuyển từng đường ghi; JPA flush, persistence context và native update không được làm thay đổi lock order, snapshot, rollback hoặc thứ tự audit. |

`05. Technical stack` §5.2.2 có cho phép JDBC ở một số trường hợp, nhưng chỉ đạo hiện tại của người điều hành **chặt hơn**: phase này không dùng `JdbcTemplate` cho persistence; native SQL phải được thực hiện qua Spring Data JPA repository và mapping/projection phù hợp. `06` yêu cầu domain thuần, không mang JPA/Spring vào domain; các liên kết sang bảng module khác dùng ID/contract, không tạo quan hệ JPA để truy cập chéo ownership.

### 11.2. Thứ tự thực hiện và tiêu chí cho từng phần

Mỗi phần dưới đây là một đơn vị thực hiện/kiểm thử có thể review riêng; chạy baseline trước khi sửa và không đánh dấu hoàn thành nếu invariant hoặc dependency thuộc phần đó chưa được chứng minh. Có thể chia PR theo thứ tự, nhưng không để thay đổi JPA dở dang phá luồng đang dùng.

#### LIM-REF-01 — Baseline và hợp đồng cần giữ

- Lập danh sách operation, query và caller của hai repository cũ; ánh xạ tới `LIM-01–10`, contract `limit.api`, response của `07` và các test hiện có. Chụp baseline build/test, schema/migration, API response/error và dữ liệu kiểm thử trước khi thay thế.
- Ghi rõ các behavior cần giữ: hiệu lực hai scope, `NOT_CONFIGURED` không cho phép tự động chuyển tiền, usage theo ngày, lifecycle/version, maker/checker, policy, ownership và các giới hạn tích hợp hiện tại. Đối chiếu quyết định đã phản ánh trong nguồn chuẩn, gồm `07` §8.5.2–8.5.3 và `DEC-LIM-001`; không tự chốt các điểm còn mở hoặc âm thầm thay quyết định D1–D5 đã có.
- Kiểm tra contract với Transfer/Admin/Account/Identity/Audit tại `design/05`; nếu thiếu dependency/operation thì nêu phương án, cập nhật contract theo chỉ dẫn dự án và mock theo contract khi provider chưa sẵn sàng. Không tạo bean giả trong production.
- **Nghiệm thu:** có ma trận operation → nguồn → test/bằng chứng, lệnh và kết quả baseline; các blocker được ghi rõ. Không dùng kết quả lịch sử thay cho baseline trên revision thực tế.

#### LIM-REF-02 — Entity, JPA repository và mapping dữ liệu `limit`

- Tạo mapping persistence cho `limit_config`, `limit_usage` theo `04` §4.5.1–4.5.2 và migration hiện hành; tách model/snapshot `api` và domain khỏi JPA entity. Chọn một mapping nhất quán cho E1/E2/E3/E4, tránh hai entity quản lý cùng bảng/identity khi không cần. Thiết kế MapStruct mapping đầy đủ hai chiều và projection read khi cần; không làm mất precision/scale, enum, null, version, thời gian hay giá trị DB sinh.
- Thay `LimitJdbcRepository` bằng Spring Data JPA repository/adapter thông qua port thuộc `limit`; truy vấn thường dùng JPA, truy vấn PostgreSQL đặc thù dùng native query qua Spring Data JPA. Chứng minh cách xử lý `ON CONFLICT` với partial unique index, insert/update có `RETURNING`, row/advisory lock, conditional update, affected rows, `transaction_timestamp()` và `clock_timestamp()` trên stack thực tế; nếu một dạng `RETURNING` không hỗ trợ trong Spring Data JPA, chọn cách tương đương vẫn giữ atomicity và kiểm thử race, không quay lại `JdbcTemplate`.
- Kiểm soát flush/clear/refresh, thứ tự phát SQL và first-level cache khi trộn entity với native DML; không overwrite dữ liệu mới bằng entity cũ. Không đổi DDL, grant hay migration chỉ để làm ORM chạy; nếu thực sự cần migration, thiết kế forward-only, đối chiếu `04`/`08`, test nâng cấp dữ liệu và quyền DB trước khi áp dụng.
- Hợp nhất hoặc điều chỉnh read path E3 để dùng cùng model/adapter phù hợp; giữ một câu đọc snapshot nhất quán, không tạo/khóa usage, JSON/nullable và semantics `DEC-LIM-001`.
- **Nghiệm thu:** PostgreSQL round-trip và constraint/precision/nullability theo `04`; không còn `JdbcTemplate`/manual `ResultSet` trong persistence production của `limit`; test E1 và E3 đạt, bao gồm hiệu lực biên, usage thiếu, remaining và không có side effect.

#### LIM-REF-03 — Enforcement và thay đổi cấu hình an toàn trong transaction

- Chuyển `LimitRuleService`, `LimitEnforcementService`, `LimitChangeService` và policy sang port/model/mapping mới; dùng Lombok có chọn lọc và tái sử dụng error, clock, context/config của `common`. Đặt transaction boundary ở application; provider JOIN caller, không commit riêng trong T2 hay approval.
- Giữ `READ COMMITTED`, thời điểm và business date cố định của T2; giữ guard cấu hình/activation hiện có, thứ tự khóa, upsert counter và kiểm tra cả customer/account. Giữ conditional increment, số dòng cập nhật, single-use handle, chống dùng sau commit/khác transaction và xử lý business rejection/technical failure đúng contract. Không tự loại bỏ guard hiện có chỉ vì có `@Transactional`.
- Giữ policy/revalidation, pending/active/superseded/rejected, expected version, no future effective, guard bật account giữa ngày, không reset/backfill usage và không tự duyệt theo các quyết định đã phản ánh trong `07`. Không chuyển quyền sở hữu approval sang limit.
- **Nghiệm thu:** test PostgreSQL với transaction/connection độc lập chứng minh race tạo usage lần đầu, đồng thời cùng/khác account, ngưỡng bằng/vượt, conditional increment nhiều handle, rollback sau increment/audit failure, transaction đã hoàn tất/`afterCommit`, thời điểm qua nửa đêm và cạnh tranh activation/config. Các test `LimitEnforcementIT`, `LimitBusinessDateIT`, `LimitChangesIT` và case tương đương phải giữ hoặc tăng coverage, không xóa/giảm oracle để làm xanh test.

#### LIM-REF-04 — Tích hợp E4 trong `admin` và response/DTO

- Phối hợp owner `admin` chuyển `approval_request` của workflow LIMIT_CHANGE từ `ApprovalJdbcRepository` sang entity/repository Spring Data JPA do admin sở hữu; map JSONB, state, ID, timestamp và DTO qua mapping phù hợp. Không dùng entity của `admin` trong `limit`, không để `limit` ghi trực tiếp bảng approval, không thay các workflow admin ngoài phạm vi.
- Tách request/response DTO và MapStruct web mapper khỏi logic điều phối trong controller; dùng common Problem/error, request context, security và validation hiện có. Duy trì signature/semantics contract liên module và JSON/status/Location/error của `07`/OpenAPI, trừ khi phát hiện mâu thuẫn phải đưa owner quyết định.
- Kiểm chứng approval + pending config + consume challenge + audit C2 cùng transaction và thứ tự ghi đúng; `FOR UPDATE`, stale version, cạnh tranh checker, rollback, fail-closed khi provider thiếu và C3 denial theo contract. Reuse provider API của identity/account/customer/audit; test double chỉ ở test.
- **Nghiệm thu:** không còn `JdbcTemplate`/manual `ResultSet` trong persistence production của phần admin liên quan; PostgreSQL test cho insert/JSONB, lock/decide, rollback và quyền DB; HTTP/contract test giữ nguyên 201/200, 403/404/409/422/428/503, CSRF, step-up và response của E4.

#### LIM-REF-05 — Enforce boundary, hồi quy và bàn giao

- Bổ sung ArchUnit cho `limit` và phần admin liên quan: không import module khác ngoài `api`/SPI được công bố; không lộ entity/JPA repository ra API/HTTP; domain thuần; inbound không phụ thuộc outbound; application dùng port thay adapter-out; không tạo cycle hoặc `common` → business. Test rule bằng ví dụ vi phạm có kiểm soát để tránh rule chạy xanh vì không match class; kiểm tra MapStruct-generated code.
- Chuyển các test `limit`/`admin` còn phụ thuộc `JdbcTemplate` sang test helper/repository phù hợp với yêu cầu JPA; giữ oracle độc lập cho constraint, grant, lock và rollback bằng fixture SQL/connection PostgreSQL riêng khi cần. Dữ liệu/SQL phục vụ setup và quan sát test không được trở thành persistence production hoặc fake module production.
- Chạy lại baseline tương ứng, toàn bộ test limit/admin/common bị ảnh hưởng, ArchUnit, contract/OpenAPI và `./gradlew check`; test cạnh tranh phải dùng PostgreSQL thật, các connection/transaction độc lập và kiểm tra DB sau commit/rollback. Lưu revision, lệnh, môi trường, số test, kết quả và evidence theo `09` §10; phân biệt baseline, kết quả sau sửa và test mock/provider thật.
- Rà `rg` và build để bảo đảm không còn `JdbcTemplate`, `NamedParameterJdbcTemplate`, manual `ResultSet` trong persistence production của các phần thuộc phase, kể cả native query; kiểm tra DI, Lombok/MapStruct processors và entity validation. Rà diff, tài liệu/contract bị ảnh hưởng và bảo đảm không thay API/schema/behavior ngoài phạm vi được duyệt.
- **Nghiệm thu:** tất cả test/rule trên revision cuối đạt; không suy kết quả integration thật từ mock. Báo cáo riêng phần chưa tích hợp Transfer/LIM-05, session/account/identity/audit thật, C3 alert và timezone D2 cho đến khi có provider/quyết định và bằng chứng tương ứng.

### 11.3. Phụ thuộc và điều kiện kết thúc

- Thứ tự: **REF-01 → REF-02 → REF-03 → REF-04 → REF-05**; test và contract liên quan chạy cùng từng phần. REF-04 cần phối hợp owner `admin`; REF-05 tổng hợp sau khi các phần trước đạt.
- Việc thay persistence là refactor có rủi ro đối với money path: không kết luận giữ nguyên transaction chỉ bằng unit test, mock hoặc build pass. Nếu Spring Data JPA không tái hiện an toàn một operation PostgreSQL hiện có, dừng phần phụ thuộc, ghi rõ bằng chứng và hỏi người điều hành thay vì tự nới quy tắc JPA hay sửa invariant.
- Các kết quả E2/E3/E4 trước đây là bằng chứng của revision cũ; phase chỉ hoàn thành khi có bằng chứng mới trên code sau refactor. Các integration chưa có provider thật vẫn giữ **Blocked**, không coi phase refactor làm hoàn tất LIM-05 hay sign-off thay owner.
