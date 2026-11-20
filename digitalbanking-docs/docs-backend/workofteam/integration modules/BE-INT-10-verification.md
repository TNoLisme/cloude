# BE-INT-10 — Kiểm chứng recovery và Tfail trên PostgreSQL

| Mục | Phạm vi |
|---|---|
| Trạng thái | **Verified.** Lượt cuối 178/178 test qua 15 lớp, gồm 14/14 ca recovery/Tfail mới trong 62 ca Transfer T2. Lượt encoder 28/28, gồm 7 ca encoder và 21 ca boundary đã chạy ở lượt trước. Không có failure, error hoặc skip ở hai lượt cuối. |
| Ngày | 20/11/2026, Asia/Ho_Chi_Minh |
| Revision | `phuong/integration@1d32536f0e63916751e32dacbb4b5515ff5d1cf8` với thay đổi workspace BE-INT-10. [Manifest SHA-256](evidence/BE-INT-10/source-manifest.sha256) |
| Môi trường | Java 21.0.13.1, Gradle 8.14.3, Docker 29.8.0, PostgreSQL 16.15 Testcontainers, Spring profile `test`. Worker dùng role `banking_app` và transaction manager của T2. Flyway và kết nối quan sát dùng role riêng. |
| Yêu cầu | FR-022, AC-012/013, T-REC-01–04, T-CREDIT-FENCE-01 và T-CREDIT-MAP-01; DEC-TRF-003/006/010/013/014 và DEC-INT-019. |
| Tái lập | Chạy `sh '../digitalbanking-docs/docs-backend/workofteam/integration modules/evidence/BE-INT-10/verify.sh'` từ `digitalbanking-backend` với Docker và Gradle cache đã có dependency. |
| Artifacts | [Lệnh, revision và kết quả từng lượt](evidence/BE-INT-10/results.json), [JUnit cuối](evidence/BE-INT-10/junit-final.xml), [Gradle cuối](evidence/BE-INT-10/gradle-final.txt), [JUnit encoder](evidence/BE-INT-10/junit-log-encoder.xml), [Checksum artifact](evidence/BE-INT-10/artifacts.sha256). |

## Nghiệm thu

| Vector | Kết quả quan sát |
|---|---|
| T-REC-01 | HTTP tạo intent T1 thật. Handoff dừng trước T2. Worker mới phát hiện intent quá tuổi; observer độc lập thấy epoch/token/lease đã commit trước bước tiền. Cả hai số dư, hai ledger entries, một Account witness, một Limit effect, outbox và C1 đúng. Worker mới chạy lại vẫn chỉ có một kết quả, HTTP replay 201. |
| T-REC-02 | Fixture ép status Transfer còn PENDING khi provider thật commit movement, ledger, Limit, Audit và phản hồi gốc. Replay trả 409 khi Transfer còn PENDING. Tiêm SQL lỗi tại `TRANSFER_RECONCILED` chứng minh rollback cả repair status và idempotency. Sau khi test cho phép xử lý lại, worker commit COMPLETED, snapshot gốc và C1 SYSTEM. Tiền, ledger, Limit usage và outbox giữ số lượng ban đầu. |
| T-REC-03 | Ledger một vế, Limit khác basis, thiếu Audit completion, response thiếu masking, basis chưa biết và PostgreSQL lock timeout khi đọc Ledger đều để PENDING/MANUAL. Không tạo thêm money/ledger/usage; API replay giữ 409. Counter incident tăng và JSON log giữ event, transfer ID, reason và trace qua allowlist. |
| T-REC-04 | Connection giữ customer guard hoặc Transfer row khiến worker bỏ qua, count vẫn 0. Hai worker độc lập cạnh tranh khi worker đầu đang xử lý cho đúng một claim và một kết quả. Worker mới đưa claim hết lease sang MANUAL, rồi thử lại bằng epoch/token cũ qua cả T2 và Tfail đều không ghi tiền hoặc terminal. |
| Credit/Tfail | Trigger SQL quan sát debit đã xảy ra rồi abort credit. Sau rollback, Tfail ghi FAILED/422, release không usage, outbox và C1. Tiêm lỗi Audit trong Tfail để chứng minh không commit FAILED, release hoặc response. Mô phỏng dừng worker ngay sau commit marker, worker mới tiếp tục Tfail với intent dưới 300 giây và cùng epoch. Nhánh savepoint, mất ACK sau commit và ngắt PostgreSQL ngay trước commit được chạy lại. |
| V24 | Flyway fresh V1–V24 và Hibernate validate. Upgrade V23→V24 giữ checksum và bản ghi cũ, không bịa basis/witness. Migration lặp không tác động. Runtime purge chỉ xóa idempotency terminal đã hết hạn có Transfer khớp, giữ idempotency PENDING đã quá hạn. |

## Phạm vi và lịch sử

Account, Ledger, Limit và Audit đều dùng provider thật. Hai worker là hai instance trên cùng PostgreSQL với các connection độc lập; restart được mô phỏng bằng việc dừng tại mốc đã kiểm và tạo worker mới. Fixture SQL chỉ tạo lịch sử lệch và lỗi có chủ đích. T-REC-02 không kích hoạt API rearm thật. Scheduler có wiring startup/60 giây theo cấu hình opt-in; test gọi job chủ động và không đo chu kỳ polling triển khai. Rule Prometheus được kiểm cấu trúc YAML; metric và payload log được kiểm trong test ứng dụng.

Bộ lọc `--tests` theo DEC-INT-007 phủ T0/T1/T2, recovery, Ledger/Limit, ranh giới module và các kỳ vọng schema bị tác động bởi V24. Lượt đầu đạt 57/59, gồm 12/12 ca provider BE-INT-10. Hai fixture candidate còn kỳ vọng TTL cũ hoặc matcher basis chưa phù hợp. Lượt hồi quy sau đạt 176/177; fixture Session còn cố định dải Flyway V1–V22. Artifact giữ cả các lượt này. JUnit trích metadata và kết quả, loại stdout, thông tin xác thực và dữ liệu HTTP. Full regression và diễn tập vận hành nằm ngoài bằng chứng BE-INT-10.
