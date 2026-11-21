# Rà soát GAP FE trên `main` và `dev2` — 21/11/2026

| Mục | Giá trị |
|---|---|
| Yêu cầu | Người phụ trách FE yêu cầu rà lại hai nhánh và ghi câu hỏi còn thiếu để chốt, chat ngày 21/11/2026 |
| Snapshot đã fetch | `origin/main@65bd72f`, `origin/dev2@740ab5f`; FE local nền `frontend@9e578f2` |
| Trạng thái | Tài liệu đối chiếu chờ người phụ trách FE và owner nguồn xác nhận; không tự đóng GAP |
| Phiếu lần chạy | [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-GAP-REVIEW/2026-11-21-9e578f2/run-01.md]] |

## Cách đọc kết quả

`Có câu trả lời` nghĩa là nguồn đã ghi rõ một quyết định/hành vi trong phạm vi nêu ra. `Một phần` nghĩa là có quyết định hoặc BE đã kiểm chứng một lát nhưng vẫn thiếu contract, xác nhận owner hoặc môi trường cần cho FE. `Chưa có` nghĩa là không thấy quyết định cho câu hỏi chính trên hai SHA này. Triển khai BE v1 không tự biến thành contract/runtime v2; test module không tự chứng minh browser flow. `07` §11.4 tại `dev2` vẫn ghi Backend/Frontend/QA/Security/Audit review là “Chưa”. Các GAP trong tracker giữ mở tới khi nguồn sở hữu được đồng bộ và phần FE liên quan kiểm chứng.

Nguồn chính: [quyết định dev2](https://github.com/TNoLisme/cloude/blob/740ab5f/DECISIONS.md), [API Contracts 07 dev2](https://github.com/TNoLisme/cloude/blob/740ab5f/digitalbanking-docs/docs-common/07.%20API%20contracts.md), [OpenAPI v1 dev2](https://github.com/TNoLisme/cloude/blob/740ab5f/digitalbanking-docs/openapi/digital-banking-v1.yaml), [[digitalbanking-docs/docs-common/03. SRS.md]], [[digitalbanking-docs/docs-common/design/04. security.md]], [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]]. `main@65bd72f` chưa có thay đổi BE muộn hơn `dev2@740ab5f` cho các câu hỏi này; không suy diễn từ việc nhánh khác nhau.

## Kết quả theo GAP

| GAP | Đã tìm được trên main/dev2 | Còn phải chốt để FE thực hiện đầy đủ | Owner cần trả lời | Mức |
|---|---|---|---|---|
| 01 | FE đã chốt giữ register/recovery/PIN/create customer/seed/block/unblock trong đợt đầu. `07` §1.2 và v1 YAML vẫn không định nghĩa các API đó. | BA/BE cung cấp phạm vi, API, trạng thái/lỗi/AC và version; FE không mở thao tác mock nghiệp vụ khi thiếu contract. | BA + BE | Chưa có contract |
| 02 | `TransferCreateRequest` có destination account number; `OI-API-02` vẫn mở. | Có masked lookup hay chỉ nhập số rồi BE xác nhận lúc tạo? Nếu lookup, chốt route, dữ liệu được lộ, lỗi và quyền. | BA + BE + Security | Chưa có quyết định lookup |
| 03 | FE đã chọn tìm customer bằng số điện thoại. `OI-API-08/18` và v1 YAML chưa có search customer/user; exact-ID staff đã có. | Contract phone search, scope single-bank, masking/PII, phân trang/lỗi và cách lấy `targetUserId` cho role admin. | BA + BE + Security | Chưa có API; xem [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]] |
| 04 | `DEC-ID-010` chọn Yubico, UV required, RP/origin rõ, COSE key/signCount/nonce và verification FIDO v1; BE-ID-IMP-10 báo 666 tests đạt. | Enrollment/provision trusted credential, RP/origin triển khai, challenge method/fallback (`OI-API-13/17`) và browser end-to-end. BE-ID-IMP-10 vẫn PARTIAL; passcode positive chưa xong. | BE Identity + Security | Một phần |
| 05 | BE bàn giao v1.1.0: `DB_SESSION`/`LOGIN_NEW_DEVICE`; FE đã chốt bản v2 FE/mock riêng `SESSION_TOKEN`, Path `/api`, `LOGIN_MFA`. `DEC-INT-020` không bàn giao runtime `/api/v2`. | BE/Security/DB xác nhận v2 tồn tại, cách song hành/migration v1, cookie/CSRF, bootstrap/reload/logout và test runtime. | BE + Security + DB | Một phần: v1 có, v2 chưa |
| 06 | Common 05 ghi pnpm/Vite 7/RHF+Zod; FE đã chọn Ant Design. Common vẫn đề xuất Tailwind/shadcn hoặc MUI, ADR-001 chưa được duyệt liên vai trò. | Chốt ngoại lệ/đồng bộ UI kit trong common và runtime Node/package/lockfile/CI cho task cha. | FE + kiến trúc | Một phần |
| 07 | `DEC-INT-008`, BE-INT-03 và `07` §3.4–3.6 đã xác định lát exact-ID single-bank: OPERATIONS/ADMIN có `CUSTOMER_READ` + `ACCOUNT_READ`; AUDITOR có `ACCOUNT_READ` nhưng không `CUSTOMER_READ`. `DEC-INT-020` bổ sung `FRAUD_READ` cho AUDITOR. | Đồng bộ SRS §4.2/Security §5.1 đang mâu thuẫn về Auditor; chốt ma trận role→permission→endpoint/action, nguồn capability khi quyền đổi, nối staff với phiên và test v2/API thật. Xem GAP-07-A..D trong tracker. | BE + BA + Security, rồi FE | Một phần |
| 08 | `07` §5.6 và §8.8 đã chốt Admin `Idempotency-Key` bắt buộc, scope actor/operation/resource, hash và replay/TTL; `OI-API-19` ghi đã xử lý. §8.8 nêu maker step-up bind key + proposal hash, checker bind approvalId + decision hash. Transfer binding và retry có BE-INT-08/09 verification. | Đồng bộ version/YAML FE đang dùng, mẫu 428/header/payload và contract test FE–BE cho transfer + Admin; xác nhận các xử lý `IN_PROGRESS`/conflict/replay theo runtime bàn giao. Không coi mock là bằng chứng. | BE + Security + FE | Câu trả lời BE phần lớn đã có; FE handoff còn |
| 09 | `DEC-TRF-004/005`, `07` và BE-INT-10 đã chốt recovery Tfail, TTL server; có GET theo reference/history. | Khi reload mất reference nhưng chỉ còn intent/key: BE có protected lookup theo key hay BA/FE chọn UX tra lịch sử/thông báo chờ? Nếu lưu key ở browser, chốt thời gian và bảo vệ. | BE + BA + FE | Một phần |
| 10 | `OI-API-07` vẫn mở retention/legal hold; OTP/SMS/outbox BE-INT-08 không phải notification inbox UI. | BA/Audit xác nhận FE có màn inbox/retention/legal hold hay việc vận hành nội bộ; nếu có, BE định nghĩa API/quyền/AC. | BA + BE + Audit | Chưa có |
| 11 | `dev2` đã có runtime fraud rule/review và `DEC-INT-020` ghi cập nhật flag + C2. Resource Plan W5/W6 vẫn là mốc module, chưa chốt bảng FR/AC P0/P1 Option B cho FE. | BA/người điều phối duyệt chức năng fraud nào thuộc release, màn/action nào ở P0/P1, mục hoãn có owner và trigger; tránh lấy runtime làm quyết định scope. | BA + điều phối | Một phần kỹ thuật; scope chưa có |
| 12 | `07` §8.6.3 đã mô tả `AUDIT_EXPORT`, CSV/JSONL, `maxRows` tối đa 10.000, stream, 413, snapshot, SHA-256 và `AUDIT_EXPORTED`; `DEC-INT-020` nói runtime đã dùng snapshot giới hạn. | `OI-API-11` vẫn hỏi sync 10k hay async; cần BE/Audit đồng bộ status trong 07/YAML, mẫu file thật, test size/413/permission/audit trước khi nối download FE. | BE + Audit + FE | Một phần |

## Phiếu câu hỏi mang đi chốt

1. **BA + BE + Security — GAP-01/02/03/10/11:** Hãy trả lời phạm vi release và API còn thiếu (register/recovery/PIN/admin customer/seed/block; người nhận; phone search/user lookup; notification/retention; Fraud P0/P1). Mỗi câu trả lời cần owner, version, FR/AC, quyền, trạng thái/lỗi và nơi cập nhật `07`/YAML. Riêng GAP-03 dùng [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]].
2. **BE Identity + Security + DB — GAP-04/05:** Khi nào có enrollment/trusted credential và test WebAuthn browser; có bàn giao runtime v2 theo baseline Security đã chọn không, cookie/CSRF/migration và môi trường nào chứng minh?
3. **BE + BA + Security — GAP-07:** Chọn ma trận permission duy nhất, xử lý mâu thuẫn Auditor, cách FE nhận capability hiện hành hoặc versioned mapping và chính sách refresh khi role đổi.
4. **BE + Security + FE — GAP-08/09:** Xác nhận version contract và bộ ví dụ/test cho 428, idempotency replay/conflict; chốt khôi phục transfer intent khi FE chưa có reference sau reload.
5. **BE Audit + FE — GAP-12:** Chốt sync hay async, giới hạn thực tế, file mẫu và test lỗi/quyền; sửa `OI-API-11` nếu sync 10k đã là quyết định chính thức.
6. **FE + kiến trúc — GAP-06:** Sau khi đối chiếu common, chốt ADR-001/UI kit và CI/runtime; không ngầm dùng lựa chọn FE để sửa chuẩn chung.

Sau phản hồi owner: cập nhật nguồn sở hữu `docs-common/07`, security/SRS/BRD khi liên quan và OpenAPI đúng version; sau đó cập nhật feature/standards/design FE, mock/client/test, tracker và phiếu evidence từng task. Chỉ chuyển phần UI/mock đã được duyệt sang tích hợp khi readiness và browser flow đạt theo [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]].
