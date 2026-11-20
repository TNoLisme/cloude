import { delay, http, HttpResponse } from "msw";
import { isApiId } from "../shared/lib/id";
import { fraudFlags } from "./fraud-fixtures";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
let enabled = false;
let scenario: Scenario = "default";
export function prepareFraudFixture() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  enabled = true;
  scenario = "default";
}
export function selectFraudScenario(value: Scenario) {
  scenario = value;
}
export function resetFraudFixture() {
  enabled = false;
  scenario = "default";
}
const error = (status: number, code: string) =>
  HttpResponse.json(
    {
      type: "about:blank",
      title: code,
      status,
      detail: code,
      instance: "/api/v2/fraud-flags",
      code,
      correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
      timestamp: "2026-11-20T03:00:00Z",
      retryable: status >= 500,
    },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
export const fraudHandlers = [
  http.get("*/api/v2/fraud-flags", async ({ request }) => {
    if (!enabled) return HttpResponse.error();
    if (scenario === "slow") await delay(1500);
    if (scenario === "error") return error(503, "SERVICE_UNAVAILABLE");
    if (scenario === "forbidden") return error(403, "AUTH_FORBIDDEN");
    const query = new URL(request.url).searchParams;
    if (
      [...query.keys()].some(
        (key) =>
          ![
            "page",
            "size",
            "status",
            "severity",
            "ruleCode",
            "reference",
            "customerId",
            "from",
            "to",
          ].includes(key),
      )
    )
      return error(400, "VALIDATION_ERROR");
    const page = Number(query.get("page") ?? "0");
    const size = Number(query.get("size") ?? "20");
    const status = query.get("status");
    const severity = query.get("severity");
    const ruleCode = query.get("ruleCode");
    const reference = query.get("reference");
    const customerId = query.get("customerId");
    const from = query.get("from");
    const to = query.get("to");
    if (
      !Number.isInteger(page) ||
      page < 0 ||
      !Number.isInteger(size) ||
      size < 1 ||
      size > 100 ||
      (status &&
        !["OPEN", "UNDER_REVIEW", "ALLOWED", "BLOCKED", "CLOSED"].includes(
          status,
        )) ||
      (severity && !["LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(severity)) ||
      (ruleCode && ruleCode.length > 32) ||
      (customerId && !isApiId(customerId)) ||
      (from && Number.isNaN(Date.parse(from))) ||
      (to && Number.isNaN(Date.parse(to))) ||
      (from && to && Date.parse(from) >= Date.parse(to))
    )
      return error(400, "VALIDATION_ERROR");
    const items = (scenario === "empty" ? [] : fraudFlags).filter(
      (item) =>
        (!status || item.status === status) &&
        (!severity || item.severity === severity) &&
        (!ruleCode || item.rule.code === ruleCode) &&
        (!reference || item.transferReference === reference) &&
        (!customerId || item.customerId === customerId) &&
        (!from || Date.parse(item.createdAt) >= Date.parse(from)) &&
        (!to || Date.parse(item.createdAt) < Date.parse(to)),
    );
    const totalPages = Math.ceil(items.length / size);
    return HttpResponse.json({
      items: items.slice(page * size, (page + 1) * size),
      page: {
        number: page,
        size,
        totalElements: items.length,
        totalPages,
        first: page === 0,
        last: page + 1 >= totalPages,
      },
      asOf: "2026-11-20T03:00:00Z",
    });
  }),
];
