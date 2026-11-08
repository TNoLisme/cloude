# Phase 4 – Documentation Review

> **Dự án:** Digital Banking  
> **Phạm vi:** `01. Introduction`, `02. BRD`, `03. SRS` (bản chính thức trong `docs-common`)  
> **Ngày review:** 02/11/2026  
> **Người thực hiện:** Senior Business Analyst / System Analyst

---

## 1. Kết luận điều hành

Bộ tài liệu 01–03 đã có cấu trúc đầy đủ, phạm vi Common-focused MVP nhất quán và traceability tổng thể tốt. Tuy nhiên, **chưa nên chốt sign-off ngay** vì còn 3 lỗi mức High và một số lỗi Medium ảnh hưởng đến tính nhất quán, khả năng triển khai và kiểm chứng.

**Kết luận:** **Pass – toàn bộ High/Medium/Low đã được xử lý; sẵn sàng sign-off.**

| Mức | Số lượng | Nội dung chính |
|---|---|---|
| High | 0 (đã xử lý) | F-01/F-02/F-03 đã resolved; xem mục 10 |
| Medium | 0 (đã xử lý) | F-04 – F-10 đã resolved; xem mục 11 |
| Low | 0 (đã xử lý) | F-11 – F-14 đã resolved; xem mục 12 |

---

## 2. Phạm vi và phương pháp

### 2.1. Tài liệu đã review

| Tài liệu | Đường dẫn | Vai trò |
|---|---|---|
| 01. Introduction | `docs-common/01. Introduction.md` | Bối cảnh, mục tiêu, phạm vi, stakeholder |
| 02. BRD | `docs-common/02. BRD.md` | Business requirements, rules, processes |
| 03. SRS | `docs-common/03. SRS.md` | Functional/non-functional requirements, use cases, acceptance |

### 2.2. Phương pháp kiểm tra

- Đối chiếu phạm vi, stakeholder, objectives, metrics, constraints, assumptions, dependencies và open issues giữa 01–03.
- Kiểm tra ID duy nhất, đúng dải, không trùng và không còn ID đã loại bỏ.
- Kiểm tra traceability: `OBJ → BR → FR/NFR → UC/AC`; `BP → UC`; `BRL → SYS-RL/FR`.
- Rà soát mâu thuẫn về trạng thái, threshold, role/permission, out-of-scope và error behavior.
- Rà soát nội dung trùng lặp, thiếu sót và chất lượng diễn đạt.

---

## 3. Tổng quan nhất quán

| Khu vực | 01 Introduction | 02 BRD | 03 SRS | Kết quả |
|---|---|---|---|---|
| Project identity | Digital Banking | Digital Banking | Digital Banking | Nhất quán |
| Phạm vi | Common-focused MVP | Kế thừa 01 | Kế thừa 01/02 | Nhất quán, trừ out-of-scope chi tiết |
| Stakeholders | STK-001 – STK-005 | STK-001 – STK-005 | STK-001 – STK-005 | Nhất quán (F-01 resolved) |
| Objectives/Metrics | OBJ-001 – 006; MET-001 – 007 | Sử dụng cùng ID | Sử dụng MET-006/OBJ-006 | Nhất quán |
| Business requirements | — | BR-001 – BR-042 | Trace đủ 42 BR | Nhất quán |
| Business rules | — | BRL-001 – BRL-034 | Tham chiếu BRL; có SYS-RL | Nhất quán (F-09 resolved) |
| Processes | — | BP-001 – BP-007 | Có BP → UC mapping | Nhất quán |
| Roles/permissions | Stakeholder summary | Source STK | ROLE-001 – 004 + Checker | Nhất quán (F-05/F-06 resolved) |
| Constraints | CON-001 – 010 | CON-001 – 010 | CON-001 – 010 + TC/RC/IC/OC | Nhất quán nội dung |
| Assumptions/Dependencies | ASM-001 – 009; DEP-001 – 005 | Cùng ID | Cùng ID | Nhất quán |
| Open issues | — | OI-001 – 006 | OI-001 – 006 | Nhất quán |
| Glossary | Glossary chính | Tham chiếu 01 | Tham chiếu 01/02 + bổ sung | Có lỗi nhỏ ở glossary SRS |
| Demo/acceptance | Demo bắt buộc | BR-042 | AC-031/032 + D1–D5 | Nhất quán |

---

## 4. Findings

### 4.1. High

#### F-01 – STK-006 vẫn còn trong 01. Introduction

| Trường | Nội dung |
|---|---|
| Tài liệu | `docs-common/01. Introduction.md` |
| Vị trí | Mục 5.3 Communication and Engagement Needs, dòng 265; Stakeholder Catalog dòng 236–242 |
| Vấn đề | Catalog chỉ có STK-001 – STK-005, nhưng bảng Communication vẫn còn dòng `STK-006`. 02 và 03 không còn STK-006. |
| Ảnh hưởng | Phá vỡ traceability stakeholder; trái với quyết định “loại bỏ STK-006”; có thể gây nhầm lẫn khi lập RACI/approval. |
| Khuyến nghị | Xóa dòng STK-006 khỏi 01; nếu nhu cầu báo cáo tiến độ/demo vẫn cần, gán cho STK-002 hoặc STK-005. Nếu thực sự cần stakeholder thứ 6, phải bổ sung lại catalog và cập nhật 02/03. |
| Trạng thái | **Resolved** – 01 không còn STK-006; catalog/communication dùng STK-001 – STK-005 |
| Mức | High (Resolved) |

#### F-02 – Transfer step-up authentication chưa được đặc tả đủ

| Trường | Nội dung |
|---|---|
| Tài liệu | `02. BRD` BR-002; `03. SRS` FR-026, UC-005/UC-006, mục 6.2, 6.3, 6.4, 10.1, 11.1 |
| Vấn đề | BR-002 yêu cầu OTP cho giao dịch rủi ro cao/vượt ngưỡng. SRS có FR-026 nhưng chưa có: (1) use case/alternate flow riêng cho transfer step-up; (2) API response/error khi cần OTP; (3) mã lỗi `OTP_REQUIRED`/`STEP_UP_REQUIRED`; (4) AC riêng cho giao dịch vượt ngưỡng cần OTP; (5) nhánh step-up trong sequence diagram. |
| Ảnh hưởng | Một yêu cầu `Must` có thể không được triển khai hoặc không được kiểm thử đúng; traceability BR-002 mới chỉ dừng ở AC-003 (new-device OTP). |
| Khuyến nghị | Bổ sung use case “Transfer step-up authentication” hoặc alternate flow trong UC-005/006; định nghĩa response `OTP_REQUIRED`/`STEP_UP_REQUIRED`; thêm AC cho transfer vượt ngưỡng; cập nhật sequence diagram và traceability BR-002. |
| Trạng thái | **Resolved** – đã bổ sung UC-015, US-009, AC-033, `STEP_UP_REQUIRED` (428), `/auth/step-up/verify`, SYS-RL-026 và nhánh sequence; BR-002 trace tới UC-015/AC-033 |
| Mức | High (Resolved) |

#### F-03 – Audit-write failure và quan hệ atomic giữa tiền và audit chưa rõ

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 2.13.2; Appendix A `AUDIT_WRITE_FAILED` |
| Vấn đề | SRS yêu cầu “không trả COMPLETED nếu audit record chưa được ghi nhận bền vững”, nhưng chưa định nghĩa điều gì xảy ra nếu debit/credit đã thành công mà audit write thất bại. Appendix A chỉ ghi “retry/reconcile”, chưa có trạng thái/compensation/AC. |
| Ảnh hưởng | Có thể phát sinh tiền đã bị trừ nhưng giao dịch không COMPLETED và không có audit; vi phạm mục tiêu audit/traceability và consistency. |
| Khuyến nghị | Đặc tả rõ một trong hai hướng: (a) audit write nằm trong cùng đơn vị atomic/outbox với money movement; hoặc (b) nếu audit fail, transfer giữ PENDING/FAILED và có compensation/reconciliation bắt buộc. Thêm AC cho audit-write failure và cập nhật mục 8.4/Appendix A. |
| Trạng thái | **Resolved** – 2.13.2 định nghĩa rõ; AC-034 bổ sung; sequence có nhánh audit failure; 8.4/Appendix A/D3 cập nhật |
| Mức | High (Resolved) |

### 4.2. Medium

> Tất cả F-04 – F-10 đã được resolved trong remediation; nội dung dưới đây là bản ghi lịch sử. Xem mục 11.

#### F-04 – ID convention trung tâm chưa cập nhật các prefix mới

| Trường | Nội dung |
|---|---|
| Tài liệu | `01. Introduction` mục 8.1; `02. BRD` dùng `OI-`; `03. SRS` dùng `DAR-`, `DIR-`, `TC-`, `RC-`, `IC-`, `OC-`, `AUTH-`, `OI-` |
| Vấn đề | Bảng ID convention của 01 chỉ có OBJ/MET/STK/BR/BP/BRL/CON/ASM/DEP/RSK/ROLE/FR/NFR/UC/US/INT/DR/AC/SYS-RL. Các prefix mới chưa được khai báo tập trung; `OI-` cũng chưa có trong 01. |
| Ảnh hưởng | Khó quản lý ID, dễ trùng hoặc hiểu sai giữa các tài liệu. |
| Khuyến nghị | Bổ sung `OI-`, `DAR-`, `DIR-`, `TC-`, `RC-`, `IC-`, `OC-`, `AUTH-` vào 01 mục 8.1, hoặc tuyên bố rõ đây là prefix cục bộ của 02/03 và ghi owner. |
| Mức | Medium |

#### F-05 – User classes trong SRS lẫn với project stakeholders

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 1.4 và 4.1 |
| Vấn đề | Mục 1.4 liệt kê Business Owner (STK-002) và Development/QA (STK-005) như “User Classes”, nhưng mục 4.1 chỉ định nghĩa 4 role hệ thống: Customer, Operations, Admin, Auditor. STK-002/005 là stakeholder/dự án, không phải người dùng hệ thống. |
| Ảnh hưởng | Role model không nhất quán; permission matrix không bao phủ các “user class” này. |
| Khuyến nghị | Đổi tên mục 1.4 thành “User classes and project stakeholders”; tách STK-002/005 khỏi user classes; giữ 4 role hệ thống. |
| Mức | Medium |

#### F-06 – Permission matrix, API catalog và UC actors không khớp

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 4.2, 5.1, 6.2 |
| Vấn đề | Matrix cho Admin/Auditor quyền read ở nhiều màn hình, nhưng API catalog/UC actors hẹp hơn. Ví dụ: Admin có R ở audit trail nhưng `GET /audit` chỉ ghi Operations/Auditor; Admin/Auditor có R ở fraud flag nhưng `GET /fraud-flags` chỉ ghi Operations; Admin có R ở account/transaction nhưng một số endpoint không liệt kê Admin. |
| Ảnh hưởng | Có thể triển khai sai quyền hoặc test sai; vi phạm least privilege/role consistency. |
| Khuyến nghị | Chọn permission matrix làm nguồn chính thức và cập nhật API catalog/UC actors tương ứng; hoặc thu hẹp matrix nếu Admin/Auditor không thực sự cần quyền read. |
| Mức | Medium |

#### F-07 – Lỗi traceability NFR

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 10.2, 11.1, 11.3 |
| Vấn đề | (1) BR-003 (lockout/session) đang map tới NFR-016 (OTP security) — không đúng; NFR-016 thuộc BR-002/BR-034. (2) NFR-021 (audit immutability) xuất hiện ở cả dải Security NFR-013–NFR-021 và Auditability NFR-021–NFR-023. |
| Ảnh hưởng | Traceability gây hiểu sai; kiểm chứng security/audit bị chồng lấn. |
| Khuyến nghị | Bỏ NFR-016 khỏi BR-003; đổi Security range thành NFR-013–NFR-020; giữ NFR-021 ở Auditability; cập nhật mục 10.2 và 11.3. |
| Mức | Medium |

#### F-08 – Out-of-scope và OI-005 chưa đồng bộ

| Trường | Nội dung |
|---|---|
| Tài liệu | `02. BRD` mục 4.14 và OI-005; `03. SRS` mục 1.1 và 11.4 |
| Vấn đề | 02 xếp cancellation/reversal vào out-of-scope nhưng OI-005 vẫn hỏi có cần trong MVP không. 03 mục 1.1 không liệt kê cancellation/reversal, fees/interest/FX; 03 OI-005 lại ghi “State machine, phạm vi out-of-scope”. |
| Ảnh hưởng | Phạm vi và state machine có thể phải thay đổi nếu OI-005 được trả lời khác. |
| Khuyến nghị | Chốt OI-005. Nếu out-of-scope, ghi rõ trong 03 mục 1.1 và đảm bảo state machine không có CANCELLED/REVERSED. Nếu in-scope, bổ sung FR/AC/state. Đồng thời bổ sung fees/interest/FX vào out-of-scope của 03 hoặc tham chiếu 02 mục 4.14. |
| Mức | Medium |

#### F-09 – Thiếu mapping BRL → SYS-RL cho một số rule

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 8.2, 8.3 |
| Vấn đề | BRL-018/019/020 (history read-only/display/detail) và BRL-033 (data classification) chưa có SYS-RL tương ứng; FR-027/028/030/052 có tồn tại nhưng mapping rule → enforcement chưa đầy đủ. |
| Ảnh hưởng | Traceability BRL → SYS-RL/FR chưa kín; dễ bỏ sót test rule. |
| Khuyến nghị | Bổ sung SYS-RL cho history và data classification, hoặc thêm cột/ghi chú map trực tiếp các BRL này tới FR tương ứng. |
| Mức | Medium |

#### F-10 – Recovery sequence diagram thiếu nhánh manual review

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 6.4.2 so với 2.13.2 và UC-014 |
| Vấn đề | Diagram chỉ có COMPLETED/FAILED; không có nhánh “không thể đối soát tự động → giữ PENDING, cảnh báo, chuyển vận hành” đã mô tả trong 2.13.2/UC-014. |
| Ảnh hưởng | Có thể khiến implementation giả định luôn tự động resolve; không nhất quán với AC-013. |
| Khuyến nghị | Thêm nhánh `else Cannot reconcile -> keep PENDING + alert/manual review` vào sequence diagram; cập nhật D3/AC-013 nếu cần. |
| Mức | Medium |

### 4.3. Low

#### F-11 – Glossary SRS trùng `DIR` và thiếu `OI`

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` Appendix B |
| Vấn đề | `DIR` xuất hiện hai lần: “Data integrity rule” và “Data Integrity Rule (SRS-specific)”. `OI` được dùng trong 11.4 nhưng không có định nghĩa; 01 cũng chưa có OI. |
| Khuyến nghị | Xóa một dòng DIR; thêm định nghĩa `OI`; cập nhật 01 mục 8.1. |
| Mức | Low |

#### F-12 – UC-005/006 đánh dấu notification/receipt như bắt buộc

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 5.2 |
| Vấn đề | Main flow bước 10 ghi “Gửi notification và trả receipt”, nhưng FR-024 (receipt) và FR-041 (notification) là `Should`. Nếu defer, UC postconditions/AC có thể không đạt. |
| Khuyến nghị | Ghi rõ “notification/receipt nếu được cấu hình/không defer” hoặc tách khỏi main flow bắt buộc; giữ AC-014/AC-025 là Should. |
| Mức | Low |

#### F-13 – Thiếu document control/version/status thống nhất

| Trường | Nội dung |
|---|---|
| Tài liệu | 01, 02, 03 |
| Vấn đề | 01 có project/date; 02/03 có project/title; không có version history/status/approval thống nhất ở đầu tài liệu (02/03 có approval table ở cuối nhưng không có version). |
| Khuyến nghị | Thêm khối Document Control/Version/Status ngắn ở đầu mỗi tài liệu hoặc một register chung; không chặn nội dung nhưng cần cho quản trị thay đổi. |
| Mức | Low |

#### F-14 – Rủi ro idempotency key hết hạn sau 24 giờ

| Trường | Nội dung |
|---|---|
| Tài liệu | `03. SRS` mục 2.13.1/UC-007; `02. BRD` BRL-013 |
| Vấn đề | Key hết hạn được coi là yêu cầu mới; nếu client retry sau 24h mà không query reference trước, có thể tạo giao dịch trùng. Đây là hệ quả của retention 24h đang là đề xuất. |
| Khuyến nghị | Giữ khuyến nghị query theo reference trước khi tạo mới; xem xét tăng retention hoặc dùng request ID dài hạn; chốt tại OI-001. |
| Mức | Low |

---

## 5. ID Integrity

| Kiểm tra | Kết quả | Ghi chú |
|---|---|---|
| ID duy nhất trong từng tài liệu | Pass | BR, BRL, FR, NFR, UC, US, AC, SYS-RL... không trùng trong định nghĩa |
| Dải ID đầy đủ | Pass | BR-001–042; BRL-001–034; FR-001–053; NFR-001–032; AC-001–032; UC-001–014; US-001–008; SYS-RL-001–025 |
| Không còn ID đã loại bỏ | Pass | STK-006 không còn trong 01/02/03 (F-01 resolved) |
| Prefix convention cross-document | Pass | 01 đã khai báo OI/DAR/DIR/TC/RC/IC/OC/AUTH (F-04 resolved) |
| Không trùng định nghĩa glossary | Pass | `DIR` chỉ còn một định nghĩa; `OI` đã bổ sung (F-11 resolved) |
| Mapping NFR chính xác | Pass | BR-003 không còn map NFR-016; NFR-021 chỉ còn ở Auditability (F-07 resolved) |
| Không có orphan requirement | Pass | Các FR/NFR/AC đều có liên kết |
| Traceability BR → FR/NFR/AC | Pass | 42/42 BR có ít nhất một liên kết |
| Traceability BRL → SYS-RL/FR | Pass | SYS-RL-027/028 đã bổ sung (F-09 resolved) |

---

## 6. Traceability Audit

| Kiểm tra | Kết quả | Chi tiết |
|---|---|---|
| 42 BR xuất hiện trong SRS matrix | Pass | Đủ BR-001 – BR-042 |
| Mỗi BR có FR/NFR/AC | Pass | BR-029/031/032 dùng NFR; BR-042 dùng AC/demo |
| Mỗi FR có Source BR | Pass | Tất cả FR có cột Source BR |
| Mỗi UC map tới FR | Pass | 14/14 UC |
| Mỗi US map tới FR | Pass | 8/8 US |
| Mỗi BP map tới UC | Pass | Đã có bảng BP → UC |
| Mỗi AC map tới FR/NFR | Pass | 32/32 AC |
| Mỗi NFR map tới BR/CON | Pass | Có cột Source |
| Step-up transfer có AC riêng | Pass | UC-015/AC-033/STEP_UP_REQUIRED/SYS-RL-026 đã bổ sung (F-02 resolved) |
| Audit-write failure có AC | Pass | AC-034 + behavior 2.13.2 + sequence branch (F-03 resolved) |
| NFR-021 không trùng dải | Pass | Security dùng NFR-013 – NFR-020; Auditability dùng NFR-021 – NFR-023 (F-07 resolved) |
| Permission matrix ↔ API ↔ UC actors | Pass | API/UC actors đã bổ sung Admin/Auditor read; auth endpoint dùng All roles (F-06 resolved) |
| BRL → SYS-RL/FR | Pass | SYS-RL-027/028 bổ sung (F-09 resolved) |
| Recovery manual branch | Pass | Sequence diagram có nhánh manual review (F-10 resolved) |

---

## 7. Nội dung trùng lặp và thiếu

### 7.1. Trùng lặp có thể chấp nhận

- 01 và 02 cùng có objectives/stakeholders: 01 tóm tắt, 02 phân tích chi tiết.
- 02 và 03 cùng có business rules: 02 sở hữu BRL, 03 tham chiếu và bổ sung SYS-RL — đúng chủ đích.
- 02 và 03 cùng có CON/ASM/DEP: 03 lặp lại để độc lập khi đọc; chấp nhận được.
- 03 có 6.3 Error Codes và Appendix A: Appendix A ghi rõ là bổ sung; chấp nhận được.
- 03 có 10.2 NFR Acceptance Summary và 11.3 NFR Verification Matrix: khác mục đích; chấp nhận được.

### 7.2. Trùng lặp cần xử lý

- `DIR` trùng trong 03 Appendix B (F-11).
- NFR-021 nằm ở hai dải NFR trong 10.2/11.3 (F-07).
- US acceptance criteria trong 5.4 và AC table trong 10.1 có nội dung gần nhau; có thể giữ nếu US AC được xem là bản tóm tắt, nhưng nên ghi rõ AC table là nguồn chính thức.

### 7.3. Thiếu sót chính

> Tất cả High/Medium/Low trong danh sách dưới đây đã được xử lý; xem mục 10–12.

- Use case/AC/API/error cho transfer step-up (F-02).
- AC và behavior cho audit-write failure (F-03).
- Mapping BRL-018/019/020/033 → SYS-RL/FR (F-09).
- Document control/version/status thống nhất (F-13).
- Định nghĩa OI trong glossary/ID convention (F-11).
- Đồng bộ out-of-scope và OI-005 (F-08).

---

## 8. Kế hoạch hành động đề xuất

> Các hành động P1/P2/P3 đã được thực hiện; xem mục 10–12.

| Ưu tiên | Finding | Hành động | Owner đề xuất |
|---|---|---|---|
| P1 | F-01 | Xóa/điều chỉnh STK-006 trong 01; xác nhận stakeholder cuối | BA |
| P1 | F-02 | Bổ sung use case/AC/API/error/sequence cho transfer step-up | BA + Tech Lead + QA |
| P1 | F-03 | Chốt behavior audit-write failure và atomicity; thêm AC | BA + Tech Lead + Audit |
| P2 | F-04 | Cập nhật ID convention 01 với OI/DAR/DIR/TC/RC/IC/OC/AUTH | BA |
| P2 | F-05 | Tách user classes khỏi project stakeholders trong 03 | BA |
| P2 | F-06 | Đồng bộ permission matrix ↔ API catalog ↔ UC actors | BA + Tech Lead |
| P2 | F-07 | Sửa BR-003 → NFR-016; tách NFR-021 khỏi Security range | BA |
| P2 | F-08 | Chốt OI-005; đồng bộ out-of-scope 02/03 | BA |
| P2 | F-09 | Bổ sung SYS-RL/ghi chú mapping BRL-018/019/020/033 | BA + Tech Lead |
| P2 | F-10 | Thêm nhánh manual review vào recovery sequence | BA + Tech Lead |
| P3 | F-11 | Xóa DIR trùng; thêm OI glossary | BA |
| P3 | F-12 | Đánh dấu notification/receipt là Should/conditional trong UC | BA |
| P3 | F-13 | Thêm document control/version/status | BA |
| P3 | F-14 | Xác nhận idempotency retention/chiến lược query reference | BA + Tech Lead |

---

## 9. Khuyến nghị sign-off

**Trạng thái:** **Ready for sign-off.**

- 3 High, 7 Medium và 4 Low đã resolved.
- Không còn finding mở.
- Đề nghị sign-off và cập nhật Reviewer/Approver trong Document Control.

---

## 10. Phase 4 Re-check sau remediation

### 10.1. Phạm vi re-check

Chỉ kiểm tra lại các mục High đã sửa: **F-01, F-02, F-03**.

### 10.2. Kết quả

| Finding | Fix applied | Verification | Status |
|---|---|---|---|
| F-01 – STK-006 | 01 đã bỏ STK-006; communication dùng STK-001 – STK-005 | `grep STK-006` trên `docs-common` = 0; catalog/communication khớp | **Resolved** |
| F-02 – Transfer step-up | Thêm UC-015, US-009, AC-033, `STEP_UP_REQUIRED` (428), `/auth/step-up/verify`, SYS-RL-026, nhánh sequence; cập nhật FR-004/026/042/043 và traceability BR-002 | UC-015/AC-033/STEP_UP_REQUIRED/SYS-RL-026 hiện diện; BR-002 trace tới UC-015/AC-033; AC-033 có Given-When-Then | **Resolved** |
| F-03 – Audit-write failure | 2.13.2 định nghĩa behavior; AC-034; nhánh audit failure trong sequence; 8.4/Appendix A/D3 cập nhật | AC-034 hiện diện; `AUDIT_WRITE_FAILED` có behavior; sequence có nhánh audit failure; trace BR-015/BR-024 → AC-034 | **Resolved** |

### 10.3. Cập nhật số lượng artefact

| Artefact | Trước remediation | Sau remediation |
|---|---|---|
| FR | 53 | 53 |
| NFR | 32 | 32 |
| UC | 14 | 15 (thêm UC-015) |
| US | 8 | 9 (thêm US-009) |
| AC | 32 | 34 (thêm AC-033, AC-034) |
| SYS-RL | 25 | 26 (thêm SYS-RL-026) |

### 10.4. Các mục còn mở

- **Medium:** F-04 – F-10 (ID convention, user classes/roles, permission matrix/API, NFR mapping, out-of-scope/OI-005, BRL mapping, recovery diagram).
- **Low:** F-11 – F-14 (glossary DIR/OI, UC notification/receipt, document control/version, idempotency 24h).
- **Kết luận re-check:** 3/3 High đã resolved. Medium và Low được xử lý ở mục 11–12; trạng thái cuối là **Ready for sign-off**.

---

## 11. Phase 4 Re-check – Medium remediation

### 11.1. Phạm vi re-check

Chỉ kiểm tra lại các mục Medium đã sửa: **F-04 – F-10**.

### 11.2. Kết quả

| Finding | Fix applied | Verification | Status |
|---|---|---|---|
| F-04 – ID convention | 01 mục 8.1 bổ sung OI/DAR/DIR/TC/RC/IC/OC/AUTH | Các prefix hiện diện trong bảng ID convention; 02/03 dùng đúng prefix | **Resolved** |
| F-05 – User classes | 03 mục 1.4 chỉ còn 4 user class; thêm ghi chú STK-002/STK-005 là project stakeholders | 4 user class khớp 4 role; không còn Business Owner/Development-QA trong user class | **Resolved** |
| F-06 – Permission/API/UC | API catalog và UC actors bổ sung Admin/Auditor read; auth endpoint dùng `All roles` | Các endpoint read và UC actor list khớp permission matrix | **Resolved** |
| F-07 – NFR mapping | Bỏ NFR-016 khỏi BR-003; Security range đổi thành NFR-013 – NFR-020; AC-030 cập nhật | Không còn `NFR-013 – NFR-021`; BR-003 không còn NFR-016; NFR-021 chỉ ở Auditability | **Resolved** |
| F-08 – Out-of-scope/OI-005 | 03 mục 1.1 bổ sung cancellation/reversal, phí/lãi/tỷ giá, UI/UX chi tiết, đa ngôn ngữ; 02/03 OI-005 ghi rõ hiện out-of-scope | Out-of-scope 02/03 đồng bộ; OI-005 không còn mâu thuẫn | **Resolved** |
| F-09 – BRL mapping | Thêm SYS-RL-027 (history/read-only) và SYS-RL-028 (data classification) | BRL-018/019/020/033 đã có mapping; 8.3 có nhóm History/read-only và Data classification | **Resolved** |
| F-10 – Recovery diagram | 6.4.2 bổ sung nhánh `Cannot reconcile automatically → Keep PENDING + alert/manual review` | Sequence diagram khớp 2.13.2/UC-014 | **Resolved** |

### 11.3. Cập nhật artefact

| Artefact | Sau High remediation | Sau Medium remediation |
|---|---|---|
| FR | 53 | 53 |
| NFR | 32 | 32 |
| UC | 15 | 15 |
| US | 9 | 9 |
| AC | 34 | 34 |
| SYS-RL | 26 | 28 (thêm SYS-RL-027/028) |

### 11.4. Các mục còn mở

- **Low:** F-11 – F-14 đã được xử lý trong mục 12.
- **Kết luận re-check:** 7/7 Medium đã resolved. Sau Low remediation (mục 12), bộ tài liệu đạt **Ready for sign-off**.

---

## 12. Phase 4 Re-check – Low remediation

### 12.1. Phạm vi re-check

Chỉ kiểm tra lại các mục Low đã sửa: **F-11 – F-14**.

### 12.2. Kết quả

| Finding | Fix applied | Verification | Status |
|---|---|---|---|
| F-11 – Glossary SRS | Xóa `DIR` trùng; thêm định nghĩa `OI` | SRS Appendix B chỉ còn một `DIR`; `OI` hiện diện; 01 đã có OI prefix | **Resolved** |
| F-12 – UC notification/receipt | UC-005/006 main flow và postconditions ghi rõ notification/receipt là Should; thêm ghi chú AC-014/AC-025 | UC-005/006 không còn bắt buộc notification/receipt; Must AC vẫn độc lập | **Resolved** |
| F-13 – Document control | Thêm Document code/Version/Status/Date/Author/Reviewer cho 01/02/03 | Cả 3 tài liệu có cùng cấu trúc Document Control | **Resolved** |
| F-14 – Idempotency 24h | SRS 2.13.1/UC-007 yêu cầu query reference trước khi tạo mới; BRD BRL-013 ghi rõ TTL 24h là đề xuất và hướng chống trùng dài hạn | Risk được ghi rõ; client flow/mitigation hiện diện; OI-001 theo dõi TTL | **Resolved** |

### 12.3. Kết luận cuối

- **High: 0**, **Medium: 0**, **Low: 0**.
- Không còn finding mở trong phạm vi Phase 4.
- Bộ tài liệu 01–03 đạt trạng thái **Ready for sign-off**.
- Việc còn lại: điền Reviewer/Approver thực tế và ký sign-off.

---

**End of Review Report**
