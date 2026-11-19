import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { handlers, resetMockSession } from "./handlers";
import { resetV2MockSession, v2AuthHandlers } from "./v2-auth";

const server = setupServer(...handlers, ...v2AuthHandlers);
const base = "http://localhost/api/v2";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  resetV2MockSession();
  resetMockSession();
  server.resetHandlers();
});
afterAll(() => server.close());
async function login(username = "fixture-customer", version = "v2") {
  return fetch(`http://localhost/api/${version}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "fixture-only" }),
  });
}
describe("v2 Security baseline mock", () => {
  it("uses the v2 cookie name/path and clears it only after CSRF logout", async () => {
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
    const response = await login();
    const body = await response.json();
    expect(body).not.toHaveProperty("accessToken");
    expect(response.headers.get("X-Correlation-ID")).toBe(body.correlationId);
    const cookie = response.headers.get("Set-Cookie");
    expect(cookie).toContain("SESSION_TOKEN=");
    expect(cookie).toContain("Path=/api");
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("Secure");
    expect(cookie).not.toContain("DB_SESSION");
    expect((await fetch(`${base}/auth/session`)).status).toBe(200);
    expect(
      (await fetch(`${base}/auth/logout`, { method: "POST" })).status,
    ).toBe(403);
    expect((await fetch(`${base}/auth/session`)).status).toBe(200);
    const logout = await fetch(`${base}/auth/logout`, {
      method: "POST",
      headers: { "X-CSRF-Token": body.session.csrfToken },
    });
    expect(logout.status).toBe(204);
    expect(logout.headers.get("Set-Cookie")).toContain("SESSION_TOKEN=;");
    expect(logout.headers.get("Set-Cookie")).toContain("Path=/api");
    expect(logout.headers.get("Set-Cookie")).toContain("Max-Age=0");
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
  });
  it("returns LOGIN_MFA for the explicit challenge fixture without creating a session", async () => {
    const response = await login("fixture-mfa");
    expect(response.status).toBe(202);
    expect((await response.json()).challenge.purpose).toBe("LOGIN_MFA");
    expect(response.headers.get("Set-Cookie")).toBeNull();
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
  });
  it("keeps the v1 preview fixture independent from the v2 mock session", async () => {
    expect((await login("fixture-customer", "v1")).status).toBe(200);
    expect((await fetch("http://localhost/api/v1/auth/session")).status).toBe(
      200,
    );
    expect((await fetch(`${base}/auth/session`)).status).toBe(401);
  });
  it("rejects malformed login JSON and the legacy phone payload", async () => {
    for (const body of [
      "{",
      JSON.stringify({ phone: "0900000123", password: "fixture-only" }),
    ]) {
      const response = await fetch(`${base}/auth/login`, {
        method: "POST",
        body,
      });
      expect(response.status).toBe(400);
      expect((await response.json()).code).toBe("VALIDATION_ERROR");
    }
  });
});
