import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient, formErrors } from "./client";

export function problem(code: string, status: number, extra = {}) {
  return {
    type: "about:blank",
    title: code,
    detail: "<script>raw server detail</script>",
    instance: "/fixture",
    status,
    code,
    retryable: true,
    correlationId: "original-body-id",
    timestamp: "2026-11-17T00:00:00Z",
    ...extra,
  };
}
const baseUrl = "http://localhost/api/v2";
afterEach(() => vi.useRealTimers());

describe("shared transport primitives (fetch doubles, no business handlers)", () => {
  it("preserves status and original body correlation separately from request headers", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        { correlationId: "original-body-id" },
        {
          status: 201,
          headers: {
            "X-Correlation-ID": "current-header-id",
            "X-Request-ID": "request-id",
            "Idempotent-Replayed": "true",
            "Retry-After": "2",
          },
        },
      ),
    );
    const client = createApiClient({
      baseUrl,
      fetch: fetcher,
      getCsrfToken: () => "fixture-csrf",
    });
    const result = await client.request("/fixture", {
      method: "POST",
      body: { amount: "0.10", id: "9999999999999999999" },
    });
    expect(result).toMatchObject({
      status: 201,
      correlationId: "current-header-id",
      requestId: "request-id",
      idempotentReplayed: true,
      retryAfter: "2",
      data: { correlationId: "original-body-id" },
    });
    const [, init] = fetcher.mock.calls[0];
    expect(init).toMatchObject({
      credentials: "include",
      cache: "no-store",
      body: '{"amount":"0.10","id":"9999999999999999999"}',
    });
    const headers = new Headers(init?.headers);
    expect(headers.get("X-CSRF-Token")).toBe("fixture-csrf");
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.has("Cookie")).toBe(false);
  });
  it("does not parse a 204 body", async () => {
    const response = new Response(null, { status: 204 });
    const parse = vi.spyOn(response, "json");
    const c = createApiClient({
      baseUrl,
      fetch: vi.fn<typeof fetch>().mockResolvedValue(response),
    });
    expect((await c.request("/fixture")).data).toBeUndefined();
    expect(parse).not.toHaveBeenCalled();
  });
  it("requires CSRF before protected POST but public login can explicitly omit it", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({}));
    const c = createApiClient({ baseUrl, fetch: fetcher });
    await expect(
      c.request("/fixture", { method: "POST" }),
    ).rejects.toMatchObject({ code: "CSRF_REQUIRED" });
    expect(fetcher).not.toHaveBeenCalled();
    await c.request("/auth/login", { method: "POST", csrf: "optional" });
    expect(
      new Headers(fetcher.mock.calls[0][1]?.headers).has("X-CSRF-Token"),
    ).toBe(false);
  });
  it.each(["AUTH_OTP_INVALID", "AUTH_INVALID_CREDENTIALS", "FUTURE_AUTH_CODE"])(
    "does not invalidate session for %s",
    async (code) => {
      const onSessionExpired = vi.fn();
      const c = createApiClient({
        baseUrl,
        onSessionExpired,
        fetch: vi
          .fn<typeof fetch>()
          .mockResolvedValue(
            Response.json(problem(code, 401), { status: 401 }),
          ),
      });
      await expect(c.request("/fixture")).rejects.toMatchObject({
        code,
        meta: { status: 401 },
      });
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );
  it("invalidates only the current AUTH_SESSION_EXPIRED response", async () => {
    const onSessionExpired = vi.fn();
    const c = createApiClient({
      baseUrl,
      onSessionExpired,
      fetch: vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          Response.json(problem("AUTH_SESSION_EXPIRED", 401), { status: 401 }),
        ),
    });
    await expect(c.request("/fixture")).rejects.toMatchObject({
      code: "AUTH_SESSION_EXPIRED",
    });
    expect(onSessionExpired).toHaveBeenCalledOnce();
  });
  it("preserves error metadata, allows only known form fields, and renders safe messages", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        problem("VALIDATION_ERROR", 400, {
          errors: [
            { field: "username", code: "REQUIRED", message: "unsafe raw text" },
            {
              field: "__proto__.value",
              code: "REQUIRED",
              message: "unsafe raw text",
            },
          ],
        }),
        { status: 400, headers: { "X-Correlation-ID": "header-id" } },
      ),
    );
    const c = createApiClient({ baseUrl, fetch: fetcher });
    try {
      await c.request("/fixture");
      expect.fail("Expected an error");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      const e = error as ApiError;
      expect(e.meta.correlationId).toBe("header-id");
      expect(e.problem?.correlationId).toBe("original-body-id");
      expect(formErrors(e, ["username"])).toEqual([
        {
          field: "username",
          code: "REQUIRED",
          message: "Vui lòng kiểm tra thông tin đã nhập.",
        },
      ]);
      expect(e.message).not.toContain("script");
    }
  });
  it.each([
    "<html>upstream error</html>",
    '{"status":401,"code":"AUTH_SESSION_EXPIRED"}',
  ])(
    "rejects invalid upstream response without treating it as a session expiry",
    async (body) => {
      const onSessionExpired = vi.fn();
      const c = createApiClient({
        baseUrl,
        onSessionExpired,
        fetch: vi
          .fn<typeof fetch>()
          .mockResolvedValue(new Response(body, { status: 401 })),
      });
      await expect(c.request("/fixture")).rejects.toMatchObject({
        code: "INVALID_RESPONSE",
      });
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );
  it("has no implicit retries even for retryable errors and Retry-After", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () =>
      Response.json(problem("RATE_LIMITED", 429), {
        status: 429,
        headers: { "Retry-After": "10" },
      }),
    );
    const c = createApiClient({ baseUrl, fetch: fetcher });
    await expect(
      c.request("/fixture", { method: "POST", csrf: "optional" }),
    ).rejects.toMatchObject({ meta: { retryAfter: "10" } });
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("rejects a non-JSON-serializable body before sending a request", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const c = createApiClient({ baseUrl, fetch: fetcher });
    await expect(
      c.request("/fixture", {
        method: "POST",
        csrf: "optional",
        body: { unsupported: 1n },
      }),
    ).rejects.toMatchObject({ code: "INVALID_REQUEST" });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("keeps only trace metadata when an error is constructed from a response envelope", () => {
    const meta = {
      status: 200,
      correlationId: "id",
      requestId: null,
      retryAfter: null,
      idempotentReplayed: false,
      data: { password: "never-retain" },
    };
    expect(
      JSON.stringify(new ApiError("INVALID_RESPONSE", meta)),
    ).not.toContain("never-retain");
  });
  it("cancels an in-flight request through the caller signal", async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );
    const c = createApiClient({ baseUrl, fetch: fetcher });
    const controller = new AbortController();
    const result = expect(
      c.request("/fixture", { signal: controller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
    controller.abort();
    await result;
  });
  it.each(["__proto__", "constructor"])(
    "uses a safe fallback for unknown code %s",
    (code) => {
      const e = new ApiError(code, {
        status: 500,
        correlationId: null,
        requestId: null,
        retryAfter: null,
        idempotentReplayed: false,
      });
      expect(e.message).toBe(
        "Không thể hoàn tất yêu cầu. Vui lòng kiểm tra lại.",
      );
    },
  );
  it("blocks requests after client disposal", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const c = createApiClient({ baseUrl, fetch: fetcher });
    c.dispose();
    await expect(c.request("/fixture")).rejects.toMatchObject({
      name: "AbortError",
    });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects a stale response even when a fetch implementation ignores cancellation", async () => {
    let resolve!: (r: Response) => void;
    const onSessionExpired = vi.fn();
    const c = createApiClient({
      baseUrl,
      onSessionExpired,
      fetch: () =>
        new Promise((r) => {
          resolve = r;
        }),
    });
    const pending = c.request("/fixture");
    c.invalidate();
    resolve(
      Response.json(problem("AUTH_SESSION_EXPIRED", 401), { status: 401 }),
    );
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
  it("honors external cancellation, including already aborted signals", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const controller = new AbortController();
    controller.abort();
    const c = createApiClient({ baseUrl, fetch: fetcher });
    await expect(
      c.request("/fixture", { signal: controller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("times out ordinary requests at 10 seconds without claiming server failure", async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn<typeof fetch>().mockImplementation(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );
    const c = createApiClient({ baseUrl, fetch: fetcher });
    const result = expect(c.request("/fixture")).rejects.toMatchObject({
      code: "TIMEOUT",
    });
    await vi.advanceTimersByTimeAsync(10_000);
    await result;
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it.each([
    "https://elsewhere.test/",
    "//elsewhere.test",
    "/../auth/session",
    "/%2e%2e/auth/session",
    "/\n../auth/session",
    "/%2e%2e%2fauth/session",
    "/a\\b",
  ])("rejects base escaping path %s", async (path) => {
    const fetcher = vi.fn<typeof fetch>();
    const c = createApiClient({ baseUrl, fetch: fetcher });
    await expect(c.request(path)).rejects.toBeInstanceOf(TypeError);
    expect(fetcher).not.toHaveBeenCalled();
  });
});
