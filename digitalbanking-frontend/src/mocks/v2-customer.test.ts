import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { resetV2MockSession, v2AuthHandlers } from "./v2-auth";

const server = setupServer(...v2AuthHandlers);
const base = "http://localhost/api/v2";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  resetV2MockSession();
  server.resetHandlers();
});
async function login() {
  return fetch(`${base}/auth/login`, {
    method: "POST",
    body: JSON.stringify({
      username: "fixture-customer",
      password: "fixture-only",
    }),
  });
}
describe("customer self v2 mock", () => {
  it("requires a synthetic session for each read", async () => {
    for (const path of ["/customers/me", "/accounts", "/accounts/3001"]) {
      const response = await fetch(base + path);
      expect(response.status).toBe(401);
      expect((await response.json()).code).toBe("AUTH_SESSION_EXPIRED");
    }
  });
  it("returns masked profile and account DTOs with decimal-string balances", async () => {
    expect((await login()).status).toBe(200);
    const profile = await (await fetch(base + "/customers/me")).json();
    expect(profile).toMatchObject({
      customerId: "2001",
      userId: "1001",
      phoneMasked: "*******123",
      emailMasked: "n***@example.test",
    });
    expect(profile).not.toHaveProperty("phone");
    expect(profile).not.toHaveProperty("email");
    const list = await (await fetch(base + "/accounts")).json();
    expect(list.items).toHaveLength(1);
    expect(list.items[0]).toMatchObject({
      accountId: "3001",
      accountNumberMasked: "**** **** 3001",
      availableBalance: "25000000.00",
    });
    const detail = await (await fetch(base + "/accounts/3001")).json();
    expect(detail.owner.customerId).toBe(profile.customerId);
    expect(detail).not.toHaveProperty("accountNumber");
    expect(
      (await (await fetch(base + "/accounts?status=LOCKED")).json()).items,
    ).toEqual([]);
  });
  it("distinguishes malformed IDs, another customer's synthetic account and missing accounts", async () => {
    await login();
    for (const [id, status, code] of [
      ["abc", 400, "VALIDATION_ERROR"],
      ["3002", 403, "AUTH_FORBIDDEN"],
      ["9007199254740993", 404, "ACCOUNT_NOT_FOUND"],
    ] as const) {
      const result = await fetch(`${base}/accounts/${id}`);
      expect(result.status).toBe(status);
      expect((await result.json()).code).toBe(code);
    }
  });
  it("fails closed on an invalid status filter whose response is unspecified", async () => {
    await login();
    await expect(fetch(`${base}/accounts?status=unknown`)).rejects.toThrow();
  });
});
