# Đối chiếu contract và bàn giao quyết định FE–BE

| Thuộc tính | Giá trị |
|---|---|
| Mã / ngày | FE-CONTRACT-HANDOFF / 17/11/2026 |
| Trạng thái | Draft — tài liệu FE để phối hợp review, chưa có xác nhận mới từ BE/FE |
| Phạm vi | Replay, retention/TTL, correlation, ownership; liên hệ với mục tiêu session v2 đã duyệt phía FE |
| Evidence | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-01.md]] |

## 1. Kết luận rà soát và cách dùng

Đã có quyết định thiết kế cho replay, TTL và ownership trên các nhánh Transfer/rework. Không cần yêu cầu chọn lại ba vấn đề này từ đầu. Tuy nhiên main/dev, nhánh Transfer và FE đang giữ các phiên bản nguồn khác nhau; cần xác nhận contract giao FE, đồng bộ đúng version và review/test trước phần triển khai phụ thuộc. Tài liệu này ghi nhận quyết định đã tồn tại trong repo, không ký thay owner hoặc tự đưa chúng thành baseline FE/mock v2.

Main d1efbc6 vẫn chỉ có HTTP v1; 07 đã yêu cầu replay nguyên status/body nhưng YAML còn 200. Dev 0371bfc đã có thêm implementation backend và reports mới, nhưng bản YAML vẫn còn 200; không lấy báo cáo source thay kiểm API thật. Dev2 bb6f23f và rework 159e08f có DECISIONS, nguồn 03/04/07/05 được cập nhật và YAML loại 200 replay. Nhánh identity-pr da4a4ba cũng có DECISIONS nhưng YAML lại vẫn có 200: không trộn tài liệu/code giữa các nhánh để tuyên bố mọi thứ đã đồng bộ.

Các nhánh BE đã rà đều chưa có OpenAPI HTTP v2. “Provider API/DTO v1” trong DEC-TRF-012/015 là version hợp đồng Java nội bộ, không phải xác nhận BE sẽ hỗ trợ HTTP /api/v2. FE/mock v2 hiện tại vẫn theo quyết định người phụ trách FE ngày 14/11/2026.

## 2. Nguồn đã ghim

| Nguồn | Nội dung cần đọc / trạng thái |
|---|---|
| [Main 07](https://github.com/TNoLisme/cloude/blob/d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49/digitalbanking-docs/docs-common/07.%20API%20contracts.md) | §3.6: ownership/403/404; §5.4 và §8.3: replay nguyên status/body; TTL tại §3.7/11 còn Đề xuất |
| [Main Modules contract](https://github.com/TNoLisme/cloude/blob/d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49/digitalbanking-docs/docs-common/design/05.%20Modules%20contract.md) | MOD-OI-04/08 còn mở tại snapshot main |
| [Dev2 DECISIONS](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/DECISIONS.md) | DEC-TRF-003 ghi ủy quyền người điều hành; 004/005/012 ghi lựa chọn thiết kế, chưa thay provider/QA sign-off |
| [Dev2 07](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/digitalbanking-docs/docs-common/07.%20API%20contracts.md), [SRS](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/digitalbanking-docs/docs-common/03.%20SRS.md), [DB](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/digitalbanking-docs/docs-common/04.%20Database%20design%20%26%20analysis.md) | Đã đồng bộ semantics replay/correlation/retention theo các DEC; không coi đây là proof runtime |
| [Dev2 OpenAPI](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/digitalbanking-docs/openapi/digital-banking-v1.yaml) | POST /transfers bỏ 200; 201/422 là terminal replay. 202 vẫn được khai báo, cần xác nhận tính hỗ trợ ở môi trường tích hợp |
| [Handoff Transfer](https://github.com/TNoLisme/cloude/blob/bb6f23f33ccdad3fedd13582c46882ed1ed85c07/digitalbanking-docs/docs-backend/modules/transfer/tasks/evidence/BE-TRF-01-handoff.md) | Baseline thiết kế đã chọn theo ủy quyền; AC/provider/consumer review và integration còn Blocked |
| [Rework DECISIONS](https://github.com/TNoLisme/cloude/blob/159e08fb4ab85cf67f14cc97e4dd1299adb37a70/DECISIONS.md) | Giữ DEC-TRF-004/005/012; DEC-TRF-016 ghi staff scope server-side còn Blocked |

## 3. Những gì đã có quyết định

| ID | Nội dung trong nguồn | Áp dụng vào FE sau khi contract giao FE được xác nhận | Giới hạn |
|---|---|---|---|
| C-01 Replay terminal | DEC-TRF-004; cùng actor/key/hash, terminal trả nguyên status/body đã lưu 201 hoặc 422; Idempotent-Replayed: true | Client giữ HTTP status, đọc replay header trên cả success và Problem 422; không nhận 422 nào cũng là replay | Main 07 đã có rule này, YAML/feature FE cũ còn 200; cần đồng bộ snapshot máy đọc đúng version |
| C-02 Nonterminal/conflict | DEC-TRF-004; chưa terminal trả 409 IDEMPOTENCY_IN_PROGRESS + Retry-After; HTTP retry không resume T2. Khác actor/hash conflict, không lộ response | Giữ cùng intent/key/body, chặn gửi lặp, phân biệt IN_PROGRESS và CONFLICT; theo dõi bằng reference nếu có | Recovery thật do BE; 5xx/timeout không tự suy ra FAILED. Retry/poll budgets FE còn phải theo nguồn được chốt |
| C-03 TTL | DEC-TRF-005; mặc định/tối thiểu 24h từ completed_at terminal đã lưu trong DB; không purge PENDING. Record terminal còn tồn tại vẫn replay/conflict dù timestamp đã qua; chỉ sau purge key có thể thành lệnh mới | Không tính 24h từ lúc bấm gửi hoặc nhận response, không dùng hết giờ để cho phép tự tạo lại. Khi unknown: query reference hoặc nhờ vận hành xác minh trước gửi mới | Không biết chính xác mốc nhận/purge ở browser; chưa có căn cứ để thêm countdown hoặc expiry field mới. Review/migration/purge/concurrency evidence vẫn mở |
| C-04 Correlation | DEC-TRF-004; replay body giữ correlation gốc, header X-Correlation-ID theo request hiện tại | Model tách trace của response gốc và lần request hiện tại; hỗ trợ truy vết mà không ghi token/raw key/PII | Không ghi đè body đã lưu để ép header/body bằng nhau; cần contract test cả 201 và 422 |
| C-05 Ownership customer | Main 07 §3.6 và DEC-TRF-012; CUSTOMER cần TRANSACTION_READ_SELF và sở hữu source hoặc destination | UI chỉ hiển thị dữ liệu server cho phép; kiểm 403 tồn tại nhưng ngoài scope, 404 không tồn tại; không lộ response actor khác khi key conflict | BE enforce; FE cache/user isolation không thay kiểm quyền server. 403/404 phân biệt là lựa chọn nguồn, không tự chuyển thành 404 tất cả |
| C-06 Ownership staff/fraud | DEC-TRF-012; staff cần TRANSACTION_READ và scope server-side; fraudFlags cần FRAUD_READ hoặc FRAUD_REVIEW | Route/action/masked projection theo matrix và scope được BE trả/xác nhận | Policy quyền cơ bản đã ghi; cơ chế staff scope và mapping capability cho FE chưa đủ/đã ghi Blocked trong DEC-TRF-016. Không khóa toàn bộ customer flow vì phần staff còn thiếu |

Không lấy các ngưỡng khác xuất hiện cùng DECISIONS làm quyết định FE mới ngoài phạm vi audit. Các GAP còn lại vẫn theo tracker.

### 3.1. Các quyết định thiết kế khác trên dev2

Theo câu hỏi bổ sung ngày 17/11, đã đọc mở rộng DECISIONS dev2@bb6f23f. Có 16 bản ghi DEC-TRF-001..016; ngoài 004/005/012 đã đối chiếu, các nhóm dưới đây đã có lựa chọn thiết kế theo ủy quyền trong nguồn. Bảng chỉ mở rộng thông tin, không tự nhận các ngưỡng/semantics mới vào FE/mock v2 hoặc ký review cho provider/QA.

| Nhóm / DEC | Lựa chọn được ghi trong dev2 | Liên hệ FE |
|---|---|---|
| Step-up · 001/002/008 | Default ≥10 triệu VND/lệnh hoặc tổng dự kiến >20 triệu/ngày. Tổng theo customer gồm COMPLETED ngày hiện tại + mọi reservation PENDING còn hiệu lực kể cả qua ngày + lệnh mới, mỗi intent một lần. Guard/reservation và business zone Asia/Ho_Chi_Minh cấu hình | FE dùng challenge/428 authoritative, không thay kiểm tra server bằng phép cộng từ danh sách đang hiển thị; boundary/race vectors cần contract version giao FE |
| Validation/failure · 003 | Phát hiện lỗi amount/inactive/thiếu tiền trước T1 thì không tạo transfer. Điều kiện đổi sau T1 có thể FAILED khi đã chứng minh rollback/an toàn; unknown commit thiếu proof giữ thực trạng và tra soát | Tách validation/preflight, FAILED có reference và kết quả chưa rõ. Không coi timeout là bằng chứng FAILED |
| Recovery · 006/010/014 | Chỉ hoàn tất PENDING khi đủ evidence account/ledger/limit/audit và response; không post tiền lại. Claim bền rồi transaction xử lý có fencing. Job 60s, xét PENDING ≥300s; tối đa 5 attempts hoặc 15 phút từ lần đầu, thiếu evidence/unsafe thì alert/manual | Các giá trị job là policy BE, không phải poll/retry budget FE. UI/query theo response thật; recovery/reload UX vẫn chờ BE input và người phụ trách FE |
| Audit · 007 | Business flush xong mới ghi audit C2 theo flag ID, rồi C1 cuối T2; audit bắt buộc lỗi thì rollback T2; fraud fail-open không nuốt lỗi audit | Server quyết định outcome; FE xử lý Problem/Unknown theo contract, không báo thành công từ thao tác gửi request |
| Limit/fraud/ledger · 009 | Snapshot/version cấu hình dùng trong T2; rule velocity flag khi >5 intent có T1 commit của cùng customer trong cửa sổ 600s, không đếm replay/428 như intent mới; rule này không tự block; invariant ledger cần kiểm trước audit | FE đọc limit/status authoritative; thông tin fraud theo permission/masking, không tự suy hoặc tự block bằng rule client |
| Notification/OTP · 011 | Outbox cùng T2, delivery sau commit theo at-least-once, lỗi gửi không đổi transfer. OTP chỉ verify khi SENT và ACK đúng generation đã persist; trả challenge sau ACK, dispatch lỗi/unknown trả 503 OTP_DISPATCH_FAILED; resend xoay generation | Không đồng nhất thông báo lỗi với chuyển tiền thất bại. Challenge/resend phải theo state/response server; chưa triển khai handler ngoài contract FE đã duyệt |
| Schema/module contract · 013/015 | Nơi lưu snapshot TTL, metadata fencing và baseline provider-owned DTO v1, nullability/outcome/transaction; consumer có thể phát triển bằng fake test-only, ghép provider thật sau review | Version DTO Java nội bộ không phải quyết định HTTP v2; FE không gọi port nội bộ. Readiness không được chứng minh bằng fake |
| HTTP boundary candidate · 016 | Boundary POST/GET /api/v1/transfers chỉ kích hoạt tường minh khi orchestrator/provider/migration được review sẵn sàng. Validation qua common Problem; GET source-or-destination; staff scope, PII và integrations vẫn Blocked | Có thiết kế boundary để đối chiếu mẫu lỗi/read API, nhưng không được coi route production đã sẵn sàng chỉ vì có candidate code/docs |

Các quyết định phát triển độc lập không thay điều kiện nghiệm thu integration. Q-01/02/03/04/05 và FE-01..06 vẫn cần phản hồi/chốt đúng phần như mục 4; không mở thêm câu hỏi chọn lại các policy đã ghi khi chưa có bằng chứng mâu thuẫn mới.

## 4. Phiếu để người phụ trách FE mang chốt với BE

Theo yêu cầu người phụ trách FE ngày 17/11/2026: gửi phần BE trước, các lựa chọn phía FE chờ người phụ trách FE chốt sau phản hồi. Các câu hỏi là xác nhận áp dụng/review và đầu vào còn thiếu, không yêu cầu chọn lại C-01..06. Một phần câu trả lời đã có trong docs-common như bảng 4.0; xác nhận bản giao FE và các phần thiếu vẫn **Chờ trả lời/chốt**. Chưa có phản hồi trực tiếp mới từ BE hoặc phê duyệt FE cho các lựa chọn còn mở.

### 4.0. Phần đã có câu trả lời trong common

Đọc đúng snapshot ở mục 2: docs-common trên main và dev2/rework chưa đồng bộ hoàn toàn. “Đã có câu trả lời trong tài liệu” không tự đồng nghĩa API đã chạy hoặc BE đã phản hồi buổi chốt.

| Câu hỏi | Docs-common đã trả lời gì | Phần còn hỏi BE |
|---|---|---|
| Q-01 Contract giao FE | 07 là nguồn semantics, YAML phải đồng bộ. Main 07 đã yêu cầu replay nguyên status/body; dev2/rework đồng bộ DEC-TRF-004/005/012 và YAML bỏ 200 replay | SHA/PR/bản contract chính thức giao FE và version áp dụng; không hỏi lại từ đầu có chọn replay 201/422 hay không |
| Q-02 HTTP version/session | 07/OpenAPI BE đang mô tả HTTP v1, DB_SESSION; breaking change cần version mới. Security có SESSION_TOKEN/LOGIN_MFA; FE đã chọn v2 riêng | BE/Security/DB xác nhận nhận triển khai HTTP v2, route/cookie/purpose/coexistence và readiness. Contract Java provider v1 không trả lời việc HTTP v2 |
| Q-03 Staff scope/capabilities | Main 07 §3.5–3.6 có quyền/ownership/403/404/masking; DEC-TRF-012 làm rõ customer sở hữu source hoặc destination, staff TRANSACTION_READ + scope; fraudFlags cần FRAUD_READ/REVIEW | Cách thực thi staff scope và matrix/metadata mà client dùng trong bản giao FE; DEC-TRF-016 rework vẫn ghi cơ chế staff scope Blocked. Không hỏi lại quyền customer cơ bản |
| Q-04 Recovery/retention | Main 07 có GET /transfers/{reference} và GET /transactions với filter/page. Dev2 03/04/07 theo DEC-TRF-005 đã ghi TTL tối thiểu 24h từ terminal DB, PENDING không purge; record còn thì replay/conflict, sau purge có thể thành lệnh mới; query reference/tra soát trước gửi lại | Xác minh khi mất reference/key, dữ kiện tra soát Ops thực có và policy lưu recovery ở browser. OpenAPI đã rà chưa có lookup transfer bằng idempotency key; không mặc định request này được hỗ trợ |
| Q-05 Readiness/vector | 07/YAML có status/header/schema và ví dụ; Modules contract/BE-TRF-01 có contract vector/kế hoạch kiểm chứng. 202 ghi Đề xuất async, MVP synchronous có thể không dùng | Hỗ trợ 202 thực tế, URL/seed/API sẵn sàng ở đúng FE/BE SHA, vector đã được owner review và evidence API/browser. Mẫu docs không thay môi trường tích hợp |

Correlation đã được trả lời trong 07 dev2 và DEC-TRF-004: body replay giữ correlation gốc, header theo request hiện tại. Không cần hỏi BE chọn lại semantics này; cần xác nhận nó nằm trong version giao FE và kiểm response thật. Báo cáo module common trên main chỉ chứng minh nền tảng error/context/security theo phạm vi báo cáo; không đóng các câu hỏi về lifecycle session, recovery money path hoặc readiness nghiệp vụ.

### 4.1. Gửi BE trước — Q-01..05

BE/API là đầu mối trả lời và phối hợp Transfer, Security, DB, QA, Product/Ops khi câu hỏi thuộc trách nhiệm các bên đó. BE không ký thay owner nghiệp vụ/bảo mật.

| ID | Câu hỏi cụ thể gửi BE / bên phối hợp | Đầu ra phải ghi sau khi chốt | Chặn phần nào |
|---|---|---|---|
| Q-01 Contract giao FE | BE/API xác nhận nhánh/SHA chứa 07/YAML nào là nguồn để FE phát triển? Có chấp nhận C-01..06 theo DEC-TRF-004/005/012 làm baseline giao FE không? Các khác biệt giữa main/dev/dev2/rework/identity-pr sẽ được đồng bộ ở PR nào? | SHA semantics + YAML, DEC IDs, reviewer API/Transfer/FE/QA và PR đồng bộ; không chỉ trả lời tên branch thay đổi được | Đổi DTO, handler transfer và contract tests theo bản mới |
| Q-02 HTTP version/session | FE đã chốt v2 riêng SESSION_TOKEN/Path=/api/LOGIN_MFA. BE/Security/DB có nhận triển khai HTTP v2 và áp semantics Transfer đã chọn vào version này không? Chốt routing, DB purpose, hai cookie/logout và môi trường readiness theo V2-01..05. | Contract giao tiếp của đúng version, người nhận việc/reviewer và bằng chứng readiness. Nếu đề nghị FE dùng v1 thì trình người phụ trách FE đổi quyết định rõ ràng trước code phụ thuộc | Client/UI tích hợp thật; không tự chuyển FE sang v1 hoặc tự đổi v1 trên main |
| Q-03 Staff scope/capabilities | TRANSACTION_READ được giới hạn bởi scope gì, với endpoint/filter nào? FE xác định capability từ field/schema hoặc matrix nào trong session/user DTO hiện hữu? Chốt 403/404/masking và fraudFlags cho từng actor | Bảng actor × endpoint/action × scope × error × masked fields; nếu đổi DTO phải cập nhật semantics/YAML trước sinh types | Menu/action staff, history/read chéo và integration GAP-07; customer scope rõ có thể triển khai riêng |
| Q-04 Recovery/retention thực có | BE có API và dữ kiện tối thiểu nào để tra soát khi có reference, khi chỉ còn key, hoặc khi reload mất cả hai? API history có thể hỗ trợ xác minh đến mức nào, có giới hạn gì? Theo DEC-TRF-005, cách xử lý khi record còn tồn tại/đã purge và quy trình tra soát Ops nào được hỗ trợ? Có policy Security về dữ liệu recovery được phép lưu ở browser không? | Method/path/schema/quyền và ví dụ cho các khả năng thực có; trường hợp chưa hỗ trợ ghi rõ. Hướng dẫn server/Ops, giới hạn retention và policy lưu trữ do owner xác nhận. Không yêu cầu BE chọn bố cục, nút hoặc nội dung thông báo | Đầu vào cho FE-02/03/04; không tự bổ sung lookup endpoint hay giả định key luôn tra lại được |
| Q-05 Readiness/vector | 202 async có được hỗ trợ trong môi trường đầu tiên không? BE/QA cung cấp các vector/API fixture đúng schema cho 201/422 replay, 409 in-progress/conflict, Retry-After, hai correlation và 403/404; bộ nào đã có runtime/evidence? | FE/BE SHA, URL test an toàn/seed tổng hợp, method/status/header/body mẫu, giới hạn và review; docs-only/fake ghi đúng mức | API/browser gate và tuyên bố hỗ trợ nhánh async; không chặn unit của phần độc lập đã rõ |

Không có thao tác gửi tài liệu cho BE trong lần này; người dùng mang tài liệu để chốt theo yêu cầu chat. Chưa ghi nhận câu trả lời hoặc phê duyệt của owner.

### 4.2. Để người phụ trách FE chốt sau phản hồi BE

Các lựa chọn sau là UX/cách tổ chức client trong giới hạn contract và policy đã được xác nhận. Không dùng quyết định giao diện để đổi quyền, retention hoặc semantics của server. Các phần FE đã duyệt từ trước không cần chọn lại, trừ khi BE đề nghị một thay đổi tác động chúng.

| ID | Người phụ trách FE sẽ chốt | Phản hồi BE cần trước | Giới hạn / ai còn cần xác nhận |
|---|---|---|---|
| FE-01 Phạm vi/version triển khai | Thứ tự màn/luồng tích hợp, nếu BE đề nghị thay mục tiêu v2 thì người phụ trách FE chấp nhận hay giữ lựa chọn đã có | Q-01/02/05 | Mục tiêu FE/mock v2 đã được duyệt, không tự đổi sang v1 chỉ vì BE hiện dùng v1; thay contract/security cần owner tương ứng |
| FE-02 UX Unknown/reload | Nội dung thông báo, nút kiểm tra lại, cách dẫn history/tra soát, trường hợp cần người dùng xác minh trước khi tạo lệnh mới | Q-04/05 | Không trình bày timeout là FAILED hoặc gợi ý gửi mới như thể đã chắc chưa chuyển tiền |
| FE-03 Khôi phục intent ở client | Có cần trải nghiệm phục hồi sau reload không; nếu có thì cơ chế/field/lifetime đề xuất nào đáp ứng policy và API thực có | Q-04, cùng Q-02 về session | Hiện chưa chốt persist key/payload (GAP-09). FE không tự cho phép lưu dữ liệu nhạy cảm; Security/BE review thiết kế recovery trước implementation |
| FE-04 Retry/poll trên UI | Cho thử lại thủ công/tự động trong điều kiện được phép, số lần/thời gian chờ, thông báo và action khi vượt budget | Q-01/04/05 | Phải tôn trọng Retry-After, giữ same intent/key/body, không dùng clock FE suy ra purge/retention; các budget trước đây là Đề xuất |
| FE-05 Hiển thị trace/lỗi | Cách trình bày mã giao dịch, correlation gốc/lần gọi hiện tại và hướng dẫn hỗ trợ | Q-01/04/05 | Semantics hai correlation đã có quyết định; FE chỉ chốt cách hiển thị, không sửa body replay hoặc log PII/token/raw key |
| FE-06 Menu/action/trạng thái | Ẩn hay vô hiệu action kèm lý do, cách hiển thị forbidden/not-found và thông tin masked theo từng màn | Q-03, Q-02 về metadata phiên | Quyền và scope là policy server; FE chọn cách thể hiện trong quyền đã xác nhận, không cấp thêm quyền |

Trình tự: người dùng lấy câu trả lời Q-01..05 từ BE → đối chiếu nguồn/schema/evidence → trình người phụ trách FE đúng các mục FE còn cần quyết định → ghi lựa chọn/ngày/phạm vi → triển khai task phụ thuộc và kiểm tra theo FE-RUN. Chưa dùng thời gian chờ như một phê duyệt.

## 5. Lộ trình thực hiện theo các quyết định đã tìm thấy

Đây là phân rã của roadmap hiện hành, không tạo quyết định nghiệp vụ mới hoặc thay thứ tự/gate đã duyệt. Mỗi bước mở phiếu FE-RUN, cập nhật code cùng feature/design/guide/evidence, chạy checks thật, rồi chờ duyệt task con. UI/mock đạt không đóng task cha/integration.

| Bước / task hiện có | Đầu ra triển khai | Điều kiện vào | Kiểm chứng / gate |
|---|---|---|---|
| 0 · FE-FND-02 contract alignment | Chốt Q-01/02; đối chiếu DEC với 07/YAML của version đích. Sau khi xác nhận, cập nhật semantics/versioned YAML, generated types, mô tả mock, test snapshot/delta và FE-03 đang mô tả 200 replay | Người phụ trách FE và owner nguồn xác nhận baseline/version; quyền sửa canonical nguồn phải rõ | Parse/generation check, diff chỉ gồm delta đã duyệt; không tự làm mới YAML v1 đã cam kết giữ nguyên |
| 1 · FE-FND-02 shared client/session | Transport typed, credentials/CSRF, Problem Details; giữ status/header/body riêng, 204; session metadata memory theo v2 FE/mock đã duyệt | Phần generic và login/session/logout mock đã rõ được làm trước bước 0; semantics Transfer mới chỉ đưa vào sau bước 0 | Unit/component và các gate lint/typecheck/API/format/test/build; không auto-retry mutation hoặc tự đặt budget/policy đang mở |
| 2 · FE-03-01/02 intent/replay | UUID/CSPRNG key cho một intent, same key/body khi retry; 428 verify rồi retry theo GAP-08 đã chốt; model 201 COMPLETED, 422 FAILED có reference, 409 IN_PROGRESS/CONFLICT và Unknown | Bước 0/1, contract challenge/binding và recipient đủ căn cứ. Không lấy DEC nội bộ để tự giải quyết GAP-02/08 | FET-04–06: double submit, replay success/failed, conflict cross-actor, in-progress và transport unknown. Replay 422 không thành toast success, 5xx không thành FAILED giả |
| 3 · FE-03-02/FE-04-01 retention/trace/query | Query reference, history/refetch; tách header/body correlation; UX unknown/reload sau Q-04 và FE-02..05, không dùng clock browser thay timestamp DB | C-03/04 được nhận vào version đích; BE trả Q-04, người phụ trách FE chốt các lựa chọn liên quan; 202 chỉ mở khi Q-05 xác nhận | FET-06–08: một logical transfer, vẫn query trước gửi mới; trace cũ/mới; long PENDING không tự hết hạn. BE cung cấp proof purge/replay; mock không chứng minh retention thật |
| 4 · FE-FND-03/FE-REL-01 + feature reads | Customer ownership trước; mapping staff menu/action/history/fraud theo Q-03 và quyết định thể hiện FE-06; cache theo user/scope và invalidation | Customer phần rõ độc lập; staff đợi matrix/scope, session đúng version và quyết định UI liên quan | FET-03/08/10/13: source/destination participant, cross-customer deny, 403/404, masking, direct URL, cross-user cache; không lọc local để giả quyền server |
| 5 · FE-REL/API từng phần | Tích hợp phần UI/mock đã được duyệt khi API đúng version sẵn sàng, rồi browser critical flows và handover | Q-05 readiness, Q-02 runtime, owner reviews và các GAP liên quan đã giải quyết | FET liên quan + BE evidence idempotency/ledger/purge/ownership; ghi FE/BE SHA/contract/seed/URL an toàn. Merge theo xác nhận mốc cụ thể, không tự push/merge từ việc lập lộ trình |

## 6. Cách ghi kết quả buổi chốt

| ID câu hỏi | Trả lời cụ thể / lựa chọn | Người quyết định, vai trò | Ngày, nguồn chat/PR | SHA semantics/YAML / evidence | Phần được phép bắt đầu |
|---|---|---|---|---|---|
| Q-01..05 | Chưa có | Chưa có | Chưa có | Chưa có | Chỉ phần độc lập đã được phép ở bước 1 |

Sau phản hồi BE mới ghi lựa chọn FE:

| ID FE | Input Q/SHA đã nhận | Quyết định người phụ trách FE / phạm vi | Người/ngày/nguồn | Task được mở / phần còn chặn |
|---|---|---|---|---|
| FE-01..06 | Chưa có | Chưa chốt | Chưa có | Các phần phụ thuộc tiếp tục chờ |

Khi có xác nhận, cập nhật ADR-002/GAP-05 cho session/version, GAP-07 cho staff scope, GAP-08/09 cho retry/recovery và FE-03/standards liên quan trong cùng thay đổi. Không đánh dấu toàn bộ ownership/replay/TTL là thiếu quyết định; tách rõ thiết kế đã ghi, bản contract giao FE, implementation và evidence nghiệm thu.
