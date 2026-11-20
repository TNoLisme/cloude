import { delay, http, HttpResponse } from "msw";
import { isApiId } from "../shared/lib/id";
import { accounts, profile } from "./fixtures";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
let enabled = false;
let scenario: Scenario = "default";
export function prepareStaffCustomerFixture() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  enabled = true;
  scenario = "default";
}
export function selectStaffCustomerScenario(value: Scenario) {
  scenario = value;
}
export function resetStaffCustomerFixture() {
  enabled = false;
  scenario = "default";
}
const problem = (status: number, code: string) =>
  HttpResponse.json(
    {
      type: "about:blank",
      title: code,
      status,
      detail: code,
      instance: "/api/v2/admin/customers",
      code,
      correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
      timestamp: "2026-11-20T03:00:00Z",
      retryable: status >= 500,
    },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );

async function fixtureResponse(id: string, accountList: boolean) {
  if (!enabled) return HttpResponse.error();
  if (scenario === "slow") await delay(1500);
  if (scenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
  if (scenario === "forbidden") return problem(403, "AUTH_FORBIDDEN");
  if (!isApiId(id)) return problem(400, "VALIDATION_ERROR");
  if (id !== profile.customerId) return problem(404, "CUSTOMER_NOT_FOUND");
  return HttpResponse.json(
    accountList
      ? scenario === "empty"
        ? { ...accounts, items: [] }
        : accounts
      : profile,
  );
}

export const staffCustomerHandlers = [
  http.get("*/api/v2/admin/customers/:customerId", ({ params }) =>
    fixtureResponse(String(params.customerId), false),
  ),
  http.get("*/api/v2/admin/customers/:customerId/accounts", ({ params }) =>
    fixtureResponse(String(params.customerId), true),
  ),
];
