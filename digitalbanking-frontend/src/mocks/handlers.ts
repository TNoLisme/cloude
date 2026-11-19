import { delay, http, HttpResponse } from "msw";
import { account, accounts, authFixture, profile } from "./fixtures";
import type { components } from "../shared/api/generated/openapi";
import { z } from "zod";

const loginSchema = z.strictObject({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(128),
  deviceRef: z.uuid().optional(),
  deviceName: z.string().min(1).max(100).optional(),
  deviceFingerprint: z.string().min(1).max(512).optional(),
});

// Only operations already specified in digital-banking-v1.yaml.
// This fixture session does not implement or prove backend cookie/security policy.
let signedIn = false;
export function selectPreviewSession() {
  signedIn = true;
}
export function resetMockSession() {
  signedIn = false;
}
const problem = (status: number, code: components["schemas"]["ErrorCode"]) =>
  HttpResponse.json(
    {
      type: "about:blank",
      title: code,
      status,
      code,
      detail: "Kịch bản dữ liệu tổng hợp để kiểm tra giao diện.",
      instance: "/api/v1",
      timestamp: new Date().toISOString(),
      retryable: false,
      correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
    },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
export const handlers = [
  http.post("*/api/v1/auth/login", async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    if (!loginSchema.safeParse(body).success) {
      return problem(400, "VALIDATION_ERROR");
    }
    // A documented HTTP 200 scenario, not password verification.
    await delay(150);
    signedIn = true;
    return HttpResponse.json(authFixture());
  }),
  http.get("*/api/v1/auth/session", () =>
    signedIn
      ? HttpResponse.json(authFixture())
      : problem(401, "AUTH_SESSION_EXPIRED"),
  ),
  http.post("*/api/v1/auth/logout", () => {
    signedIn = false;
    return new HttpResponse(null, { status: 204 });
  }),
  http.get("*/api/v1/customers/me", () =>
    signedIn
      ? HttpResponse.json(profile)
      : problem(401, "AUTH_SESSION_EXPIRED"),
  ),
  http.get("*/api/v1/accounts", () =>
    signedIn
      ? HttpResponse.json(accounts)
      : problem(401, "AUTH_SESSION_EXPIRED"),
  ),
  http.get("*/api/v1/accounts/:accountId", ({ params }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    return params.accountId === account.accountId
      ? HttpResponse.json(account)
      : problem(404, "ACCOUNT_NOT_FOUND");
  }),
];
