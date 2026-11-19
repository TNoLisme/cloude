import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
const user = {
  userId: "a",
  customerId: "c",
  displayName: "Test",
  phone: "0912345678",
  email: "test@example.test",
  roles: ["CUSTOMER"],
  isPinSet: true,
};
const access = {
  accessToken: "test-token",
  tokenType: "Bearer",
  expiresIn: 900,
  user,
};
function response(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
beforeEach(() => {
  vi.resetModules();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe("HTTP and session lifecycle", () => {
  it("uses real base, credentials, authorization and the supplied idempotency key", async () => {
    const fetch = vi.fn().mockResolvedValue(
      response({
        transferId: "transfer",
        status: "AWAITING_OTP",
        expiresInSeconds: 120,
      }),
    );
    vi.stubGlobal("fetch", fetch);
    const { session } = await import("../stores/session");
    session.getState().accept("test-token", user as never);
    const { post, transferCheck } = await import("./client");
    await post(
      "/transfers",
      { pin: "000001", amount: "6000000" },
      true,
      "stable-key",
      transferCheck,
    );
    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe("/api/v1/transfers");
    expect(options.credentials).toBe("include");
    expect(options.headers.Authorization).toBe("Bearer test-token");
    expect(options.headers["Idempotency-Key"]).toBe("stable-key");
    expect(JSON.parse(options.body).pin).toBe("000001");
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("handles 204 without parsing JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    const { request } = await import("./client");
    expect(await request("/auth/logout", { method: "POST" })).toBeUndefined();
  });
  it("refreshes once for concurrent callers and checks server user shape", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response({ csrfToken: "csrf-token-at-least-16" }))
      .mockResolvedValueOnce(response(access));
    vi.stubGlobal("fetch", fetch);
    const { refresh } = await import("./client");
    await Promise.all([refresh(), refresh(), refresh()]);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls[1][1].headers["X-CSRF-Token"]).toBe(
      "csrf-token-at-least-16",
    );
    const { session } = await import("../stores/session");
    expect(session.getState().user?.roles).toEqual(["CUSTOMER"]);
  });
  it("retries a safe read once after refresh", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(response({ code: "SESSION_EXPIRED" }, 401))
      .mockResolvedValueOnce(response({ csrfToken: "csrf-token-at-least-16" }))
      .mockResolvedValueOnce(response(access))
      .mockResolvedValueOnce(response({ items: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetch);
    const { session } = await import("../stores/session");
    session.getState().accept("old-token", user as never);
    const { page } = await import("./client");
    expect(await page("/accounts")).toEqual({ items: [], nextCursor: null });
    expect(fetch.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/accounts",
      "/api/v1/auth/csrf",
      "/api/v1/auth/refresh",
      "/api/v1/accounts",
    ]);
  });
  it("does not refresh/replay a money mutation after 401", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(response({ code: "SESSION_EXPIRED" }, 401));
    vi.stubGlobal("fetch", fetch);
    const { session } = await import("../stores/session");
    session.getState().accept("old-token", user as never);
    const { request } = await import("./client");
    await expect(
      request("/transfers", { method: "POST", auth: true, body: {} }),
    ).rejects.toMatchObject({ status: 401 });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(session.getState().user).toBeNull();
  });
  it("never accepts a late response from a previous user", async () => {
    let resolve!: (value: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockReturnValue(
        new Promise<Response>((done) => {
          resolve = done;
        }),
      ),
    );
    const { session } = await import("../stores/session");
    session.getState().accept("old-token", user as never);
    const { request } = await import("./client");
    const pending = request("/accounts", { auth: true });
    session.getState().clear();
    resolve(response({ items: [{ balance: "123" }], nextCursor: null }));
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
  it("honors Retry-After before allowing another request", async () => {
    const fetch = vi.fn().mockResolvedValue(
      response({ code: "RATE_LIMITED", correlationId: "corr" }, 429, {
        "Retry-After": "30",
      }),
    );
    vi.stubGlobal("fetch", fetch);
    const { request } = await import("./client");
    await expect(
      request("/auth/recover/verify", { method: "POST" }),
    ).rejects.toMatchObject({ status: 429, correlationId: "corr" });
    await expect(
      request("/auth/recover/verify", { method: "POST" }),
    ).rejects.toMatchObject({ status: 429 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("treats network/invalid successful responses as unknown outcomes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    const { post, uncertain, checkedLogin } = await import("./client");
    try {
      await post("/transfers", {});
    } catch (error) {
      expect(uncertain(error)).toBe(true);
    }
    expect(() =>
      checkedLogin({ ...access, user: { ...user, roles: ["FAKE_ADMIN"] } }),
    ).toThrow();
  });
});
