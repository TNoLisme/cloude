# FE/BE — Contract, E2E, k6, CI Frontend

**Người phụ trách:** FE/BE integration
**Reviewer:** BE-4 + module owner liên quan
**Mục tiêu:** FE dùng backend thật, xong browser 3 role, xong k6 + CI FE/OpenAPI + routing. Không sửa BE để UI pass.

---

## 1. Vai trò

FE/BE là **owner duy nhất** frontend integration + k6 HTTP scenario + CI frontend. Không sửa `be/src/main/**` để UI pass. Backend lỗi thì báo BE owner, không fallback mock âm thầm. k6 dataset/invariant query lấy từ BE-2, không tự đoán số dư.

## 2. Phạm vi

### Được sửa

```text
frontend/src/api/**
frontend/scripts/**
frontend/package.json
frontend/vite.config.ts
frontend/src/** tests
tests/load/banking-mvp.js (tạo mới)
.github/workflows/frontend-contract.yml (tạo mới: OpenAPI + FE)
docs/projects/backend-mvp/evidence/FEBE-*.md
contracts/openapi.yaml (proposal only)
```

### Không được sửa

- `be/src/main/**` business logic, migration, repository/service.
- Generated OpenAPI output bằng tay.
- Contract chưa approved.

Mock chỉ bật chủ động cho unit/component. Flow E2E/k6 bắt buộc backend thật + disposable PG.

## 3. Đầu vào

1. `contracts/openapi.yaml` hiện tại + generated types. Drift là dừng, báo BE-4.
2. Handoff từ BE-1: auth endpoint/cookie/CSRF, OTP TTL/attempt, mailbox guard, synthetic credentials qua env.
3. Handoff từ BE-2: account DTO, status matrix, lookup XOR, seed key/replay, block reason, dataset synthetic + invariant query.
4. Handoff từ BE-3: transfer state machine, 5M boundary, key/retry/timeout unknown, error matrix, Idempotency-Replayed.
5. Handoff từ BE-4: error/role matrix chốt, screenshot policy, CI artifact path.

## 4. Dependencies and handoff contracts

| FE/BE work | Depends on | Required input | Independent work if input missing |
|---|---|---|---|
| Contract gate, typecheck, build, FE CI | Current frozen OpenAPI | spec path, generated types and validator command | Fully proceeds; report drift, do not edit generated output by hand |
| Customer auth E2E | BE-1 | synthetic credentials via env, OTP mailbox guard, cookie/CSRF sequence | Build non-secret test skeleton; execution waits for valid local setup |
| Account/operator E2E | BE-2 | synthetic customers/accounts, known balances, seed/block error matrix | Build selectors/assertion skeleton; no invented account state |
| Transfer E2E and unknown outcome | BE-3 | state/error matrix, retry key rule, reconcile route | Contract tests continue; behavior run waits for stable API |
| k6 run | BE-1 + BE-2 + BE-3 | auth setup + seed SQL + expected balances/invariant query | Script structure can be prepared; load run blocked until disposable dataset exists |
| Final evidence | BE-4 | evidence schema, image/CI report links, status vocabulary | Keep raw results; do not mark acceptance without BE-4 review |

Contract change protocol: stop only affected flow, open proposal with operation/schema diff and consumer impact, wait for BE-4 + module owner approval, regenerate types from approved OpenAPI, rerun affected gates. No shared FE/BE/BE implementation edits.

## 5. Quy trình

### Bước 0 — Dựng nền disposable + guard secret (Tiên quyết — Chuẩn bị môi trường)

1. Tạo disposable PG (không dùng DB giữ lại). Seed theo file BE-2.
2. Demo profile guard + env credentials. Không ghi secret vào Git/md/screenshot/video/storage.
3. Mailbox guard: local/demo only.
4. Check base path không lặp `/api/v1`, `credentials:include`, token memory-only, key stable.
Done khi `api:smoke` pass trên nền disposable.

### Gói A — Contract gate (Làm ngay, độc lập)

1. Chạy:
```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm run api:check
npm run typecheck
npm test
npm run build
npm run api:smoke
cd ..
python be/scripts/validate-openapi.py
```
2. Check tay: types match contract, CSRF đúng refresh/logout, không auto-retry unsafe (đặc biệt POST transfer), Problem.code drive UI, 204 không parse JSON.
3. Drift → dừng integration, ghi operation/schema lệch, báo BE-4 + owner. Không sửa generated tay.
4. Output `FEBE-contract-gate.md`: command + output + drift list (none hoặc chi tiết).

### Gói B — Browser E2E 3 role (Sau khi qua Contract Gate)

Dataset: Customer A/B active + balance known (lấy BE-2), Operator, Auditor. Screenshots chỉ synthetic.

Customer 20 bước:
```text
register OTP/proof → login phone → PIN setup → account/balance →
resolve dest → transfer 5M (small) → transfer 5M+1 (OTP) →
wrong OTP 1 lần → valid OTP → replay cùng key →
expiry case → history list → detail source/dest →
recovery SMS → recovery Email → change PIN → reset PIN → logout
```
Mỗi bước: action + expected UI + API status + screenshot synthetic.

Operator:
```text
login → lookup phone → lookup email → no/both filter chặn →
counter create OTP → seed → replay key (không credit 2) →
block reason → Customer transfer reject check →
unblock → refetch server (không cache cũ) → masked check →
cancel action không mutation
```

Auditor:
```text
login → audit filter/cursor → seed/block/transfer/recovery events →
risk large/frequency rows → không có nút mutate →
direct Customer/Operator route denied → logout clear cache
```

Unknown-outcome (bắt buộc, làm cùng BE-3):
- Timeout sau commit: UI không false-fail, giữ key/body retry, detail reconcile, không optimistic tạo K2, không duplicate, confirm dùng ID cũ.
- Ghi video/log nếu có.

Output `FEBE-e2e-matrix.md` + screenshots. Fail bước nào ghi exact API response + UI state, báo BE owner đúng module.

### Gói C — k6 + CI FE (Sau khi nhận dataset từ BE-2)

1. Tạo `tests/load/banking-mvp.js`:
- 50 VU, 20 RPS steady, 10 phút. Transfers chính ≤5M để OTP không dominate latency. Step-up >5M riêng tùy chọn với mailbox.
- Setup: health → login/session (token pool) → account read → resolve → small transfer → history → replay sample.
- Phân loại business 4xx vs unexpected 5xx. Check status/code + correlation + replay header.
- Teardown: final balance query (lấy từ BE-2) + duplicate row/debit/credit check.
- Chạy:
```powershell
k6 run tests/load/banking-mvp.js
```
2. Target: transfer p95 ≤500ms, balance p95 ≤500ms, 0 invariant violation, 0 duplicate. Fail ghi số thật + bottleneck (BE CPU/RAM, DB connections, lock waits), không nâng threshold che.
3. Tạo `.github/workflows/frontend-contract.yml`: openapi validate + `api:check`; `npm ci`, typecheck, test, build; upload artifact không secret; drift làm fail.
4. Same-origin check:
- Local `127.0.0.1:5173/api → localhost:8080/api`: cookie gửi, CSRF accept, SPA fallback, health tới BE, không wildcard CORS credential.
- Cloud `bank.example.com/ + /api/*` (khi có): tương tự. Cross-origin chỉ pass khi test exact CORS + SameSite=None/Secure + HTTPS + browser thật.
5. Output: k6 script + `FEBE-k6-report.md` (version, commit, machine/Docker shape, PG version/config, VU/RPS/mix, p50/p95/p99, throughput, 4xx/5xx, CPU/RAM/conn/lock, invariant, duplicate) + CI run xanh.

## 6. Lệnh nghiệm thu

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\frontend
npm run api:check
npm run typecheck
npm test
npm run build
npm run api:smoke
k6 run ../tests/load/banking-mvp.js
cd ..
python be/scripts/validate-openapi.py
git diff --check
```

## 7. Tiêu chí đạt

- [ ] Contract/build/smoke pass, drift none hoặc có proposal.
- [ ] 3-role E2E pass hoặc skipped rõ lý do + evidence.
- [ ] Unknown-outcome pass (không false-fail, không duplicate).
- [ ] k6 report đủ p50/p95/p99 + throughput + error class + resource + invariant + duplicate.
- [ ] CI FE/OpenAPI pass.
- [ ] Screenshots synthetic, không secret/PII thật.
- [ ] Responsive 360/768/1024/1440 note + a11y limitations.

## 8. Nghiệm thu

- BE-4 check: chạy lại lệnh mục 6, so report. k6 thiếu invariant/resource là fail.
- BE-1 check auth flow/cookie/CSRF đúng. BE-2 check dataset/invalidation. BE-3 check transfer/timeout matrix.
- Fail nếu: sửa BE để UI pass, mock trong E2E/k6, nâng threshold che bottleneck, screenshot dính secret.

## 9. Bàn giao

Cho BE-4: command/output, E2E matrix, k6 path/version/report, CI path/run/artifact, screenshots, limitations, mismatch list, a11y/perf note.
Cho BE-1: auth setup thực tế, mailbox guard, credential env, recovery/PIN prerequisites.
Cho BE-2: seed fixtures dùng thật, lookup fixtures, DB metric fields cần thêm, balance dataset kết quả.
Cho BE-3: transfer dataset, expected balances, timeout hook dùng thật, invariant query, OTP prerequisites.
Nhận từ BE: dataset + error matrix + state machine. Thiếu là mark BLOCKED, không tự bịa data.

## 10. Cấm

Không sửa BE. Không commit secret/OTP/token. Không E2E/k6 trên DB giữ lại. Không wildcard CORS + credential. 1 PR 1 gói.
