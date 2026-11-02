import { session } from "../stores/session";
import type { components } from "./generated/openapi";
export type Schema<K extends keyof components["schemas"]> =
  components["schemas"][K];

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    public correlationId?: string,
    public retryAt = 0,
    public fields: string[] = [],
  ) {
    super(code);
  }
}
const base = (import.meta.env.VITE_API_BASE_URL || "/api/v1").replace(
  /\/$/,
  "",
);
let csrf: string | null = null;
let refreshPromise: Promise<void> | null = null;
const throttled = new Map<string, number>();
export function resetClient() {
  csrf = null;
}
session.subscribe((s, prev) => {
  if (s.generation !== prev.generation) resetClient();
});
export const object = (x: unknown): x is Record<string, unknown> =>
  !!x && typeof x === "object" && !Array.isArray(x);
export function checked<T>(
  x: unknown,
  check: (value: Record<string, unknown>) => boolean,
): T {
  if (!object(x) || !check(x)) throw new ApiError(0, "INVALID_RESPONSE");
  return x as T;
}
const strings = (o: Record<string, unknown>, keys: string[]) =>
  keys.every((k) => typeof o[k] === "string");
export function checkedLogin(x: unknown): Schema<"LoginResponse"> {
  return checked(
    x,
    (v) =>
      strings(v, ["accessToken", "tokenType"]) &&
      v.tokenType === "Bearer" &&
      typeof v.expiresIn === "number" &&
      object(v.user) &&
      strings(v.user, [
        "userId",
        "customerId",
        "displayName",
        "phone",
        "email",
      ]) &&
      typeof v.user.isPinSet === "boolean" &&
      Array.isArray(v.user.roles) &&
      v.user.roles.every((r) =>
        ["CUSTOMER", "OPERATOR", "AUDITOR", "ADMIN"].includes(r),
      ),
  );
}
type Options = {
  method?: "GET" | "POST";
  body?: unknown;
  auth?: boolean;
  csrf?: boolean;
  key?: string;
  signal?: AbortSignal;
  retryRead?: boolean;
};
export async function request(
  path: string,
  options: Options = {},
): Promise<unknown> {
  const method = options.method || "GET";
  const throttleKey = `${method}:${path.split("?")[0]}`;
  const retryAt = throttled.get(throttleKey) || 0;
  if (retryAt > Date.now())
    throw new ApiError(429, "RATE_LIMITED", undefined, retryAt);
  throttled.delete(throttleKey);
  const snapshot = session.getState();
  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Correlation-Id": crypto.randomUUID(),
  };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.auth && snapshot.token)
    headers.Authorization = `Bearer ${snapshot.token}`;
  if (options.key) headers["Idempotency-Key"] = options.key;
  if (options.csrf) headers["X-CSRF-Token"] = await csrfToken();
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "include",
      signal: options.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new ApiError(0, "NETWORK_ERROR");
  }
  if (options.auth && session.getState().generation !== snapshot.generation)
    throw new DOMException("Session changed", "AbortError");
  if (
    response.status === 401 &&
    options.auth &&
    method === "GET" &&
    options.retryRead !== false
  ) {
    await refresh();
    return request(path, { ...options, retryRead: false });
  }
  if (response.status === 204 && response.ok) return undefined;
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError(
      response.status,
      "INVALID_RESPONSE",
      response.headers.get("X-Correlation-Id") || undefined,
    );
  }
  if (!response.ok) {
    const code =
      object(payload) && typeof payload.code === "string"
        ? payload.code
        : "HTTP_ERROR";
    const correlation =
      object(payload) && typeof payload.correlationId === "string"
        ? payload.correlationId
        : undefined;
    const retry = response.headers.get("Retry-After");
    const retryAt = retry
      ? /^\d+$/.test(retry)
        ? Date.now() + Number(retry) * 1000
        : Date.parse(retry)
      : 0;
    if (response.status === 429 && retryAt > Date.now())
      throttled.set(throttleKey, retryAt);
    // Mutations are never replayed automatically. The flow decides reconciliation.
    if (response.status === 401 && options.auth) session.getState().clear();
    const fields =
      object(payload) && Array.isArray(payload.fieldErrors)
        ? payload.fieldErrors
            .filter(object)
            .map((item) => item.field)
            .filter((field): field is string => typeof field === "string")
        : [];
    throw new ApiError(
      response.status,
      code,
      correlation,
      Number.isFinite(retryAt) ? retryAt : 0,
      fields,
    );
  }
  return payload;
}
async function csrfToken() {
  if (!csrf)
    csrf = checked<Schema<"CsrfTokenResponse">>(
      await request("/auth/csrf"),
      (v) => typeof v.csrfToken === "string",
    ).csrfToken;
  return csrf;
}
export async function refresh() {
  if (!refreshPromise) {
    const generation = session.getState().generation;
    refreshPromise = (async () => {
      try {
        const result = checkedLogin(
          await request("/auth/refresh", { method: "POST", csrf: true }),
        );
        if (session.getState().generation !== generation)
          throw new DOMException("Session changed", "AbortError");
        session.getState().accept(result.accessToken, result.user);
      } catch (e) {
        if (
          session.getState().generation === generation &&
          e instanceof ApiError &&
          e.status === 401
        )
          session.getState().clear();
        throw e;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
}
export async function login(body: Schema<"LoginRequest">) {
  const result = checkedLogin(
    await request("/auth/login", { method: "POST", body }),
  );
  session.getState().accept(result.accessToken, result.user);
  return result.user;
}
export async function logout() {
  try {
    await request("/auth/logout", { method: "POST", auth: true, csrf: true });
  } finally {
    session.getState().clear();
  }
}
export function post<T>(
  path: string,
  body?: unknown,
  auth = true,
  key?: string,
  check: (v: Record<string, unknown>) => boolean = (v) =>
    typeof v.message === "string",
) {
  return request(path, { method: "POST", body, auth, key }).then((x) =>
    checked<T>(x, check),
  );
}
export const proofCheck = (field: string) => (v: Record<string, unknown>) =>
  typeof v[field] === "string" &&
  typeof v.expiresInSeconds === "number" &&
  v.expiresInSeconds > 0;
export const transferCheck = (v: Record<string, unknown>) =>
  strings(v, ["transferId", "status"]) &&
  (v.status === "AWAITING_OTP" ||
    (strings(v, ["amount", "sourceAccountId", "destinationAccountId"]) &&
      /^\d+$/.test(v.amount as string)));
export const accountCheck = (v: Record<string, unknown>) =>
  strings(v, [
    "accountId",
    "accountNumberMasked",
    "balance",
    "status",
    "currency",
  ]) && /^\d+$/.test(v.balance as string);
export async function get<T>(
  path: string,
  signal?: AbortSignal,
  check: (v: Record<string, unknown>) => boolean = () => true,
) {
  return checked<T>(await request(path, { auth: true, signal }), check);
}
export async function page<T>(
  path: string,
  signal?: AbortSignal,
  rowCheck: (v: Record<string, unknown>) => boolean = () => true,
): Promise<{ items: T[]; nextCursor: string | null }> {
  return checked(
    await request(path, { auth: true, signal }),
    (v) =>
      Array.isArray(v.items) &&
      v.items.every((row) => object(row) && rowCheck(row)) &&
      (v.nextCursor === null || typeof v.nextCursor === "string"),
  );
}
export function queryString(values: Record<string, string | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value) params.set(key, value);
  return params.size ? `?${params}` : "";
}
export function uncertain(error: unknown) {
  return (
    !(error instanceof ApiError) ||
    error.status === 0 ||
    error.status >= 500 ||
    error.status === 401
  );
}
