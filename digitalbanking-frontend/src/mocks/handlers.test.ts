import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { handlers, resetMockSession, selectPreviewSession } from "./handlers";

const server = setupServer(...handlers);
const base = "http://localhost/api/v1";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  resetMockSession();
});
afterAll(() => server.close());

describe("target-contract mock API", () => {
  it("starts anonymous, exposes fixture session/accounts and clears on logout", async () => {
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
    selectPreviewSession();
    const auth = await (await fetch(`${base}/auth/session`)).json();
    expect(auth).not.toHaveProperty("accessToken");
    expect(auth.user.roles).toEqual(["CUSTOMER"]);
    expect((await fetch(`${base}/auth/session`)).status).toBe(200);
    const accounts = await (await fetch(`${base}/accounts`)).json();
    expect(accounts.items[0].availableBalance).toBe("25000000.00");
    expect(typeof accounts.items[0].accountId).toBe("string");
    const detailResponse = await fetch(`${base}/accounts/3001`);
    expect(detailResponse.status).toBe(200);
    const detail = await detailResponse.json();
    expect(detail.owner).toEqual({ customerId: "2001", status: "ACTIVE" });
    expect(detail.owner).not.toHaveProperty("fullName");
    expect((await fetch(`${base}/accounts/9999`)).status).toBe(404);
    const profile = await (await fetch(`${base}/customers/me`)).json();
    expect(profile.phoneMasked).toBeDefined();
    expect(profile).not.toHaveProperty("phone");
    expect(
      (await fetch(`${base}/auth/logout`, { method: "POST" })).status,
    ).toBe(204);
    expect((await fetch(`${base}/accounts`)).status).toBe(401);
  });
  it("fails closed for v1 login until the MFA flow is implemented", async () => {
    await expect(
      fetch(`${base}/auth/login`, {
        method: "POST",
        body: JSON.stringify({ username: "fixture", password: "fixture" }),
      }),
    ).rejects.toThrow();
  });
  it("fails closed for an unimplemented API instead of reaching backend", async () => {
    await expect(fetch(`${base}/not-in-contract`)).rejects.toThrow();
  });
});
