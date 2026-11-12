# BE-COM-IMP-04 — Verification report

> **Dự án:** Digital Banking MVP
> **Task / phiên bản task:** [BE-COM-IMP-04](../tasks/BE-COM-IMP-04.md), v1.0 ngày 12/11/2026; nội dung AC giữ nguyên, snapshot/manifest trong evidence
> **Ngày / người thực hiện:** 12/11/2026, UTC+07:00 / Codex theo ủy quyền owner
> **Trạng thái task:** Done — implementer verified
> **Kết luận verification:** Verified trong phạm vi primitive crypto, JCS, masking và validation cấu hình khóa
> **Reviewer / sign-off:** Chưa review

## 1. Phạm vi và baseline

Triển khai phần còn thiếu của task 04: AES-256-GCM, HMAC/SHA-256, so sánh constant-time bằng JCA, RFC 8785 JCS, masking và fail-fast cấu hình. Không xây payload nghiệp vụ/persistence/OTP lifecycle/session, không thay password hashing hoặc tự triển khai rotation. Không nhận toàn hệ thống đã đạt encryption-at-rest/PII masking chỉ từ utility tests.

- HEAD: `73f190e0a2f34b5f95cc295a90e5fe8a23a4479b` + working tree chưa commit. [Manifest](evidence/BE-COM-IMP-04/source.sha256), [snapshot](evidence/BE-COM-IMP-04/source-snapshot.tar.gz) xác định bản code/config/test/docs/task đã dùng; snapshot gồm nền task 01–03 đang dirty để tái chạy đúng bản thực tế. Không reset hay xóa thay đổi trước phiên.
- Môi trường: Linux 7.0.0-43-generic, OpenJDK 21.0.13.0.3, Gradle 8.14.3, Spring Boot 3.5.7, JCS 1.1, Python 3.14.4. [Environment](evidence/BE-COM-IMP-04/environment.txt). PostgreSQL thật `postgres:16-alpine` qua Testcontainers cho regression nền. Crypto unit/component không dùng DB; operational HTTP chạy Tomcat thật với datasource/JPA/Flyway excluded.
- Dependency task 01: AppProperties nested defaults, external key binding, Crypto.toString redacted và Clock nền có thực, regression chạy lại đạt. Task 02/03 là nền trước phiên, giữ nguyên implementation; không dùng report lịch sử làm bằng chứng runtime thay cho lần chạy này.
- Đường dẫn user `digitalbankingdocs/.../taks` được đối chiếu thành thư mục thực `digitalbanking-docs/.../tasks`; ba README được đọc trước khi đọc task/code.

| Nguồn đã đọc / mục | Hành vi / traceability áp dụng |
|---|---|
| README gốc, docs-common/README, docs-backend/README | `01–11 → design → backend`, tra cứu đúng tên file, không sửa nguồn chuẩn để làm test pass |
| `03. SRS` §2.13.4, §4.5, §8.2; `04. Database design & analysis` §4, §9, §11.2 | FR-039, NFR-014/015/016/019/020, AC-023/030; enc/hash/last4, OTP/password never, OQ-15 Open |
| `05. Technical stack` §5.1.6; `06. System architecture` AD-12, §8.5, §9.4 | Khóa ngoài source, AES/HMAC và ranh giới common, hash_version thuộc canonical payload |
| `07. API contracts` §2.6, §5.2 | Account `**** **** 1234`, phone last3, email first char/domain, tên theo role; RFC 8785 request hash và payload caller-owned |
| `design/04. security` §6.2–6.4, §7 | IV12B+ciphertext+tag16B; SecureRandom; HMAC tách khóa, UTF-8; nationalId/IP mask; secret ngoài source, fail-fast demo/prod |
| `design/01. audit-traceability` §5.6, §7.2–7.3, Appendix B | Audit allowlist; UTF-16/ECMAScript/escaping, không normalize Unicode; delimiter `|` và vector `1c2215…405e` |
| `09. Test plan` §2.2, §8, §10 | Verified cần lệnh/artifact/revision/môi trường; skipped không phải pass; không dùng H2/mock để chứng minh PostgreSQL |
| common README §1.5, §2.3, §7.2; tasks README COM-G01..04 | OI-COM-04/07 đã phân xử; OI-COM-05 Open; COM-CRY-01..04/JCS-01..02/PII-01 phần primitive |
| [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html) §3.2, Appendix B; [JCS upstream](https://github.com/erdtman/java-json-canonicalization); [NIST AES-GCM examples](https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Standards-and-Guidelines/documents/examples/AES_GCM.pdf) GCM-AES256 Example #1 | Oracle độc lập về số/Unicode/escape và AES-GCM; dependency `io.github.erdtman:java-json-canonicalization:1.1`, Apache-2.0 |

## 2. Thay đổi thực tế

Các đường dẫn Java dưới đây thuộc `digitalbanking-backend/src/`; docs thuộc `digitalbanking-docs/`. Snapshot chứa toàn bộ file chính xác.

| File/thành phần | Thay đổi và mục đích | AC được đáp ứng |
|---|---|---|
| `main/java/.../common/config/CryptoConfig.java` | Tạo bean với khóa ngoài source; kiểm Base64/32B/tách khóa; môi trường mẫu; từ chối previousKeys chưa hỗ trợ; xóa decoded temporary buffers | AC02 |
| `main/java/.../common/crypto/PiiCryptoService.java` | AES/GCM/NoPadding, fresh IV12, tag128, one-shot decrypt; lỗi không cause/provider detail/plaintext | AC01/02 |
| `main/java/.../common/crypto/HmacService.java` | HMAC/SHA UTF-8/lowercase hex, overload SHA bytes; MessageDigest.isEqual; Mac mới cho mỗi lần gọi | AC03/06 |
| `main/java/.../common/crypto/JcsCanonicalizer.java` | Strict raw parser kiểm duplicate/syntax/surrogate/finite; JCS library với scalar-root adapter | AC04/06 |
| `main/java/.../common/crypto/PiiMaskingUtils.java` | Hàm thuần mask last4/phone/email/IPv4; null/invalid/IPv6 fail-closed, không log input | AC05 |
| `build.gradle.kts`, `gradle/libs.versions.toml` | Pin dependency JCS 1.1, không tự viết serializer RFC | AC04 |
| `test/java/.../common/crypto/{CryptoPrimitivesTest,CryptoConfigTest,JcsCanonicalizerTest,PiiMaskingUtilsTest,CryptoOperationsTest}.java`; `test/resources/crypto/{audit,transfer}.json` | 81 crypto test cases, gồm test vector và Tomcat operational endpoint/component | AC01..06 |
| backend README, backend deployment/configuration, common README, task 04 và tasks index | Handoff input/output/cách dùng/biên/rotation gap, trạng thái task và report | G04 |

Không sửa module nghiệp vụ, schema/migration, security chain hay contract OpenAPI. AppProperties/TimeConfig/context/error/config YAML/compose/.env.example đang có thay đổi từ phiên trước, task 04 không chỉnh các file đó. Snapshot giữ chúng để mô tả đúng nền đã test.

## 3. Ma trận tiêu chí chấp nhận

Giữ nguyên nội dung tiêu chí từ task và tasks README; kết quả Verified chỉ trong phạm vi task 04.

| AC ID | Tiêu chí / kết quả kỳ vọng từ nguồn | Test hoặc cách kiểm tra | Kết quả thực tế | Evidence ID | Trạng thái |
|---|---|---|---|---|---|
| COM-G01 | Hành vi và test oracle truy được về nguồn chuẩn; khác biệt/đề xuất còn mở được ghi rõ, không tự đổi contract/policy | Đối chiếu nguồn §1 và quyết định DEC-01..07 §5 | Oracle RFC/NIST/Python và nguồn chuẩn; giữ mọi open issue nguồn | E03/E04/E05 | Verified |
| COM-G02 | Diff đúng phạm vi task, giữ dependency và ownership của common; không thêm nghiệp vụ hoặc abstraction chưa có nhu cầu | ArchUnit 4 rules; review imports/diff và snapshot | Chỉ common crypto/config, dependency JCS, test/docs; không import nghiệp vụ, entity, repository hay transaction | E04/E05 | Verified |
| COM-G03 | Kiểm thử phù hợp cho thay đổi đã chạy và đạt; không dùng mock/H2 để chứng minh lock, constraint hay persistence PostgreSQL | clean build; kiểm XML, exit code và snapshot | 151 tests; 0 failure/error/skip, gồm 81 crypto tests; PostgreSQL thật chỉ là regression nền | E04 | Verified |
| COM-G04 | Report đủ mọi AC, docs liên quan đồng bộ, giới hạn và việc bàn giao có đầu mối; evidence không lộ secret/PII | Report đủ 10 AC, handoff README, index/task cập nhật; kiểm evidence | Report + source snapshot + XML/log đã lọc generated password, chỉ dữ liệu synthetic | E05 | Verified |
| COM-IMP-04-AC01 | AES-256-GCM tạo blob IV 12B + ciphertext + tag 16B, IV ngẫu nhiên mới mỗi lần; decrypt xác thực đầy đủ | CryptoPrimitivesTest: utf8AndEmptyRoundtripWithFreshIvAndCorrectBlobLayout; rejectsEveryTamperedByteWrongKeyTruncationAndExtensionWithoutProviderDetails; decryptsIndependentAes256GcmNistVector | 150 IV khác nhau, roundtrip rỗng/Unicode; đúng IV12+UTF8+tag16; lật từng byte, mọi độ dài cắt, nối thừa và wrong key đều bị từ chối; vector NIST đạt | E04 | Verified |
| COM-IMP-04-AC02 | Khóa AES/HMAC tách biệt, lấy ngoài source; Base64/độ dài 32 byte được kiểm tra; thiếu/sai/placeholder không hợp lệ bị fail-fast theo môi trường nguồn yêu cầu | CryptoConfigTest (15 cases, nhiều vòng lặp nội bộ); CryptoOperationsTest; inspection AppProperties/CryptoConfig | Base64/31/33 byte/thiếu/trùng/placeholder/rotation cấu hình sai fail-fast; local/test và external key startup đạt; log/toString/exception/6 endpoint không lộ khóa | E04/E05 | Verified |
| COM-IMP-04-AC03 | HMAC/SHA đúng bytes/encoding/hex lowercase; utility không tự sửa input nghiệp vụ; so sánh secret/hash dùng primitive constant-time | CryptoPrimitivesTest: knownAnswersPreserveLeadingZerosWhitespaceUtf8AndOtpCallerBinding; constantTimePrimitiveAcceptsEqualAndRejectsDifferentAndDifferentLengths; independent-oracles.py | 11 oracle độc lập khớp, có UTF-8/leading-zero output/input/whitespace/challenge binding; review MessageDigest.isEqual, không đo thời gian để kết luận | E03/E04/E05 | Verified |
| COM-IMP-04-AC04 | JCS đầy đủ RFC 8785: UTF-16 key order, number/escape đúng chuẩn, giữ array order/null, không tự normalize Unicode; input ngoài miền JCS/duplicate key không bị âm thầm chấp nhận | JcsCanonicalizerTest: 24 RFC Appendix B cases; rfcUtf16KeyOrderIncludesSupplementaryCodePoints; numbersEscapingNullArrayOrderAndNestedKeysFollowJcs; 17 negative cases | Đúng UTF-16, số ECMAScript, escaping, scalar root/null/array; Unicode decomposition giữ nguyên; duplicate raw/escaped/nested, surrogate lỗi, non-finite, syntax lỗi bị từ chối bằng strict streaming parser | E02/E04 | Verified |
| COM-IMP-04-AC05 | Masking theo nguồn: account `**** **** 1234`, phone/email và các trường còn lại theo policy tương ứng; không fallback trả nguyên PII khi input lỗi | PiiMaskingUtilsTest: sourceFormatsPreserveOnlyPermittedParts; missingValuesStayAbsent; 13 invalid cases; DEC-03/04 | Account/nationalId/phone/email/IPv4 đúng nguồn, không echo input lỗi; IPv6 bỏ field/null; tên theo role chưa đủ thuật toán nên caller phải giải quyết an toàn, không thêm public policy | E04/E05 | Verified |
| COM-IMP-04-AC06 | Utility dùng được cho payload transfer/audit do caller cung cấp mà không phụ thuộc business module; hash tương thích ví dụ/vector nguồn | JcsCanonicalizerTest.callerOwnedTransferAndAuditPayloadsMatchIndependentHashes; CryptoOperationsTest; ArchUnit | Transfer theo 07 §5.2 khớp b03c…839a; audit Appendix B khớp 1c22…405e; production common không ẩn payload builder/hash-chain protocol | E03/E04/E05 | Verified |

## 4. Nhật ký kiểm chứng và evidence

Mọi lệnh Gradle chạy từ `digitalbanking-backend/`, ngày 12/11/2026 UTC+07:00. E04 là bản source/test cuối; sau E04 chỉ chỉnh docs/evidence/trạng thái task, không chỉnh runtime/test/build configuration.

| Evidence ID | Thời điểm / môi trường / revision | Working directory + lệnh hoặc bước thực chạy | Expected → actual; exit code, số pass/fail/skip | Artifact tái kiểm tra được |
|---|---|---|---|---|
| E00 | Trước compile, sandbox workspace-write | `./gradlew test --tests '*common.crypto.*' --console=plain`; `./digitalbanking-backend/gradlew --version` | Cache Gradle ngoài writable root bị từ chối, chưa chạy test; exit 1. Sau đó dùng execution được cấp quyền cache/network/socket | [sandbox log](evidence/BE-COM-IMP-04/sandbox-gradle.log) |
| E01 | Trước 14:47 | `./gradlew compileJava --console=plain`; `./gradlew test --tests '*common.crypto.*' --console=plain` | Compile exit 0; focused: 84 cases, 1 failure root null do library chỉ nhận container, exit 1. Không hạ AC; sửa singleton-array adapter và kiểm đúng một root | [focused fail](evidence/BE-COM-IMP-04/focused.log) |
| E02 | Khoảng 14:47:20 | Cùng focused command, thêm operational HTTP test | Exit 0, build/test đạt; số case của bản cuối căn cứ XML E04. Giữ nguyên con số 84 được runner báo ở E01, không dùng log lần fail để suy coverage bản cuối | [focused retest](evidence/BE-COM-IMP-04/focused-retest.log) |
| E03 | Trước E04 và chạy xác nhận khi lưu evidence | Từ repo root: `python3 digitalbanking-docs/docs-backend/modules/common/report/evidence/BE-COM-IMP-04/independent-oracles.py` | Python hashlib/hmac độc lập, 11 stored fields khớp; audit hash đúng Appendix B, exit 0 | [script](evidence/BE-COM-IMP-04/independent-oracles.py), [results](evidence/BE-COM-IMP-04/independent-oracles.json), [log](evidence/BE-COM-IMP-04/oracles.log) |
| E04a | Khoảng 14:47:54 | `./gradlew clean build --console=plain` | 151 tests, 0 fail/error/skip; exit 0. Sau đó xác nhận lại vector AES bằng PDF NIST và thêm scalar true/number/string assertions | [build](evidence/BE-COM-IMP-04/build.log) |
| E04 | Khoảng 14:49:15, bản cuối | `./gradlew clean build --console=plain` | Compile/package/test/check/JaCoCo đạt; exit 0, **151 tests, 0 failures/errors/skips**, gồm 81 crypto, 4 ArchUnit và PostgreSQL regression thật | [final build](evidence/BE-COM-IMP-04/build-final.log), [summary](evidence/BE-COM-IMP-04/test-summary.txt), XML bên dưới |
| E05 | Sau E04 | `git diff --check`; review CryptoConfig, crypto package, AppProperties redaction, production security/exposure settings và imports; tạo manifest/snapshot và kiểm link | Diff check exit 0; common không import nghiệp vụ/transaction/DB; không có key thật trong evidence; docs đầy đủ | [inspection](evidence/BE-COM-IMP-04/inspection.txt), [source manifest](evidence/BE-COM-IMP-04/source.sha256), [snapshot](evidence/BE-COM-IMP-04/source-snapshot.tar.gz) |

XML: [CryptoPrimitivesTest](evidence/BE-COM-IMP-04/TEST-com.simulatedbank.banking.common.crypto.CryptoPrimitivesTest.xml), [CryptoConfigTest](evidence/BE-COM-IMP-04/TEST-com.simulatedbank.banking.common.crypto.CryptoConfigTest.xml), [JcsCanonicalizerTest](evidence/BE-COM-IMP-04/TEST-com.simulatedbank.banking.common.crypto.JcsCanonicalizerTest.xml), [PiiMaskingUtilsTest](evidence/BE-COM-IMP-04/TEST-com.simulatedbank.banking.common.crypto.PiiMaskingUtilsTest.xml), [CryptoOperationsTest](evidence/BE-COM-IMP-04/TEST-com.simulatedbank.banking.common.crypto.CryptoOperationsTest.xml). Các XML regression còn lại cũng lưu cùng folder.

Test mapping: COM-CRY-01/03 → roundtrip/tamper/wrong-key/NIST; COM-CRY-02 → startup/profile/secret tests; COM-CRY-04 → known-answer và byte compare (không chứng minh OTP TTL/single-use). COM-JCS-01 → 24 Appendix B vectors + UTF-16/escape/null/nested; COM-JCS-02/T-AUD-01 phần primitive → raw duplicate rejection + caller-owned fixtures. COM-PII-01/CT-API-017 phần utility → masking tests. COM-PII-02/03 toàn hệ thống không được nhận nghiệm thu từ task này; IMP-07 cần persistence, response/log/outbox/audit integration.

Không có test bị skip/block trong E04. Không dùng mock/H2 để chứng minh encryption persistence/locking. Operational test gọi 6 endpoint thật: health/info 200, env/configprops/metrics/prometheus 403 ở cấu hình production hiện tại, body không chứa khóa; exposure allowlist không gồm env/configprops. Đây không phải kiểm thử custom deployment thay exposure/security. XML chỉ redact dòng generated Spring security password; giữ nguyên test counts/testcase/assertions. NIST/RFC và mọi fixture/key trong unit test là dữ liệu công khai/synthetic, không secret triển khai. Không thu thập `.env`, dump env, cookie/token thật.

## 5. Gap, lỗi và quyết định còn mở

### 5.1. Quyết định thực hiện theo tài liệu và ủy quyền owner

| ID | Điểm mơ hồ / conflict, nguồn | Quyết định và tác động |
|---|---|---|
| DEC-01 | SRS §2.13.4/07 §2.6 khác design/04 §7.2 về account | Dùng `**** **** last4`, giữ nationalId `********last4`; không sửa docs-common; OI-COM-04 đã phân xử |
| DEC-02 | Java backend minh họa JsonNode nhưng duplicate keys mất trước JCS; thư viện chỉ nhận container | Chọn raw JSON String, streaming parser strict trước mọi tree, không cung cấp JsonNode/Map overload; singleton-array wrapper giữ đúng scalar roots và bỏ duy nhất byte `[`/`]` sau canonicalization. Dùng thư viện thay sort-map serializer. Negative/multi-root và RFC vectors bắt buộc đạt |
| DEC-03 | Chưa chốt biên input/chữ ký Java trong backend | Chọn null→null cho mask; input sai→exception cố định không cause/input; phone ASCII 4–15 chữ số tùy chọn +, email ASCII conservative dotted domain, IPv4 decimal strict. Đây là internal display boundary, không tự trở thành API/business validator; caller phải xử lý unsupported forms an toàn. Crypto/hash non-null. Constructors byte[] dùng bởi CryptoConfig, caller inject service beans |
| DEC-04 | 07 có tên theo role nhưng chỉ ví dụ, IPv6 chưa có policy; audit allowlist cấm field kể cả masked | Không tự tạo thuật toán tên/role, không thêm name helper trả full PII; owner map display-safe theo quyền. IPv6→null, không trả raw; secret/fingerprint/description omitted/redacted bởi caller. Không coi utility là authorization/PII scanner |
| DEC-05 | design/04 nêu fail-fast demo/prod, nguồn yêu cầu Base64/32B và tách key; local/test có published keys | Kiểm format/32B/khác nhau ở mọi profile. Chỉ profile hoàn toàn local/test được dùng khóa mẫu; profile trộn với demo/prod/cloud từ chối. Ngoài dev từ chối hai published keys trong repo và uniform-byte placeholders; không suy entropy/không hứa phát hiện mọi placeholder. Không dùng Bean Validation trên raw secret fields vì failure diagnostics có thể echo rejected value; kiểm tra bên trong CryptoConfig với lỗi an toàn |
| DEC-06 | OQ-15/OI-COM-05 Open; design/04 đề cập previousKeys nhưng chưa đủ blob/index/schema protocol | Một active key, không embed key ID, không thử lần lượt old keys; previousKeys không rỗng fail-fast để không âm thầm bỏ cấu hình. aesKeyId metadata không dùng cho decrypt/rotation. Không đóng issue nguồn |
| DEC-07 | 04 §4.6.1 ghi thêm hash_version; 04 §9.4/06 §8.5 đặt hash_version trong payload, design/01 giải thích alignment và delimiter | Không chọn policy audit trong production common. Caller fixture theo design/01 Appendix B: hash_version một lần trong payload, hash JCS + `|` + prev_hash khớp nguồn và Python. Mâu thuẫn câu chữ 01–11 vẫn cần owner audit/architecture đồng bộ; không sửa nguồn/khóa chặt hash protocol vào common. Primitive SHA nhận bytes chính xác caller đưa |

### 5.2. Các giới hạn bàn giao (không đóng issue nguồn)

| ID | AC ảnh hưởng | Gap/lỗi và tác động | Trạng thái | Đầu mối / điều kiện tiếp tục | Quyết định hoặc evidence |
|---|---|---|---|---|---|
| OQ-15 / OI-COM-05 | Không chặn AC01..06 trong scope một khóa; chặn tuyên bố rotation | Thiếu nhận diện khóa cũ, re-encrypt/blind-index migration, KMS | Open tại nguồn | Security + account/customer + DevOps chốt protocol trước dùng nhiều khóa | DEC-06, task §1 |
| HANDOFF-01 | Không chặn primitive AC05; chưa nghiệm thu FR-039/AC-023 toàn hệ thống | Role-aware name policy, input email/phone ngoài biên, IPv6; audit phải enforce allowlist và scanner độc lập | Open / owner integration | identity/customer/account/transfer/audit áp mapper theo 07; không fallback plaintext; chốt policy trước mở rộng helper | DEC-03/04; README usage |
| HANDOFF-02 | Không chặn AC06; audit integration chưa nghiệm thu | Đồng bộ câu chữ hash_version trong 04; audit owner phải chốt field set/hash_version trước persistence/verification chain | Open | audit + architecture, giữ ưu tiên 01–11; không lấy test fixture làm đặc tả mới | DEC-07 |
| IMP-07 / IMP-08 | Ngoài phạm vi utility tests | Chưa chứng minh ciphertext lưu DB, key reload/operations, cross-channel PII, backup/storage/TLS/Cloud | Planned theo backlog | identity/customer/account/transfer/audit/notification, Security và DevOps kiểm chứng trên flow thực | Task 04 bàn giao, nguồn 09 |

Không có lỗi/blocker ảnh hưởng các AC của task 04 sau E04. Placeholder detector là danh sách guard, không phải entropy test. Giới hạn JSON nesting/size còn chịu parser/library resource limits; owner phải dùng request body limit theo contract ở IMP-05. Không tự biến cấu hình hoặc utility thành policy public.

## 6. Kết luận và bàn giao

- **10 AC: 10 Verified**, 0 Not accepted/Blocked/Not verified trong phạm vi task. **151 tests đạt**, 81 crypto, 0 fail/error/skip. Build/package/ArchUnit/PostgreSQL regression đạt theo E04.
- Kết luận implementer: hoàn thành BE-COM-IMP-04; đề nghị **Done — chờ review**, không đồng nghĩa module/toàn hệ thống đã nghiệm thu.
- Handoff chi tiết input/output/cách dùng/giới hạn tại [backend README](../../../../../digitalbanking-backend/README.md#crypto-canonicalization-and-masking), [module crypto contract](../README.md#23-gói-crypto), [cấu hình](../../../deployment/configuration.md).
- Identity (Đạt): HMAC input challengeId:otp do caller xây, session/CSRF SHA256 + byte compare; giữ Argon2id/bcrypt cho password, tự sở hữu TTL/replay/attempt. Customer/account (Đạt): normalize identifiers giữ leading zeros, AES BYTEA/HMAC/last4 do module persist, áp quyền/PII mapper. Transfer (Vũ): normalize payload 07 §5.2 trước JCS, SHA trên canonical bytes, tự sở hữu idempotency. Audit/notification (Thịnh): versioned payload, timestamp normalization, delimiter/hash chain/allowlist/scanner và outbox display-safe do owner; dùng Appendix B test vector làm kiểm tra tương thích. Phương/Security/DevOps giữ đầu mối gap rotation cùng các owner theo OQ-15.
- Tiếp theo: task 05/06 wiring HTTP security/observability; task 07 chứng minh persistence/cross-channel PII; task 08 vận hành/Cloud. Không tự đánh dấu các task này hoàn thành.
- Review/sign-off thực tế: **Chưa review**; implementer không tự ký thay reviewer.
