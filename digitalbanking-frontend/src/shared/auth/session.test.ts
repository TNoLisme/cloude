import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { QueryClient } from "@tanstack/react-query";
import {
  resetV2MockSession,
  v2AuthFixture,
  v2AuthHandlers,
} from "../../mocks/v2-auth";
import { createV2Session } from "./session";
import { browserSessionSync, type SessionSync } from "./session-sync";

const baseUrl = "http://localhost/api/v2";
const server = setupServer(...v2AuthHandlers);
const instances: ReturnType<typeof createV2Session>[] = [];
const input = { username: "FIXTURE-CUSTOMER", password: " fixture-only " };
const make = (extra: Partial<Parameters<typeof createV2Session>[0]> = {}) => {
  const session = createV2Session({
    baseUrl,
    onSessionCleared: vi.fn(),
    ...extra,
  });
  instances.push(session);
  return session;
};
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  for (const s of instances.splice(0)) s.dispose();
  resetV2MockSession();
  server.resetHandlers();
  vi.unstubAllGlobals();
});
afterAll(() => server.close());

describe("v2 memory session with approved MSW handlers", () => {
  it("bootstraps anonymous, logs in without legacy token JSON, reloads metadata and logs out with CSRF/204", async () => {
    const requests: Request[] = [];
    server.events.on("request:start", ({ request }) => requests.push(request));
    const s = make();
    expect(await s.bootstrap()).toBeNull();
    expect(s.getSnapshot().phase).toBe("anonymous");
    expect((await s.login(input)).kind).toBe("authenticated");
    expect(JSON.stringify(s.getSnapshot())).not.toMatch(
      /accessToken|refreshToken|password|fixture-only/,
    );
    const restored = make();
    expect((await restored.bootstrap())?.user.userId).toBe(
      s.getSnapshot().auth?.user.userId,
    );
    expect(await restored.logout()).toEqual({ serverConfirmed: true });
    expect(restored.getSnapshot()).toEqual({
      phase: "anonymous",
      auth: null,
      challenge: null,
    });
    expect(await s.bootstrap()).toBeNull();
    const logout = requests.find((r) => r.url.endsWith("/auth/logout"));
    expect(logout?.headers.get("X-CSRF-Token")).toBe(
      v2AuthFixture().session.csrfToken,
    );
    expect(logout?.credentials).toBe("include");
    expect(requests.every((r) => !r.headers.has("Authorization"))).toBe(true);
    server.events.removeAllListeners();
  });
  it("lowercases username and preserves password bytes", async () => {
    let body: unknown;
    server.use(
      http.post(`${baseUrl}/auth/login`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(v2AuthFixture());
      }),
    );
    await make().login(input);
    expect(body).toEqual({
      username: "fixture-customer",
      password: " fixture-only ",
    });
  });
  it("keeps LOGIN_MFA as pending challenge without authenticating", async () => {
    const s = make();
    const result = await s.login({ ...input, username: "fixture-mfa" });
    expect(result.kind).toBe("challenge");
    expect(s.getSnapshot().auth).toBeNull();
    expect(s.getSnapshot().challenge?.challenge.purpose).toBe("LOGIN_MFA");
    expect(await make().bootstrap()).toBeNull();
  });
  it("carries the previous session CSRF when a new login can have an ambient cookie", async () => {
    let header: string | null = null;
    const s = make();
    await s.login(input);
    server.use(
      http.post(`${baseUrl}/auth/login`, ({ request }) => {
        header = request.headers.get("X-CSRF-Token");
        return HttpResponse.json(v2AuthFixture());
      }),
    );
    await s.login({ ...input, username: "other-fixture" });
    expect(header).toBe(v2AuthFixture().session.csrfToken);
  });
  it("requires the explicit v2 base", () => {
    expect(() => make({ baseUrl: "http://localhost/api/v1" })).toThrow(
      "/api/v2",
    );
  });
  it("rejects query/hash bases that only appear to end in v2", () => {
    expect(() =>
      make({ baseUrl: "http://localhost/api/v1?base=/api/v2" }),
    ).toThrow("/api/v2");
  });
  it("rejects legacy login DTO and unknown role before retaining a session", async () => {
    for (const body of [
      { accessToken: "never-retain" },
      {
        ...v2AuthFixture(),
        user: { ...v2AuthFixture().user, roles: ["OPERATOR"] },
      },
    ]) {
      server.use(
        http.post(`${baseUrl}/auth/login`, () => HttpResponse.json(body)),
      );
      const s = make();
      await expect(s.login(input)).rejects.toMatchObject({
        code: "INVALID_RESPONSE",
      });
      expect(s.getSnapshot().auth).toBeNull();
    }
  });
  it("projects approved metadata only, stripping unexpected secret fields", async () => {
    server.use(
      http.post(`${baseUrl}/auth/login`, () =>
        HttpResponse.json({
          ...v2AuthFixture(),
          accessToken: "never-retain",
          user: { ...v2AuthFixture().user, password: "never-retain" },
        }),
      ),
    );
    const s = make();
    await s.login(input);
    expect(JSON.stringify(s.getSnapshot())).not.toContain("never-retain");
  });
  it("distinguishes bootstrap network error from anonymous and permits explicit retry", async () => {
    server.use(http.get(`${baseUrl}/auth/session`, () => HttpResponse.error()));
    const s = make();
    await expect(s.bootstrap()).rejects.toMatchObject({
      code: "NETWORK_ERROR",
    });
    expect(s.getSnapshot().phase).toBe("error");
    server.resetHandlers();
    expect(await s.bootstrap()).toBeNull();
  });
  it("clears local session/cache on offline logout and reports server outcome unconfirmed", async () => {
    const cache = new QueryClient();
    const s = make({
      onSessionCleared: () => {
        void cache.cancelQueries();
        cache.clear();
      },
    });
    await s.login(input);
    cache.setQueryData(["fixture-user", "accounts"], { balance: "1.00" });
    server.use(http.post(`${baseUrl}/auth/logout`, () => HttpResponse.error()));
    const result = await s.logout();
    expect(result.serverConfirmed).toBe(false);
    expect(s.getSnapshot().auth).toBeNull();
    expect(cache.getQueryCache().getAll()).toHaveLength(0);
    server.resetHandlers();
    // The fixture server still has a session: local clear never claims revocation.
    expect(await s.bootstrap()).not.toBeNull();
  });
  it("does not let a delayed login restore data after logout", async () => {
    let resolve!: (response: Response) => void;
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const s = make({ fetch: fetcher });
    const pending = s.login(input);
    expect((await s.logout()).serverConfirmed).toBe(false);
    resolve(Response.json(v2AuthFixture()));
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(s.getSnapshot().auth).toBeNull();
  });
  it("cannot let an old bootstrap overwrite a newer login", async () => {
    let resolve!: (r: Response) => void;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValueOnce(
        Response.json({
          ...v2AuthFixture(),
          user: { ...v2AuthFixture().user, userId: "222" },
        }),
      );
    const s = make({ fetch: fetcher });
    const old = s.bootstrap();
    await s.login(input);
    resolve(Response.json(v2AuthFixture()));
    await expect(old).rejects.toMatchObject({ name: "AbortError" });
    expect(s.getSnapshot().auth?.user.userId).toBe("222");
  });
  it("cannot let an old logout completion clear a newer login", async () => {
    let resolve!: (r: Response) => void;
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(v2AuthFixture()))
      .mockImplementationOnce(
        () =>
          new Promise((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValueOnce(
        Response.json({
          ...v2AuthFixture(),
          user: { ...v2AuthFixture().user, userId: "222" },
        }),
      );
    const s = make({ fetch: fetcher });
    await s.login(input);
    const old = s.logout();
    await s.login(input);
    resolve(new Response(null, { status: 204 }));
    await expect(old).rejects.toMatchObject({ name: "AbortError" });
    expect(s.getSnapshot().auth?.user.userId).toBe("222");
  });
  it("clears the previous user's cache before a new login starts", async () => {
    const cache = new QueryClient();
    const s = make({
      onSessionCleared: () => {
        void cache.cancelQueries();
        cache.clear();
      },
    });
    await s.login(input);
    cache.setQueryData(["old-user"], { privateData: "old" });
    const pending = s.login({ ...input, username: "other-fixture" });
    expect(cache.getQueryCache().getAll()).toHaveLength(0);
    await pending;
  });
  it("retains an authenticated session for OTP 401 but clears for session expiry", async () => {
    const code = (value: string) =>
      Response.json(
        {
          type: "about:blank",
          title: value,
          detail: "fixture",
          instance: "/fixture",
          status: 401,
          code: value,
          retryable: false,
          correlationId: "fixture",
          timestamp: "2026-11-17T00:00:00Z",
        },
        { status: 401 },
      );
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json(v2AuthFixture()))
      .mockResolvedValueOnce(code("AUTH_OTP_INVALID"))
      .mockResolvedValueOnce(code("AUTH_SESSION_EXPIRED"));
    const s = make({ fetch: fetcher });
    await s.login(input);
    await expect(s.client.request("/fixture")).rejects.toMatchObject({
      code: "AUTH_OTP_INVALID",
    });
    expect(s.getSnapshot().phase).toBe("authenticated");
    await expect(s.client.request("/fixture")).rejects.toMatchObject({
      code: "AUTH_SESSION_EXPIRED",
    });
    expect(s.getSnapshot().phase).toBe("anonymous");
  });
  it("returns unconfirmed logout for an already expired server session", async () => {
    const s = make();
    await s.login(input);
    resetV2MockSession();
    const result = await s.logout();
    expect(result.serverConfirmed).toBe(false);
    expect(s.getSnapshot().phase).toBe("anonymous");
  });
  it("rejects malformed challenge response without storing authentication", async () => {
    server.use(
      http.post(`${baseUrl}/auth/login`, () =>
        HttpResponse.json(
          { challenge: { purpose: "LOGIN_NEW_DEVICE" } },
          { status: 202 },
        ),
      ),
    );
    const s = make();
    await expect(s.login(input)).rejects.toMatchObject({
      code: "INVALID_RESPONSE",
    });
    expect(s.getSnapshot().auth).toBeNull();
    expect(s.getSnapshot().challenge).toBeNull();
  });
  it("disposal cancels requests and clears secret metadata", async () => {
    const s = make();
    await s.login(input);
    s.dispose();
    expect(s.getSnapshot().auth).toBeNull();
    await expect(s.bootstrap()).rejects.toThrow("disposed");
  });
});

describe("tab invalidation signals", () => {
  it("clears peers without sharing session data or producing an echo", async () => {
    const listeners = [new Set<() => void>(), new Set<() => void>()];
    const publishes = [vi.fn(), vi.fn()];
    const endpoint = (i: number): SessionSync => ({
      publish() {
        publishes[i]();
        for (const fn of listeners[1 - i]) fn();
      },
      subscribe(fn) {
        listeners[i].add(fn);
        return () => {
          listeners[i].delete(fn);
        };
      },
      close() {
        listeners[i].clear();
      },
    });
    const a = make({ sync: endpoint(0) });
    const b = make({ sync: endpoint(1) });
    await a.login(input);
    await b.bootstrap();
    expect(b.getSnapshot().phase).toBe("authenticated");
    await a.logout();
    expect(b.getSnapshot().auth).toBeNull();
    expect(publishes[1]).not.toHaveBeenCalled();
  });
  it("browser adapter sends only a literal signal and ignores arbitrary incoming data", () => {
    const postMessage = vi.fn(),
      close = vi.fn();
    let receive!: (event: { data: unknown }) => void;
    vi.stubGlobal("window", {});
    vi.stubGlobal(
      "BroadcastChannel",
      class {
        postMessage = postMessage;
        close = close;
        onmessage?: (event: { data: unknown }) => void;
        constructor() {
          receive = (event) => this.onmessage?.(event);
        }
      },
    );
    const sync = browserSessionSync()!;
    const listener = vi.fn();
    const off = sync.subscribe(listener);
    sync.publish();
    expect(postMessage).toHaveBeenCalledWith("invalidate");
    receive({ data: { csrfToken: "untrusted" } });
    expect(listener).not.toHaveBeenCalled();
    receive({ data: "invalidate" });
    expect(listener).toHaveBeenCalledOnce();
    off();
    receive({ data: "invalidate" });
    expect(listener).toHaveBeenCalledOnce();
    sync.close();
    expect(close).toHaveBeenCalledOnce();
  });
});
