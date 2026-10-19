# Transactions Module — Documentation Harness

> **Phạm vi:** Module `transfer` trong Digital Banking Simulator.
> Tài liệu này là harness độc lập do BE-3 owner xây dựng.
> Chỉ tham chiếu `docs_draft` để ghép nối contract với các module khác; không chỉnh sửa tài liệu của team.

---

## Cấu trúc

```text
docs transactions/
├── README.md                  ← File này — index tổng quan
├── plans/
│   ├── README.md              ← Mục lục và quy ước của harness
│   ├── 0. introduction.md    ← Tổng quan module, mục tiêu, phạm vi
│   ├── 1. ERD.md             ← Schema bảng transfers + quan hệ
│   ├── 2. API docs.md        ← Contract HTTP chi tiết cho module transfer
│   ├── 3. Architecture.md    ← Kiến trúc nội bộ, state machine, concurrency
│   ├── 4. Deployment Infrastructure.md  ← Infra, observability, CI/CD
│   └── 5. Timelines.md       ← 5-tuần delivery plan
└── week1/                     ← Artifact và evidence tuần 1 (trống, sẽ bổ sung)
```

## Tài liệu tham chiếu (read-only, không sửa)

| Tài liệu draft | Mục đích tham chiếu |
|---|---|
| `docs_draft/baseline/mvp-requirements-and-architecture.md` | FR-05, FR-06, module boundaries, consistency boundary |
| `docs_draft/baseline/api-and-team-contract.md` | OpenAPI contract, error codes, idempotency rules |
| `docs_draft/projects/backend-mvp/05-transfers.md` | Phase 05 design detail |
| `docs_draft/projects/backend-mvp/13-be-3-transfer-financial-consistency.md` | BE-3 task scope |
| `docs_draft/projects/backend-mvp/database-architecture-design.md` | Schema `transfers`, `idempotency_records`, `otp_challenges` |
| `contracts/openapi.yaml` | HTTP authority (normative) |

## Quick links

- [Introduction](./plans/0.%20introduction.md)
- [ERD](./plans/1.%20ERD.md)
- [API Docs](./plans/2.%20API%20docs.md)
- [Architecture](./plans/3.%20Architecture.md)
- [Deployment & Infrastructure](./plans/4.%20Deployment%20Infrastructure.md)
- [5-Week Timeline](./plans/5.%20Timelines.md)
