import { delay, http, HttpResponse } from "msw";
import { isApiId } from "../shared/lib/id";
import { approvalDetails, approvalItems } from "./approval-fixtures";

type Scenario = "default" | "empty" | "slow" | "error";
let enabled = false;
let scenario: Scenario = "default";
export function prepareApprovalFixture() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  enabled = true;
  scenario = "default";
}
export function selectApprovalScenario(value: Scenario) {
  scenario = value;
}
export function resetApprovalFixture() {
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
      instance: "/api/v2/admin/approvals",
      code,
      correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
      timestamp: "2026-11-19T03:00:00Z",
      retryable: status >= 500,
    },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
export const approvalHandlers = [
  http.get("*/api/v2/admin/approvals", async ({ request }) => {
    if (!enabled) return HttpResponse.error();
    if (scenario === "slow") await delay(1500);
    if (scenario === "error") return error(503, "SERVICE_UNAVAILABLE");
    const query = new URL(request.url).searchParams;
    if (
      [...query.keys()].some(
        (key) => !["page", "size", "requestType", "status"].includes(key),
      )
    )
      return error(400, "VALIDATION_ERROR");
    const page = Number(query.get("page") ?? "0"),
      size = Number(query.get("size") ?? "20");
    const requestType = query.get("requestType"),
      status = query.get("status");
    if (
      !Number.isInteger(page) ||
      page < 0 ||
      !Number.isInteger(size) ||
      size < 1 ||
      size > 100 ||
      (requestType && !["LIMIT_CHANGE", "ROLE_CHANGE"].includes(requestType)) ||
      (status &&
        !["PENDING", "APPROVED", "REJECTED", "APPLIED", "CANCELLED"].includes(
          status,
        ))
    )
      return error(400, "VALIDATION_ERROR");
    const items = (scenario === "empty" ? [] : approvalItems).filter(
      (item) =>
        (!requestType || item.requestType === requestType) &&
        (!status || item.status === status),
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
      asOf: "2026-11-19T03:00:00Z",
    });
  }),
  http.get("*/api/v2/admin/approvals/:approvalId", async ({ params }) => {
    if (!enabled) return HttpResponse.error();
    if (scenario === "slow") await delay(1500);
    if (scenario === "error") return error(503, "SERVICE_UNAVAILABLE");
    const id = String(params.approvalId);
    if (!isApiId(id)) return error(400, "VALIDATION_ERROR");
    const detail = approvalDetails.find((item) => item.approvalId === id);
    return detail
      ? HttpResponse.json(detail)
      : error(404, "APPROVAL_NOT_FOUND");
  }),
];
