import type { components } from "./generated/openapi";

// Pick declared properties: OpenAPI's additionalProperties index signature
// would otherwise erase named fields when using Omit<Problem, "code">.
export type Problem = Pick<
  components["schemas"]["Problem"],
  | "type"
  | "title"
  | "status"
  | "detail"
  | "instance"
  | "correlationId"
  | "timestamp"
  | "retryable"
  | "errors"
  | "challenge"
  | "reference"
  | "transferStatus"
  | "failureCode"
  | "lockedUntil"
> & {
  code: string;
};
export type ResponseMeta = {
  status: number;
  correlationId: string | null;
  requestId: string | null;
  retryAfter: string | null;
  idempotentReplayed: boolean;
};
export type ApiResponse<T> = ResponseMeta & { data: T };

// Public messages are independent of untrusted/raw server detail or HTML.
const messages: Record<string, string> = {
  AUTH_SESSION_EXPIRED: "Phiên đã hết hiệu lực. Vui lòng đăng nhập lại.",
  AUTH_INVALID_CREDENTIALS: "Thông tin đăng nhập không hợp lệ.",
  AUTH_OTP_INVALID: "Mã xác thực không hợp lệ.",
  AUTH_FORBIDDEN: "Bạn không có quyền thực hiện thao tác này.",
  VALIDATION_ERROR: "Vui lòng kiểm tra thông tin đã nhập.",
  RATE_LIMITED: "Vui lòng chờ trước khi thử lại.",
  NETWORK_ERROR: "Không thể kết nối. Kết quả thao tác chưa được xác nhận.",
  TIMEOUT: "Hết thời gian chờ. Kết quả thao tác chưa được xác nhận.",
  INVALID_RESPONSE: "Phản hồi không hợp lệ. Vui lòng kiểm tra lại kết quả.",
  CSRF_REQUIRED: "Cần khôi phục phiên trước khi thực hiện thao tác.",
  INVALID_REQUEST: "Dữ liệu gửi đi không hợp lệ.",
};

export class ApiError extends Error {
  public readonly meta: ResponseMeta;
  constructor(
    public readonly code: string,
    meta: ResponseMeta,
    public readonly problem?: Problem,
  ) {
    super(
      Object.hasOwn(messages, code)
        ? messages[code]
        : "Không thể hoàn tất yêu cầu. Vui lòng kiểm tra lại.",
    );
    this.name = "ApiError";
    // Never retain an unexpected auth response/secret as metadata.data.
    this.meta = {
      status: meta.status,
      correlationId: meta.correlationId,
      requestId: meta.requestId,
      retryAfter: meta.retryAfter,
      idempotentReplayed: meta.idempotentReplayed,
    };
  }
}

export function isProblem(value: unknown, status: number): value is Problem {
  if (!value || typeof value !== "object") return false;
  const p = value as Record<string, unknown>;
  return (
    [
      "type",
      "title",
      "detail",
      "instance",
      "code",
      "correlationId",
      "timestamp",
    ].every((key) => typeof p[key] === "string") &&
    p.status === status &&
    typeof p.retryable === "boolean" &&
    (p.errors === undefined ||
      (Array.isArray(p.errors) &&
        p.errors.every(
          (e) =>
            e &&
            typeof e === "object" &&
            ["field", "code", "message"].every(
              (key) => typeof (e as Record<string, unknown>)[key] === "string",
            ),
        )))
  );
}

/** Caller supplies an allowlist: arbitrary server field paths never bind a form. */
export function formErrors(error: ApiError, fields: readonly string[]) {
  if (error.code !== "VALIDATION_ERROR") return [];
  return (error.problem?.errors ?? [])
    .filter((entry) => fields.includes(entry.field))
    .map(({ field, code }) => ({
      field,
      code,
      message: messages.VALIDATION_ERROR,
    }));
}

const emptyMeta = (): ResponseMeta => ({
  status: 0,
  correlationId: null,
  requestId: null,
  retryAfter: null,
  idempotentReplayed: false,
});
type Options = {
  method?: "GET" | "POST";
  body?: unknown;
  query?: URLSearchParams;
  signal?: AbortSignal;
  csrf?: "required" | "optional";
  csrfToken?: string;
};
export type ApiClient = ReturnType<typeof createApiClient>;

export function createApiClient(config: {
  baseUrl: string;
  getCsrfToken?: () => string | undefined;
  onSessionExpired?: () => void;
  fetch?: typeof fetch;
}) {
  const base = config.baseUrl.replace(/\/$/, "");
  const fetcher = config.fetch ?? globalThis.fetch.bind(globalThis);
  const pending = new Set<AbortController>();
  let epoch = 0;
  let closed = false;
  const invalidate = () => {
    epoch++;
    for (const controller of pending) controller.abort();
    pending.clear();
  };

  async function request<T = unknown>(
    path: string,
    options: Options = {},
  ): Promise<ApiResponse<T>> {
    if (closed) throw new DOMException("Client disposed", "AbortError");
    // Paths stay inside the configured API base; no absolute URL or traversal.
    if (
      !path.startsWith("/") ||
      path.startsWith("//") ||
      /[\\?#]/.test(path) ||
      path.split("/").some((part) => {
        const decoded = decodeURIComponent(part);
        return (
          [".", ".."].includes(decoded) ||
          decoded.includes("/") ||
          decoded.includes("\\") ||
          [...decoded].some(
            (char) => char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127,
          )
        );
      })
    )
      throw new TypeError(
        "Expected an API path inside the configured base URL",
      );
    const method = options.method ?? "GET";
    let serializedBody: string | undefined;
    try {
      serializedBody =
        options.body === undefined ? undefined : JSON.stringify(options.body);
    } catch {
      throw new ApiError("INVALID_REQUEST", emptyMeta());
    }
    const token = options.csrfToken ?? config.getCsrfToken?.();
    if (
      (options.csrf ?? (method === "GET" ? "optional" : "required")) ===
        "required" &&
      !token
    )
      throw new ApiError("CSRF_REQUIRED", emptyMeta());
    const headers = new Headers({
      Accept: "application/json, application/problem+json",
      "X-Correlation-ID": crypto.randomUUID(),
    });
    if (options.body !== undefined)
      headers.set("Content-Type", "application/json");
    if (method !== "GET" && token) headers.set("X-CSRF-Token", token);
    const meta = emptyMeta();
    meta.correlationId = headers.get("X-Correlation-ID");
    const requestEpoch = epoch;
    const controller = new AbortController();
    pending.add(controller);
    const abort = () => controller.abort();
    if (options.signal?.aborted) abort();
    options.signal?.addEventListener("abort", abort, { once: true });
    let timedOut = false;
    // 07 §2 and API-CLIENT-01: 10 seconds for ordinary API requests.
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 10_000);
    const assertCurrent = () => {
      if (requestEpoch !== epoch || (controller.signal.aborted && !timedOut))
        throw new DOMException(
          "Request context changed or was cancelled",
          "AbortError",
        );
      if (timedOut) throw new ApiError("TIMEOUT", meta);
    };
    try {
      assertCurrent();
      const response = await fetcher(
        `${base}${path}${options.query?.size ? `?${options.query}` : ""}`,
        {
          method,
          headers,
          credentials: "include",
          cache: "no-store",
          body: serializedBody,
          signal: controller.signal,
        },
      );
      meta.status = response.status;
      meta.correlationId =
        response.headers.get("X-Correlation-ID") ?? meta.correlationId;
      meta.requestId = response.headers.get("X-Request-ID");
      meta.retryAfter = response.headers.get("Retry-After");
      meta.idempotentReplayed =
        response.headers.get("Idempotent-Replayed") === "true";
      assertCurrent();
      if (response.status === 204) return { ...meta, data: undefined as T };
      let data: unknown;
      try {
        data = await response.json();
      } catch {
        assertCurrent();
        throw new ApiError("INVALID_RESPONSE", meta);
      }
      assertCurrent();
      if (!response.ok) {
        if (!isProblem(data, response.status))
          throw new ApiError("INVALID_RESPONSE", meta);
        const error = new ApiError(data.code, meta, data);
        if (response.status === 401 && data.code === "AUTH_SESSION_EXPIRED")
          config.onSessionExpired?.();
        throw error;
      }
      return { ...meta, data: data as T };
    } catch (error) {
      if (error instanceof ApiError) throw error;
      assertCurrent();
      throw new ApiError("NETWORK_ERROR", meta);
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      pending.delete(controller);
    }
  }
  // Retry/poll policy is deliberately owned by approved feature flows.
  return {
    request,
    invalidate,
    dispose() {
      closed = true;
      invalidate();
    },
  };
}
