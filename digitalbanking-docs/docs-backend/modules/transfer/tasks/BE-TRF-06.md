# BE-TRF-06 — Recovery, reconciliation và purge

> **Ưu tiên / dự kiến:** P0 / W4–W5  
> **Trạng thái:** Candidate đã triển khai; nghiệm thu và activation còn Blocked  
> **Owner:** Transfer (Vũ); phối hợp Account, Ledger, Limit, Audit, Ops/DBA và QA

## 1. Mục tiêu và phạm vi

Phục hồi transfer `PENDING` an toàn sau crash hoặc lỗi, đối soát incident mà không tạo money/ledger kép, và xóa idempotency record terminal hết hạn. Chạy trên primary; mỗi candidate được xử lý riêng, không giữ transaction cho cả batch. HTTP retry nonterminal luôn trả `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`; chỉ worker nội bộ được recovery (DEC-TRF-004).

Phạm vi gồm discovery, durable claim/fence, quyết định resume hoặc reconciliation, bounded retry/escalation, purge và runbook. Executor T2, Tfail và transaction money thuộc [BE-TRF-05](BE-TRF-05.md); notification delivery/telemetry wiring thuộc BE-TRF-07. Task này không tự sửa ledger, truy cập bảng private của provider, bật candidate production hay tuyên bố các task phụ thuộc đã hoàn thành.

**Hiện trạng.** `TransferRecoveryCandidate` + `TransferRecoveryStore` triển khai discovery, claim/fence bền, retry, reconciliation và purge theo schema candidate. `TransferRecoveryJobConfiguration` đăng ký tường minh job với hook startup và lịch 60 giây. [Evidence kiểm thử](evidence/BE-TRF-06-candidate.md) dùng PostgreSQL và mock provider. Runtime production chờ wiring, migration và chứng minh provider thật.

## 2. Nguồn và phụ thuộc

- [Backlog và AC](README.md) §BE-TRF-06; [thiết kế Transfer](../design.md) §§1–3, 5–7; [BE-TRF-02](BE-TRF-02.md), [BE-TRF-04](BE-TRF-04.md), [BE-TRF-05](BE-TRF-05.md).
- [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4; [[digitalbanking-docs/docs-common/09. Test plan.md]] §§7, 10; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §§6, 10; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §§8, 13.4; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-003–006, 008, 010, 013–015 và 018. DEC-TRF-014 làm rõ và ưu tiên cách hiểu hai transaction ở những đoạn cũ mô tả claim và xử lý cùng transaction.

| Phụ thuộc | Hợp đồng / điều kiện |
|---|---|
| Transfer 02/04/05 | T1 bền, schema/TTL, executor T2 tham gia transaction xử lý (`MANDATORY`), terminal bất biến; claim/fence áp dụng cả T2 trực tiếp, Tfail và recovery. Candidate chưa là proof. |
| Limit | Guard CUSTOMER; `TRFC-LI-06` evidence reservation/settlement theo transfer; settlement/release JOIN transaction, đúng mode, không tăng usage kép. |
| Account / Ledger | `TRFC-AC-06` movement evidence và `TRFC-LE-02` entry evidence per-transfer trên primary; không suy từ balance hiện tại. |
| Audit | `TRFC-AU-03` completion evidence; `TRFC-AU-01` ghi reconciliation audit fail-closed, JOIN transaction và audit phase cuối. |
| Ops / DBA / QA | Quy trình incident, rearm, lease, proof commit/rollback, migration, lock và failure injection; owner review chưa thay bằng tài liệu. |

`05` §13.4 đã mô tả baseline evidence và phương án thay thế: thiếu provider thì fake theo contract chỉ trong test/dev; production giữ thực trạng, alert/manual. Không cần thêm dependency liên module mới; DTO, mode, evidence bền và transaction propagation vẫn cần các owner thiết kế/review. Không đăng ký fake production.

## 3. Đầu việc

| ID | Kết quả / điều kiện |
|---|---|
| TRF-06.1 | Discovery startup/định kỳ có giới hạn, fairness; claim bền và fence chống worker cũ theo §4. |
| TRF-06.2 | Phân loại evidence và outcome theo §5; tái sử dụng T2 hoặc hoàn tất reconciliation atomic khi đủ proof, còn lại alert/manual. |
| TRF-06.3 | Retry/backoff và escalation bền theo DEC-TRF-010; xử lý unknown commit và rearm có kiểm soát theo §6. |
| TRF-06.4 | Purge terminal đúng TTL, lock/recheck và an toàn với retry theo §7. |
| TRF-06.5 | Test contract, PostgreSQL, crash và runbook; evidence/review theo §§8–9. Mỗi AC thiếu provider hoặc proof ghi Blocked. |

## 4. Discovery, claim và xử lý

1. Scheduler chạy khi startup và mỗi 60 giây; chọn `PENDING` có tuổi ít nhất 300 giây trên primary, thỏa next-attempt/state, dùng batch hữu hạn và cursor ổn định để tránh starvation. Discovery chỉ plain-read ID và context customer bất biến, không giữ lock; mỗi candidate tối đa một lần mỗi lượt. Không dùng scheduler đơn instance như cơ chế chống trùng.
2. **Transaction claim ngắn:** lấy guard CUSTOMER trước, sau đó `FOR UPDATE SKIP LOCKED` transfer; reread và xác minh customer/context, PENDING, điều kiện tuổi, state, lịch thử và giới hạn attempt. Lock bận thì skip, không tính attempt. Chỉ claim nếu protocol chứng minh attempt cũ không còn khả năng ghi; lease hết hạn không phải proof rollback. Ghi epoch tăng, token mới, owner, lease và attempt count/first/next/state bền; commit trước xử lý. Crash trước commit không có claim bền; crash sau commit phải được nhận diện khi chạy lại.
3. **Transaction xử lý riêng:** plain-read context nếu cần → guard CUSTOMER → lock/re-read transfer/fence; kiểm context, PENDING, epoch **và** token, state/lease hợp lệ và quyền SYSTEM. Token cũ, terminal hoặc context khác thì không ghi money/terminal. Đọc evidence trong transaction này và thực hiện nhánh §5; executor T2 tham gia transaction hiện hữu, không `REQUIRES_NEW`. Kết quả chỉ có hiệu lực sau commit caller. Khóa account theo ID tăng, usage CUSTOMER → ACCOUNT, business flush rồi audit cuối theo BE-TRF-05.
4. Mọi đường money/terminal, kể cả T2 trực tiếp và Tfail, phải kiểm fence dưới lock; CAS/epoch đơn thuần không thay row lock và proof transaction cũ. Không network/gateway dưới lock. Retry deadlock/timeout chỉ khi rollback đã xác nhận và protocol cho phép, trên transaction mới có giới hạn; lỗi commit không rõ đi §6.

Candidate dùng batch 100, cursor theo `(created_at, transfer_id)` và ngưỡng tuổi cố định trong mỗi vòng quét. Lease 120 giây kiểm tại lúc lấy lock xử lý. Attempt `CLAIMED` đã hết lease hoặc `FAILURE_PENDING` → `MANUAL` dưới guard/lock; dựa vào timeout sẽ làm mất proof của lần chạy trước. Chỉ attempt hồi phục có lỗi tranh chấp ở thân T2 và callback xác nhận rollback mới được trả về `IDLE`, với next-attempt và count bền. `claimDirect` cấp fence cho T2 ngay sau T1 khi chưa có attempt; adapter T2 dùng `lockUsableFence` dưới cùng guard và so cả epoch/token. Candidate JPA terminal chuyển `DONE` và xóa token atomically. Tfail, rearm có audit và lịch sử attempt cho Ops vẫn cần proof/wiring và review trước activation.

## 5. Quyết định theo evidence

Đọc `TRFC-AC-06`, `TRFC-LE-02`, `TRFC-LI-06`, `TRFC-AU-03` theo `05` §13.4 sau guard/fence. Phân biệt `Absent` sau read thành công, `Present` đầy đủ, partial/mismatch/duplicate và lỗi hệ thống; absence một nguồn không chứng minh rollback. Đối chiếu intent, mode/version và response; không dùng aggregate usage hoặc balance hiện tại làm proof.

| Kết quả dưới lock | Hành động / oracle |
|---|---|
| Transfer đã terminal | Bỏ qua; giữ nguyên status/response, không post lại hoặc ghi đè. |
| Không ledger, completion audit hoặc movement; outcome attempt trước và fence được chứng minh an toàn; reservation/mode hợp lệ | Resume executor T2 trong transaction xử lý; revalidate account/limit và xử lý business rejection theo BE-TRF-05. Không suy an toàn chỉ vì ledger vắng. |
| PENDING có ledger/audit: incident, toàn bộ evidence khớp | Chỉ tự hoàn tất khi đủ DEC-TRF-006: đúng hai entry seq 1 DEBIT source, seq 2 CREDIT destination, amount/currency và balanceAfter khớp movement Account; audit completion C1 đúng linkage/outcome; Limit evidence theo transfer, mode và settlement/usage hợp lệ; response gốc masked hoặc chứng minh chưa từng trả và có thể tái tạo chính xác. Không debit, credit hay post ledger lại. |
| Thiếu/lệch/trùng/partial, lỗi read, mode không rõ, response có thể đã trả nhưng mất, fence hoặc commit chưa rõ | Không tự thay business state, tiền, ledger, usage hay response; alert/manual ngay, ghi metadata điều phối an toàn khi có thể. Không chuyển lỗi kỹ thuật thành FAILED. |

Candidate chỉ resume executor hiện có khi mode yêu cầu reservation và proof xác nhận reservation PENDING chưa tính usage. Legacy chỉ repair khi có usage per-transfer đã commit và khớp ngày/version. Nhánh đủ proof atomically cập nhật `COMPLETED`, idempotency status/response và terminal TTL snapshot, settlement còn cần thiết **chỉ khi mode và evidence cho phép**, rồi audit `TRANSFER_RECONCILED` JOIN transaction ở pha cuối. Đã settled thì không increment usage lại; reservation required thiếu hoặc RELEASED là incident. Audit/config/settlement lỗi rollback toàn transaction. Không dựng response mới nếu không chứng minh replay chính xác; không sửa terminal đã tồn tại. Xử lý credit failure/Tfail chỉ theo điều kiện nghiêm ngặt DEC-TRF-003 và BE-TRF-05, không tự suy rollback.

## 6. Retry, unknown commit và vận hành

- Tối đa 5 attempt hoặc 15 phút từ lần đầu, tùy điều kiện đến trước; backoff 30/60/120/240 giây + jitter. Lưu count, first/next và state bền trước xử lý. Lỗi tạm chỉ retry khi rollback và fence đã được xác minh; lock bận không tiêu attempt. Hết giới hạn → dừng tự động, alert/manual. Thiếu evidence, unsafe fence hoặc unknown commit → manual ngay, không chờ hết retry.
- Khi connection mất quanh commit, không suy thành công hay rollback từ exception hoặc lease. Đọc primary sau khi ổn định, lấy guard/lock và kiểm terminal, fence và evidence. Nếu terminal đã commit giữ/replay; nếu không xác định được kết quả hay an toàn, giữ trạng thái thực tế và escalation. Không re-post tiền.
- Runbook cho Ops: xác minh primary/health và quyền, tìm transfer bằng reference an toàn, kiểm state/attempt/fence, idempotency, evidence qua API owner và audit; phân loại incident, ghi thời điểm, correlation và kết quả không chứa secret/PII. Phối hợp Account/Ledger/Limit/Audit/DBA khi proof thiếu hoặc lệch; không chỉnh ledger trực tiếp. Sau điều tra, chỉ người được phân quyền rearm bằng thao tác có audit và fence mới theo protocol được duyệt; nếu chưa có thao tác an toàn, tiếp tục manual. Với khách hàng, giữ key/query reference; nếu kết quả chưa rõ hoặc không có reference, liên hệ Ops trước khi tạo lệnh mới.
- Theo dõi pending age, attempt, kết quả, audit failure và backlog bằng label bounded; không dùng key/reference/account/user làm metric label hoặc ghi raw key/PII. Alert có owner, ngưỡng và đường xử lý; wiring chi tiết thuộc BE-TRF-07.

## 7. Purge idempotency

TTL mặc định/tối thiểu 24 giờ từ timestamp terminal lưu trong idempotency record, không hứa từ thời điểm response/commit. T1 snapshot TTL/version; transaction terminal dùng snapshot hợp lệ và `expires_at = max(expires_at hiện có, completed_at + TTL_terminal)` theo DEC-TRF-005/013. Missing/invalid config fail-closed, không terminalize với giá trị đoán.

Job trên primary chọn batch 100 record terminal `COMPLETED/FAILED` có transfer terminal cùng outcome và `expires_at < now()`, khóa idempotency record `FOR UPDATE SKIP LOCKED`, reread/recheck status và expiry trong transaction ngắn rồi xóa có điều kiện. Rà lock order/FK và race với retry trên PostgreSQL; không purge `RECEIVED/IN_PROGRESS` hay transfer PENDING dù quá TTL. Chỉ xóa idempotency record, không xóa transfer/ledger/audit; tôn trọng hold nếu cơ chế mở rộng được áp dụng. Record còn tồn tại, kể cả quá expiry, vẫn replay/conflict; chỉ sau purge key có thể được dùng như yêu cầu mới. Không coi purge là bảo đảm chống trùng vĩnh viễn.

## 8. Kiểm thử và evidence

| Nhóm | Thiết lập / oracle tối thiểu |
|---|---|
| Consumer contract | Fake test/dev đúng `05` §13.4: absent/partial/mismatch/duplicate/error từng provider, mode required/legacy, settled/pending/released, response an toàn/không an toàn, SYSTEM context và mapping; không side effect sai. Không chứng minh transaction thật. |
| `T-REC-01`, `T-IDEM-05` | Crash sau T1, trước/sau claim commit và trong T2; restart nhiều worker. Chỉ resume khi proof an toàn; một outcome, không debit/ledger/usage kép. Unknown → manual. |
| `T-REC-02` | Fixture PENDING bất thường có toàn bộ evidence khớp; atomic COMPLETED + response/TTL + settlement cần thiết + audit; không re-post tiền/ledger. Inject lỗi từng bước và kiểm rollback; response đã trả nhưng mất không được tái tạo. |
| `T-REC-03` | Thiếu audit và lần lượt thiếu/lệch/trùng các evidence, mode, response hoặc DB error → giữ thực trạng, alert/manual, không false completion. |
| `T-REC-04` / fence | PostgreSQL nhiều connection/barrier: hai instance, guard bận, `SKIP LOCKED`, stale epoch/token, hết lease khi worker cũ còn sống, T2 trực tiếp/Tfail/recovery cạnh tranh, crash claim và xử lý, fairness; không xử lý trùng hoặc ghi bởi stale worker. |
| Audit/commit | `T-AUD-06`, crash trước/sau commit, connection loss và audit reconciliation lỗi: kiểm primary, không false success, không giả định rollback; provider thật chứng minh JOIN và không business SQL sau audit lock. |
| TTL / purge | PENDING lâu hơn TTL; terminal TTL 24h/48h đổi cả hai chiều và invalid; boundary expiry, terminal 201/422, purge/retry concurrent, lock/recheck, record còn replay/conflict và key sau purge là mới. Assert transfer/ledger/audit không bị xóa. |
| Retry / Ops | Count/first/next bền qua restart, backoff/jitter, 5 lần/15 phút, lock bận không tính, manual ngay khi unknown, rearm có audit và quyền, batch không starvation; runbook diễn tập với Ops. |

Test invariant cần PostgreSQL thật, migration và provider thật, nhiều connection và failure injection; fake/fixture chỉ chứng minh consumer. Assert cả balance, movement, ledger, reservation/usage, transfer, idempotency/response, audit và outbox phù hợp. Lưu test ID, revision, lệnh, môi trường/migration, kết quả, artifact synthetic không PII/secret, người chạy và reviewer theo `09` §10; không gọi evidence candidate là end-to-end.

## 9. Blocker và điều kiện đóng

| Owner | Cần hoàn tất trước activation/nghiệm thu |
|---|---|
| Transfer / DBA / Ops | Production migration, durable claim/state machine, lease, evidence rollback/unknown commit, rearm/history, lock order và proof stale worker; quyết định chưa chốt theo DEC-TRF-013. |
| Account / Ledger / Limit / Audit | Provider-owned evidence API, schema/migration và mode/version; phân biệt absent/error, transaction/locking và evidence per-transfer; review vectors, response reconstruction và audit. |
| Transfer / BE-TRF-05 | Executor JOIN, direct T2/Tfail fencing, terminal response/TTL và rollback proof; không kích hoạt đường tiền khi thiếu. |
| Ops / QA / Security | Runbook, quyền/audit rearm, alert, crash/failover, PII và evidence T-REC-01–04, purge/race trên môi trường thật. |

**Điều kiện đóng:** toàn bộ AC [backlog](README.md) được Verified với provider và migration thật, PostgreSQL nhiều connection/failure injection, proof transaction/fencing và review owner/QA/Ops. Những mục chưa đủ bằng chứng giữ **Blocked**; candidate và evidence synthetic chỉ hỗ trợ kiểm chứng trong phạm vi Transfer. Sign-off cần provider và môi trường thật.

## 10. Runbook candidate cho Ops

1. Kiểm tra alert thuộc môi trường nào, kết nối primary, tình trạng DB và quyền xem incident. Dùng transfer ID nội bộ trên kênh được phân quyền. Ghi thời điểm và correlation đã được phép chia sẻ. Giữ raw idempotency key, token claim, response và dữ liệu khách hàng ngoài log hoặc ticket.
2. Đọc trạng thái transfer và idempotency trên primary. Kiểm tra attempt state/epoch, lease, count và lịch retry. Với lỗi mất ACK commit, xác nhận terminal nếu đã có, sau đó giữ response gốc để replay. Khi row hoặc guard còn bận, chuyển điều tra cho DBA/Ops và đọc lại sau khi primary ổn định.
3. Với PENDING/MANUAL, đọc evidence qua API owner Account, Ledger, Limit và Audit. Đối chiếu correlation, cặp entry, movement, reservation/usage đúng ngày/version và nguồn response được chứng thực. Ghi nguyên nhân thiếu/lệch và owner xử lý. Giữ trạng thái tiền/ledger để điều tra.
4. Theo dõi hàng đợi MANUAL trên primary để phát hiện incident kể cả khi gửi alert lỗi. Phân loại alert `CLAIM_UNCERTAIN`, `FENCE_UNSAFE`, `EVIDENCE_UNSAFE`, `OUTCOME_UNCERTAIN` và `RETRY_EXHAUSTED` theo từng incident. Chuyển cấp khi thiếu proof hoặc primary chưa ổn định. Giữ MANUAL cho tới khi có thao tác rearm phân quyền, ghi audit và cấp fence mới theo protocol được review. Khách hàng dùng key cũ hoặc tra reference khi kết quả chưa rõ.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 17/11/2026 | Chi tiết recovery hai transaction, evidence, retry, purge, runbook, test và blocker; chưa triển khai hoặc nghiệm thu. |
| v1.1 | 17/11/2026 | Cập nhật implementation candidate, proof, runbook và phạm vi nghiệm thu. |
