# BE-INT-02 — Verification report cho DB canonical mới

> **Ngày:** 18/11/2026, Asia/Ho_Chi_Minh
> **Thực hiện/chốt:** merge owner theo DEC-INT-003/005
> **Scope:** build/schema/regression của ứng dụng đã hợp nhất; môi trường đích là DB canonical mới.
> **Source:** `phuong/integration@8109504` + working tree; [source manifest](evidence/BE-INT-02/source-manifest.sha256).
> **Trạng thái:** **VERIFIED — BE-INT-02 DONE / G1 PASS cho DB canonical mới**. Full clean build exit 0, 2.285 test / 202 class, không failure/error/skip.

## 1. Môi trường và giới hạn

- OpenJDK 21.0.13.0.3, Gradle 8.14.3, Docker 29.8.0, PostgreSQL `16-alpine` Testcontainers (image ID `sha256:aa6cb33cf4c19c765cefcf48d96176258800870a9a1e930ad126200f9d825139`); một Gradle worker.
- Flyway chỉ đọc `classpath:db/migration`, schema `banking`; Hibernate `ddl-auto=validate`. Chuỗi mới V1–V19; SQL module-local V2–V6 vẫn lưu ở `migration-legacy` và không thuộc runtime location.
- Dữ liệu tổng hợp, container cô lập; không kết nối hoặc chuyển dữ liệu DB môi trường. Người điều hành chọn DB canonical mới nên inventory/copy legacy thật là **N/A**, không phải đã hoàn thành chuyển dữ liệu.
- Standard `test/build` loại tag `performance` theo cấu hình hiện hành. Kết quả build không thay nghiệm thu D1–D5, E2E hay G0/G2/G3. Fake trong test Admin LIMIT chỉ chứng minh consumer/transaction của fixture; full suite cũng có test với Identity/Customer/Account/Audit thật.
- `AGENTS.md` đã có thay đổi trước khi tiếp tục task, không thuộc thay đổi BE-INT-02.

## 2. Vấn đề và cách xử lý

| ID | Actual / nguyên nhân | Sửa và oracle |
|---|---|---|
| INT02-ADM-01 | JSON number bị Jackson ép thành String cho ID/Money | Deserializer cục bộ Admin từ chối token khác string; `07` §2/§8.5.2. Giữ vector HTTP invalid trước business write. |
| INT02-ADM-02 | Test fake bỏ qua purpose cấu hình; boundary chấp nhận purpose khác SENSITIVE_ACTION | Sửa fake phản ánh purpose; boundary fail-closed 503 khi provider trả sai purpose. |
| INT02-DB-01 | V12 cấp UPDATE toàn bảng approval, gồm maker/target/request facts | V18 revoke table UPDATE, grant cột workflow; ORM đánh dấu request_type/target_type/target_id `updatable=false`. Test quyền thực của banking_app, rollback, checker race và upgrade giữ checksum. |
| INT02-FIXTURE-01 | Kỳ vọng reject retry cùng key; hai create cùng payload/key nhưng đòi hai approval; fixture session trùng provider thật | Theo `07` §5.6, test replay 201/header, hai intent dùng payload/key khác; test session fixture Primary. Không bỏ kiểm tra một apply và rollback challenge. |
| INT02-FIXTURE-02 | E4 cũ cấm future/scanner reason/account scope mới; API canonical và provider mutation hiện hành khác | Theo DEC-INT-005: giữ future, validate length/control char, kiểm usage không reset, gọi đúng policy properties. Lỗi scope/policy được kiểm sau step-up trong TX provider và phải rollback cả approval/config/consume. |
| INT02-BOOT-01 | Bean Limit E4 vẫn bật khi app.limit.enabled=false, kéo JPA vào context common không datasource | Các entrypoint/service/adapter E4 tuân module flag hiện có, mặc định vẫn bật. Test common security/context/crypto/health qua. |
| INT02-DB-02 | Daily limit NaN vượt CHECK positive/range của PostgreSQL NUMERIC | V19 thêm CHECK hữu hạn; giữ V9. Test fresh từ chối NaN và upgrade V18 có NaN phải fail-closed, giữ nguyên history/row. |
| INT02-FIXTURE-03 | LimitRules thiếu approval parent, Notification thiếu challenge/transfer parent, Transfer HTTP thiếu staff-scope bean | Seed parent trong container; giữ FK và thêm staff-scope fake giới hạn đúng actor/transfer trong test, có vector deny ngoài scope. Không bật production policy hoặc giả provider thật. |
| INT02-TEST-01 | Context cache giữ pool sau khi container dừng; worker chờ Hikari shutdown, connection-adder retry | Profile test minimum-idle=0; production pool không đổi. Thread dumps baseline được lưu. |

## 3. Lịch sử chạy và kết quả

| Run | Source / expected | Actual | Artifact |
|---|---|---|---|
| Baseline | HEAD 8109504 trước sửa; full build kỳ vọng exit 0 | **INTERRUPTED / exit 143** sau các lỗi Admin và common; dừng có chủ đích để sửa, không tính full-suite PASS hoặc công bố tổng test | [log](evidence/BE-INT-02/baseline-build.log), [thread dumps](evidence/BE-INT-02/baseline-thread-dumps.tar.gz) |
| Targeted 1 | Sau V18/flag/fixture, trước sửa ORM immutable mapping và Primary fixture | **116 test: 98 PASS, 18 FAIL**, exit 1. Migration/common/health PASS; 17 Admin lỗi SQL UPDATE cột bị cấm, 1 session fixture lỗi 500 | [log](evidence/BE-INT-02/targeted-1.log), [JUnit XML](evidence/BE-INT-02/targeted-1-junit.tar.gz) |
| Targeted 2 | Sau sửa ORM và session fixture; manifest SHA-256 `0ffb734c2b91207515d4087e57784f0c00ef214cc04bd385a46bcdf5d4eae5b3` | **29/29 PASS**, 0 failure/error/skip, exit 0; 28 Admin LIMIT + 1 unavailable provider; BUILD SUCCESSFUL in 39s | [log](evidence/BE-INT-02/targeted-2.log), [JUnit XML](evidence/BE-INT-02/targeted-2-junit.tar.gz), [summary](evidence/BE-INT-02/targeted-2-summary.json), [source](evidence/BE-INT-02/targeted-2-source-manifest.sha256) |
| Full 1 | Source manifest `0ffb734c2b91207515d4087e57784f0c00ef214cc04bd385a46bcdf5d4eae5b3`; toàn standard suite | **2.283 test / 202 class: 2.240 PASS, 43 FAIL**, 0 error/skip, exit 1, 14m22s. Còn fixture Limit/Notification/Transfer và daily_limit NaN | [log](evidence/BE-INT-02/full-1.log), [JUnit XML](evidence/BE-INT-02/full-1-junit.tar.gz), [source](evidence/BE-INT-02/full-1-source-manifest.sha256), [patch](evidence/BE-INT-02/full-1-source-changes.patch) |
| Targeted 3 | Sau DEC-INT-006 / V19; source manifest `a9e56ac53112f86c5a78bb3f99e902c96c219d152f7f33a0c3b33b4b40bb66bf` | **166/166 PASS, 18 class**, 0 failure/error/skip, exit 0, 1m27s. Bao gồm migration, finite/NaN upgrade, Limit fixture, Notification và Transfer HTTP contract với fake staff-scope | [log](evidence/BE-INT-02/targeted-3.log), [JUnit XML](evidence/BE-INT-02/targeted-3-junit.tar.gz), [summary](evidence/BE-INT-02/targeted-3-summary.json), [source](evidence/BE-INT-02/targeted-3-source-manifest.sha256) |
| Full clean build cuối | Source manifest `a9e56ac53112f86c5a78bb3f99e902c96c219d152f7f33a0c3b33b4b40bb66bf`; toàn standard suite + packaged JAR + JaCoCo | **2.285/2.285 PASS, 202 class**, 0 failure/error/skip, exit 0; BUILD SUCCESSFUL in **13m56s** | [log](evidence/BE-INT-02/final-build.log), [JUnit/HTML/JaCoCo](evidence/BE-INT-02/final-results.tar.gz), [summary](evidence/BE-INT-02/final-summary.json), [JAR manifest](evidence/BE-INT-02/packaged-artifact.json) |

[Run metadata](evidence/BE-INT-02/runs.json) lưu argv chính xác của từng lượt và môi trường. Không cộng các run thành tổng test độc lập: nhiều case được chạy lại. Các run lỗi giữ lại để truy vết, được thay thế về kết luận hiện hành bởi lượt full build khi có kết quả.

## 4. Lệnh và phạm vi chạy

Chạy từ `digitalbanking-backend`, Java 21 và Docker có image/cache dependency cần thiết. [Patch source](evidence/BE-INT-02/source-changes.patch) cùng source manifest cho phép đối chiếu working tree với HEAD 8109504; không bao gồm thay đổi AGENTS.md có trước task. Theo DEC-INT-007, chỉ chọn test liên quan đến thay đổi cụ thể; ví dụ khi sửa Admin LIMIT:

```bash
./gradlew test --offline --no-daemon --max-workers=1 \
  --tests 'com.simulatedbank.banking.admin.LimitAdminHttpIT' \
  --tests 'com.simulatedbank.banking.admin.LimitAdminMissingProviderIT'
```

Lệnh full suite dưới đây chỉ ghi lại lượt lịch sử đã chạy cho report này. Không tự chạy lại trên local; chỉ dùng khi người điều hành yêu cầu rõ:

```bash
./gradlew clean build --offline --no-daemon --max-workers=1
```

Không chạy hai Gradle cùng ghi `build/`. XML/HTML tại `build/test-results/test/` và `build/reports/tests/test/` bị thay ở lượt sau; report này lưu bản archive cho từng run đã kết thúc. [Checksum migration](evidence/BE-INT-02/migration-checksums.json) so SHA-256 V1–V17 với HEAD; test V17→V19 kiểm checksum Flyway trong DB, hai loại checksum khác nhau.

## 5. Quyết định nghiệm thu

**Đóng BE-INT-02, G1 VERIFIED cho DB canonical mới.** Compile/testClasses/package/check/JaCoCo đều hoàn tất trong clean build exit 0. Fresh V1–V19, upgrade V7→V19 và V17→V19, quyền DB, checksum, legacy fail-closed/restore và Hibernate/packaged boot đều PASS. V18→V19 với NaN được kiểm từ chối, giữ nguyên dữ liệu/history. Source manifest không đổi trong lượt full cuối; JAR SHA-256 `48090b7f1cb2c6a970b25339bc23cb577a0f3386834d86e5ef0b5b95108f01d2`, chứa đúng 19 migration runtime.

Chuyển dữ liệu legacy thật N/A theo lựa chọn của người điều hành. Giữ các dependency Transfer/Limit/Identity/recovery trong BE-INT-01 và G0/G2/G3 ở trạng thái hiện hành; kết quả này không kích hoạt money path hoặc nghiệm thu E2E/D1–D5.
