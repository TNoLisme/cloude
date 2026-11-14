import { http, HttpResponse } from "msw";
import { z } from "zod";
import type { components } from "../shared/api/generated/v2/openapi";
import { authFixture } from "./fixtures";

type Schema<K extends keyof components["schemas"]> = components["schemas"][K];
const input = z.strictObject({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(128),
  deviceRef: z.uuid().optional(),
  deviceName: z.string().min(1).max(100).optional(),
  deviceFingerprint: z.string().min(1).max(512).optional(),
});
let signedIn = false;
const correlationHeaders = {
  "X-Correlation-ID": "0f8fad5b-d9cb-469f-a165-70867728950e",
};
export const v2CookieAttributes =
  "HttpOnly; Secure; SameSite=Strict; Path=/api";
export function resetV2MockSession() {
  signedIn = false;
}
export function v2AuthFixture(): Schema<"AuthResponse"> {
  return authFixture();
}
export function v2ChallengeFixture(): Schema<"ChallengeRequiredResponse"> {
  return {
    code: "AUTH_CHALLENGE_REQUIRED",
    message: "Fixture thiết bị mới cần OTP.",
    challenge: {
      challengeId: "11111111-2222-4333-8444-555555555555",
      purpose: "LOGIN_MFA",
      methods: ["OTP"],
      channel: "SMS",
      maskedDestination: "********123",
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      maxAttempts: 5,
      remainingAttempts: 5,
    },
    correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
  };
}
function problem(status: number, code: Schema<"ErrorCode">) {
  const body: Schema<"Problem"> = {
    type: "about:blank",
    title: code,
    status,
    code,
    detail: "Kịch bản mock v2; không phải xác thực backend.",
    instance: "/api/v2",
    timestamp: new Date().toISOString(),
    retryable: false,
    correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
  };
  return HttpResponse.json(body, {
    status,
    headers: {
      "Content-Type": "application/problem+json",
      ...correlationHeaders,
    },
  });
}
// Explicit fixture scenarios, not cookie verification or backend auth policy.
export const v2AuthHandlers = [
  http.post("*/api/v2/auth/login", async ({ request }) => {
    let raw: unknown;
    try {
      raw = await request.json();
    } catch {
      return problem(400, "VALIDATION_ERROR");
    }
    const parsed = input.safeParse(raw);
    if (!parsed.success) return problem(400, "VALIDATION_ERROR");
    if (parsed.data.username === "fixture-mfa")
      return HttpResponse.json(v2ChallengeFixture(), {
        status: 202,
        headers: correlationHeaders,
      });
    signedIn = true;
    return HttpResponse.json(v2AuthFixture(), {
      headers: {
        ...correlationHeaders,
        "Set-Cookie": `SESSION_TOKEN=mock-only-v2-session; ${v2CookieAttributes}`,
      },
    });
  }),
  http.get("*/api/v2/auth/session", () =>
    signedIn
      ? HttpResponse.json(v2AuthFixture(), { headers: correlationHeaders })
      : problem(401, "AUTH_SESSION_EXPIRED"),
  ),
  http.post("*/api/v2/auth/logout", ({ request }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (
      request.headers.get("X-CSRF-Token") !== v2AuthFixture().session.csrfToken
    )
      return problem(403, "AUTH_FORBIDDEN");
    signedIn = false;
    return new HttpResponse(null, {
      status: 204,
      headers: {
        ...correlationHeaders,
        "Set-Cookie": `SESSION_TOKEN=; ${v2CookieAttributes}; Max-Age=0`,
      },
    });
  }),
];
