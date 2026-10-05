# Digital Banking Simulator MVP — Documentation

## Documentation map

- [Backend Feature Delivery Workflow](./backend-development-workflow.md): mandatory per-update lifecycle: spec, clarification, user approval, implementation, testing, review, handover.
- [Observability and Debugging Policy](./observability-and-debugging.md): correlation, safe structured logs, error handling, metrics and sensitive-data rules.
- [MVP baseline documentation](./baseline/README.md): approved MVP-wide requirements and decisions.
- [Backend MVP phase roadmap](./projects/backend-mvp/00-roadmap.md): ordered phase specs. Review and approve each phase before its implementation.
- [Frontend MVP phase roadmap](./projects/frontend-mvp/00-roadmap.md): approved UI direction, route/role mapping, eight delivery phases, API integration and acceptance plans.
- [Local Docker Setup](./local-docker-setup.md): Compose startup for backend and PostgreSQL, FE URLs, verification, safe stop/restart and common failures.

Start from the workflow before implementing backend changes. Use baseline docs as shared context; each phase spec under `projects/backend-mvp/` defines one full development loop and requires its own approval before coding.
