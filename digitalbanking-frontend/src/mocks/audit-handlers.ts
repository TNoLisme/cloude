import { delay, http, HttpResponse } from "msw";
import { isApiId } from "../shared/lib/id";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
let enabled = false;
let scenario: Scenario = "default";
export function prepareAuditFixture() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  enabled = true;
  scenario = "default";
}
export function selectAuditScenario(value: Scenario) {
  scenario = value;
}
export function resetAuditFixture() {
  enabled = false;
  scenario = "default";
}
const records = [
  {
    auditId: "8001",
    occurredAt: "2026-11-20T08:15:00Z",
    recordedAt: "2026-11-20T08:15:01Z",
    actor: { type: "USER", userId: "101", role: "OPERATIONS" },
    action: "CUSTOMER_READ",
    entity: { type: "CUSTOMER", id: "2001" },
    correlationId: "75a7a324-8298-4b99-9c5d-23ae98e61545",
    outcome: "SUCCESS",
    recordHash: "a".repeat(64),
    prevHash: null,
    hashVersion: 1,
    requestId: "4b35959f-a011-4f53-bff7-4fdd519946ba",
  },
  {
    auditId: "8002",
    occurredAt: "2026-11-20T09:20:00Z",
    recordedAt: "2026-11-20T09:20:01Z",
    actor: { type: "USER", userId: "102", role: "AUDITOR" },
    action: "SECURITY_ACCESS_DENIED",
    entity: { type: "CUSTOMER", id: "2001" },
    correlationId: "8053f2ad-86fc-4546-9b3e-e2d49bbcd697",
    outcome: "REJECTED",
    recordHash: "b".repeat(64),
    prevHash: "a".repeat(64),
    hashVersion: 1,
    requestId: "c361e8fd-e63c-4779-81f7-ac2683e90105",
  },
];
const problem = (status: number, code: string) =>
  HttpResponse.json(
    {
      type: "about:blank",
      title: code,
      status,
      detail: code,
      instance: "/api/v2/audit",
      code,
      correlationId: "cbafcf1b-f0df-48bb-ab88-9c2b2585facf",
      timestamp: "2026-11-20T10:00:00Z",
      retryable: status >= 500,
    },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
export const auditHandlers = [
  http.get("*/api/v2/audit", async ({ request }) => {
    if (!enabled) return HttpResponse.error();
    if (scenario === "slow") await delay(1200);
    if (scenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
    if (scenario === "forbidden") return problem(403, "AUTH_FORBIDDEN");
    const query = new URL(request.url).searchParams;
    if (
      [...query.keys()].some(
        (key) =>
          ![
            "page",
            "size",
            "reference",
            "actorUserId",
            "action",
            "outcome",
            "from",
            "to",
          ].includes(key),
      )
    )
      return problem(400, "VALIDATION_ERROR");
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? "20");
    if (
      !Number.isInteger(page) ||
      page < 0 ||
      !Number.isInteger(size) ||
      size < 1 ||
      size > 100
    )
      return problem(400, "VALIDATION_ERROR");
    const actorUserId = query.get("actorUserId");
    const action = query.get("action");
    const outcome = query.get("outcome");
    const from = query.get("from");
    const to = query.get("to");
    if (
      (actorUserId && !isApiId(actorUserId)) ||
      (action && action.length > 64) ||
      (outcome && !["SUCCESS", "FAILURE", "REJECTED"].includes(outcome)) ||
      (from && Number.isNaN(Date.parse(from))) ||
      (to && Number.isNaN(Date.parse(to))) ||
      (from && to && Date.parse(from) >= Date.parse(to))
    )
      return problem(400, "VALIDATION_ERROR");
    const filtered = (scenario === "empty" ? [] : records).filter(
      (item) =>
        (!actorUserId || item.actor.userId === actorUserId) &&
        (!action || item.action === action) &&
        (!outcome || item.outcome === outcome) &&
        (!from || Date.parse(item.occurredAt) >= Date.parse(from)) &&
        (!to || Date.parse(item.occurredAt) < Date.parse(to)) &&
        (!query.get("reference") ||
          query.get("reference") === "TXN20261120ABCD1234"),
    );
    const totalPages = Math.ceil(filtered.length / size);
    return HttpResponse.json({
      items: filtered
        .slice(page * size, (page + 1) * size)
        .map(({ requestId, ...summary }) => {
          void requestId;
          return summary;
        }),
      page: {
        number: page,
        size,
        totalElements: filtered.length,
        totalPages,
        first: page === 0,
        last: page + 1 >= totalPages,
      },
      asOf: "2026-11-20T10:00:00Z",
    });
  }),
  http.get("*/api/v2/audit/:auditId", async ({ params }) => {
    if (!enabled) return HttpResponse.error();
    if (scenario === "slow") await delay(1200);
    if (scenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
    if (scenario === "forbidden") return problem(403, "AUTH_FORBIDDEN");
    const auditId = String(params.auditId);
    if (!isApiId(auditId)) return problem(400, "VALIDATION_ERROR");
    const item = records.find((record) => record.auditId === auditId);
    return item
      ? HttpResponse.json({ ...item, beforeState: { internal: "hidden" } })
      : problem(404, "AUDIT_NOT_FOUND");
  }),
];
