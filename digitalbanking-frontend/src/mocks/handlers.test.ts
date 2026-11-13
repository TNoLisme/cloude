import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { handlers, resetMockSession } from "./handlers";

const server = setupServer(...handlers);
const base = "http://localhost/api/v1";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  resetMockSession();
});
afterAll(() => server.close());

describe("target-contract mock API", () => {
  it("starts anonymous, exposes documented session/accounts after login and clears on logout", async () => {
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
    const login = await fetch(`${base}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: "fixture-customer",
        password: "fixture-only",
      }),
    });
    expect(login.status).toBe(200);
    const auth = await login.json();
    expect(auth).not.toHaveProperty("accessToken");
    expect(auth.user.roles).toEqual(["CUSTOMER"]);
    expect((await fetch(`${base}/auth/session`)).status).toBe(200);
    const accounts = await (await fetch(`${base}/accounts`)).json();
    expect(accounts.items[0].availableBalance).toBe("25000000.00");
    expect(typeof accounts.items[0].accountId).toBe("string");
    expect((await fetch(`${base}/accounts/3001`)).status).toBe(200);
    expect((await fetch(`${base}/accounts/9999`)).status).toBe(404);
    const profile = await (await fetch(`${base}/customers/me`)).json();
    expect(profile.phoneMasked).toBeDefined();
    expect(profile).not.toHaveProperty("phone");
    expect(
      (await fetch(`${base}/auth/logout`, { method: "POST" })).status,
    ).toBe(204);
    expect((await fetch(`${base}/accounts`)).status).toBe(401);
  });
  it("rejects legacy phone login rather than silently accepting a changed payload", async () => {
    const response = await fetch(`${base}/auth/login`, {
      method: "POST",
      body: JSON.stringify({ phone: "0900000000", password: "fixture-only" }),
    });
    expect(response.status).toBe(400);
    expect(response.headers.get("Content-Type")).toContain(
      "application/problem+json",
    );
  });
  it("fails closed for an unimplemented API instead of reaching backend", async () => {
    await expect(fetch(`${base}/not-in-contract`)).rejects.toThrow();
  });
});
