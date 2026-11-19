// @vitest-environment jsdom
import { afterAll, afterEach, beforeAll, expect, it } from "vitest";
import { setupServer } from "msw/node";
import {
  disableV2FixturePersistence,
  enableV2FixturePersistence,
  resetV2MockSession,
  selectV2MockScenario,
  v2AuthFixture,
  v2AuthHandlers,
  v2FixtureStorageKey,
} from "./v2-auth";

const server = setupServer(...v2AuthHandlers);
const base = "http://localhost/api/v2";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  disableV2FixturePersistence();
  resetV2MockSession();
  sessionStorage.clear();
  server.resetHandlers();
});
const login = () =>
  fetch(`${base}/auth/login`, {
    method: "POST",
    body: JSON.stringify({
      username: "fixture-customer",
      password: "fixture-only",
    }),
  });
const logout = () =>
  fetch(`${base}/auth/logout`, {
    method: "POST",
    headers: { "X-CSRF-Token": v2AuthFixture().session.csrfToken },
  });
it("stores one explicit fixture flag only and restores the synthetic session after reload setup", async () => {
  sessionStorage.clear();
  enableV2FixturePersistence();
  expect((await fetch(`${base}/auth/session`)).status).toBe(401);
  await login();
  expect(sessionStorage.length).toBe(1);
  expect(sessionStorage.key(0)).toBe(v2FixtureStorageKey);
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBe("1");
  disableV2FixturePersistence();
  enableV2FixturePersistence();
  expect((await fetch(`${base}/auth/session`)).status).toBe(200);
  expect((await logout()).status).toBe(204);
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBeNull();
  disableV2FixturePersistence();
  enableV2FixturePersistence();
  expect((await fetch(`${base}/auth/session`)).status).toBe(401);
});
it("ignores arbitrary storage data instead of decoding it as user/session metadata", async () => {
  sessionStorage.setItem(
    v2FixtureStorageKey,
    JSON.stringify({ userId: "999", csrfToken: "never-read" }),
  );
  enableV2FixturePersistence();
  expect((await fetch(`${base}/auth/session`)).status).toBe(401);
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBeNull();
});
it("clears the fixture flag on failed logout but retains same-page mock state for explicit server recheck", async () => {
  enableV2FixturePersistence();
  await login();
  selectV2MockScenario("logout-error");
  expect((await logout()).status).toBe(503);
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBeNull();
  expect((await fetch(`${base}/auth/session`)).status).toBe(200);
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBe("1");
});
