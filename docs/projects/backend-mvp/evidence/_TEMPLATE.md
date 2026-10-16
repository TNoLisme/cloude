# Evidence template

Copy file này cho mỗi task: `BE4-<ten-task>.md`, `BEA-*.md`, `BE3-*.md`, hoặc `FEBE-*.md` theo owner. Không dùng template làm bằng chứng pass; status chỉ đổi theo output kiểm chứng.

```markdown
# Task evidence

## Status
PLANNED | IMPLEMENTED | VERIFIED | BLOCKED | NOT_ACCEPTED

## Owner and scope
- Owner:
- In scope:
- Out of scope:

## Files
- Changed:
- Contract impact: none | proposal/link
- DB impact: none | migration proposal
- Security/role impact:

## Verification
- Environment/profile/database isolation:
- Exact command:
- Expected:
- Actual:
- Result:
- Tool/version where relevant:

## Evidence
- Logs/report/screenshots (synthetic data only):

## Dependencies
- Provider + exact input:
- Blocked item, if any:

## Handoff
- Consumer:
- API/DTO/state/error details:
- Next owner/action:

## Limitations
- Known gaps:
```

## Merge gate 10 điểm

Mỗi PR phải nêu đủ:

1. Owner + scope rõ.
2. Test + exact command.
3. Pass/fail/skipped thật.
4. OpenAPI none hoặc diff đã approve.
5. DB/migration none hoặc V mới forward-only.
6. Role impact.
7. Money/idempotency impact.
8. FE handoff note.
9. Secret/log review.
10. Không churn unrelated.

Thiếu 1 điểm thì request changes, không merge.
