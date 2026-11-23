import { delay, http, HttpResponse } from "msw";
import { z } from "zod";
import type { components } from "../shared/api/generated/v2/openapi";
import { account, accounts, authFixture, profile } from "./fixtures";
import { v2Account } from "./v2-account-fixture";
import { historyDetail, historyItems } from "./history-fixtures";
import { limitsFixture } from "./limits-fixtures";
import { isApiId } from "../shared/lib/id";
import { isMoney } from "../shared/lib/money";

type Schema<K extends keyof components["schemas"]> = components["schemas"][K];
const input = z.strictObject({
  username: z.string().min(1).max(64),
  password: z.string().min(1).max(128),
  deviceRef: z.uuid().optional(),
  deviceName: z.string().min(1).max(100).optional(),
  deviceFingerprint: z.string().min(1).max(512).optional(),
});
let signedIn = false;
let activeChallenge: Schema<"ChallengeRequiredResponse"> | null = null;
let fixtureOtp = "123456";
let resendGeneration = 0;
let challengeVerified = false;
type Scenario =
  | "default"
  | "bootstrap-error"
  | "bootstrap-slow"
  | "logout-error"
  | "expired-otp";
let scenario: Scenario = "default";
type CustomerScenario = "default" | "empty" | "slow" | "error";
let customerScenario: CustomerScenario = "default";
type HistoryScenario = "default" | "empty" | "slow" | "error";
let historyScenario: HistoryScenario = "default";
let limitsScenario: HistoryScenario = "default";
export function selectLimitsScenario(value: HistoryScenario) {
  limitsScenario = value;
}
export function selectHistoryScenario(value: HistoryScenario) {
  historyScenario = value;
}
export function selectCustomerScenario(value: CustomerScenario) {
  customerScenario = value;
}
let persistFixture = false;
let fixtureChannel: BroadcastChannel | undefined;
export const v2FixtureStorageKey = "digitalbanking.fixture.v2.signedIn";
function writeFixtureFlag(value: boolean) {
  if (!persistFixture || typeof window === "undefined") return;
  try {
    if (value) window.sessionStorage.setItem(v2FixtureStorageKey, "1");
    else window.sessionStorage.removeItem(v2FixtureStorageKey);
  } catch {
    /* Storage unavailable: fixture remains memory-only. */
  }
}
function setSignedIn(value: boolean) {
  signedIn = value;
  writeFixtureFlag(value);
  fixtureChannel?.postMessage(value ? "signed-in" : "signed-out");
}
// Opt-in only from the dev/test preview entry. These are synthetic fixture
// signals, not a cookie/session implementation or a v1/v2 coexistence policy.
export function enableV2FixturePersistence() {
  if (
    typeof window === "undefined" ||
    (!import.meta.env.DEV && import.meta.env.MODE !== "test")
  )
    return;
  persistFixture = true;
  try {
    signedIn = window.sessionStorage.getItem(v2FixtureStorageKey) === "1";
  } catch {
    signedIn = false;
  }
  fixtureChannel?.close();
  if (typeof BroadcastChannel !== "undefined") {
    fixtureChannel = new BroadcastChannel("digitalbanking-fixture-v2");
    fixtureChannel.onmessage = ({ data }: MessageEvent<unknown>) => {
      if (data !== "signed-in" && data !== "signed-out") return;
      signedIn = data === "signed-in";
      writeFixtureFlag(signedIn);
    };
  }
}
export function disableV2FixturePersistence() {
  fixtureChannel?.close();
  fixtureChannel = undefined;
  persistFixture = false;
}
export function seedAuthenticatedV2Fixture() {
  setSignedIn(true);
}
export function selectV2MockScenario(value: Scenario) {
  scenario = value;
}
export function currentV2FixtureOtp() {
  return fixtureOtp;
}
const correlationHeaders = {
  "X-Correlation-ID": "0f8fad5b-d9cb-469f-a165-70867728950e",
};
export const v2CookieAttributes =
  "HttpOnly; Secure; SameSite=Strict; Path=/api";
export function resetV2MockSession() {
  setSignedIn(false);
  activeChallenge = null;
  fixtureOtp = "123456";
  resendGeneration = 0;
  challengeVerified = false;
  scenario = "default";
  customerScenario = "default";
  historyScenario = "default";
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
    retryable: [
      "AUTH_SESSION_EXPIRED",
      "AUTH_OTP_INVALID",
      "AUTH_OTP_EXPIRED",
      "AUTH_CHALLENGE_EXPIRED",
      "SERVICE_UNAVAILABLE",
      "RATE_LIMITED",
      "OTP_DISPATCH_FAILED",
    ].includes(code),
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
    if (parsed.data.username === "fixture-invalid")
      return problem(401, "AUTH_INVALID_CREDENTIALS");
    if (parsed.data.username === "fixture-locked")
      return problem(423, "AUTH_ACCOUNT_LOCKED");
    if (parsed.data.username === "fixture-mfa") {
      activeChallenge = v2ChallengeFixture();
      activeChallenge.challenge.challengeId = crypto.randomUUID();
      challengeVerified = false;
      fixtureOtp = "123456";
      resendGeneration = 0;
      if (scenario === "expired-otp")
        activeChallenge.challenge.expiresAt = new Date(
          Date.now() - 1000,
        ).toISOString();
      return HttpResponse.json(activeChallenge, {
        status: 202,
        headers: correlationHeaders,
      });
    }
    setSignedIn(true);
    return HttpResponse.json(v2AuthFixture(), {
      headers: {
        ...correlationHeaders,
        "Set-Cookie": `SESSION_TOKEN=mock-only-v2-session; ${v2CookieAttributes}`,
      },
    });
  }),
  http.get("*/api/v2/auth/session", async () => {
    if (scenario === "bootstrap-slow") {
      scenario = "default";
      await delay(1500);
    }
    if (scenario === "bootstrap-error") {
      scenario = "default";
      return problem(503, "SERVICE_UNAVAILABLE");
    }
    writeFixtureFlag(signedIn);
    return signedIn
      ? HttpResponse.json(v2AuthFixture(), { headers: correlationHeaders })
      : problem(401, "AUTH_SESSION_EXPIRED");
  }),
  http.post("*/api/v2/auth/otp/verify", async ({ request }) => {
    const parsed = z
      .strictObject({
        challengeId: z.uuid(),
        otp: z
          .string()
          .regex(/^[0-9]{6,8}$/)
          .refine((v) => !v.includes("\n")),
      })
      .safeParse(await request.json().catch(() => null));
    if (!parsed.success) return problem(400, "VALIDATION_ERROR");
    if (
      !activeChallenge ||
      activeChallenge.challenge.challengeId !== parsed.data.challengeId
    )
      return problem(404, "AUTH_CHALLENGE_NOT_FOUND");
    if (challengeVerified) return problem(409, "INVALID_STATE_TRANSITION");
    const challenge = activeChallenge.challenge;
    if (Date.parse(challenge.expiresAt) <= Date.now())
      return problem(410, "AUTH_OTP_EXPIRED");
    if (challenge.remainingAttempts === 0)
      return problem(423, "AUTH_CHALLENGE_LOCKED");
    if (parsed.data.otp !== fixtureOtp) {
      challenge.remainingAttempts--;
      return challenge.remainingAttempts === 0
        ? problem(423, "AUTH_CHALLENGE_LOCKED")
        : problem(401, "AUTH_OTP_INVALID");
    }
    challengeVerified = true;
    setSignedIn(true);
    return HttpResponse.json(v2AuthFixture(), {
      headers: {
        ...correlationHeaders,
        "Set-Cookie": `SESSION_TOKEN=mock-only-v2-session; ${v2CookieAttributes}`,
      },
    });
  }),
  http.post("*/api/v2/auth/challenges/:challengeId/resend", ({ params }) => {
    if (
      !activeChallenge ||
      params.challengeId !== activeChallenge.challenge.challengeId
    )
      return problem(404, "AUTH_CHALLENGE_NOT_FOUND");
    if (challengeVerified) return problem(409, "INVALID_STATE_TRANSITION");
    if (Date.parse(activeChallenge.challenge.expiresAt) <= Date.now())
      return problem(410, "AUTH_CHALLENGE_EXPIRED");
    if (activeChallenge.challenge.remainingAttempts === 0)
      return problem(423, "AUTH_CHALLENGE_LOCKED");
    // Synthetic fixture generations; max-resend policy remains unchosen for FE.
    resendGeneration++;
    fixtureOtp = String(123456 + 111111 * resendGeneration)
      .slice(-6)
      .padStart(6, "0");
    activeChallenge.challenge.expiresAt =
      v2ChallengeFixture().challenge.expiresAt;
    return HttpResponse.json(activeChallenge, {
      status: 202,
      headers: correlationHeaders,
    });
  }),
  http.post("*/api/v2/auth/logout", ({ request }) => {
    // User-approved preview flag is cleared on logout, even if the mock server
    // outcome is unconfirmed. Same-page fixture memory can still restore a
    // session after a simulated error; this flag is not server revocation proof.
    writeFixtureFlag(false);
    if (scenario === "logout-error") {
      scenario = "default";
      return problem(503, "SERVICE_UNAVAILABLE");
    }
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (
      request.headers.get("X-CSRF-Token") !== v2AuthFixture().session.csrfToken
    )
      return problem(403, "AUTH_FORBIDDEN");
    setSignedIn(false);
    return new HttpResponse(null, {
      status: 204,
      headers: {
        ...correlationHeaders,
        "Set-Cookie": `SESSION_TOKEN=; ${v2CookieAttributes}; Max-Age=0`,
      },
    });
  }),
  // Synthetic customer-self fixtures; they do not prove backend authorization.
  http.get("*/api/v2/limits", async ({ request }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (limitsScenario === "slow") await delay(1500);
    if (limitsScenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
    const query = new URL(request.url).searchParams;
    if ([...query.keys()].some((key) => key !== "accountId"))
      return problem(400, "VALIDATION_ERROR");
    const accountId = query.get("accountId");
    if (accountId && !isApiId(accountId))
      return problem(400, "VALIDATION_ERROR");
    if (accountId && accountId !== account.accountId)
      return problem(403, "AUTH_FORBIDDEN");
    return HttpResponse.json(
      limitsScenario === "empty"
        ? {
            ...limitsFixture,
            effectivePerTransactionLimit: null,
            effectiveRemainingDaily: null,
            items: [],
          }
        : limitsFixture,
      { headers: correlationHeaders },
    );
  }),
  http.get("*/api/v2/customers/me", async () => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (customerScenario === "slow") await delay(1500);
    if (customerScenario === "error")
      return problem(503, "SERVICE_UNAVAILABLE");
    return HttpResponse.json(profile, { headers: correlationHeaders });
  }),
  http.get("*/api/v2/accounts", async ({ request }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (customerScenario === "slow") await delay(1500);
    if (customerScenario === "error")
      return problem(503, "SERVICE_UNAVAILABLE");
    const status = new URL(request.url).searchParams.get("status");
    // Invalid filters have no defined response in the current contract. Fail
    // locally rather than inventing a successful empty result or reaching BE.
    if (status !== null && !["ACTIVE", "LOCKED", "CLOSED"].includes(status))
      return HttpResponse.error();
    return HttpResponse.json(
      customerScenario === "empty"
        ? { ...accounts, items: [] }
        : status
          ? {
              ...accounts,
              items: accounts.items.filter((a) => a.status === status),
            }
          : accounts,
      { headers: correlationHeaders },
    );
  }),
  http.get("*/api/v2/accounts/:accountId", async ({ params }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (customerScenario === "slow") await delay(1500);
    if (customerScenario === "error")
      return problem(503, "SERVICE_UNAVAILABLE");
    const id = String(params.accountId);
    if (!isApiId(id)) return problem(400, "VALIDATION_ERROR");
    if (id === account.accountId)
      return HttpResponse.json(v2Account, { headers: correlationHeaders });
    // 3002 is a synthetic existing account belonging to a different customer.
    return id === "3002"
      ? problem(403, "AUTH_FORBIDDEN")
      : problem(404, "ACCOUNT_NOT_FOUND");
  }),
  http.get("*/api/v2/transactions", async ({ request }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (historyScenario === "slow") await delay(1500);
    if (historyScenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
    const query = new URL(request.url).searchParams;
    const page = query.get("page") ?? "0",
      size = query.get("size") ?? "20",
      status = query.get("status"),
      accountId = query.get("accountId"),
      direction = query.get("direction"),
      from = query.get("from"),
      to = query.get("to"),
      minAmount = query.get("minAmount"),
      maxAmount = query.get("maxAmount");
    if (
      [...query.keys()].some(
        (key) =>
          ![
            "page",
            "size",
            "status",
            "accountId",
            "direction",
            "from",
            "to",
            "minAmount",
            "maxAmount",
          ].includes(key),
      ) ||
      !/^(0|[1-9][0-9]*)$/.test(page) ||
      !Number.isSafeInteger(Number(page)) ||
      !/^[1-9][0-9]*$/.test(size) ||
      Number(size) > 100 ||
      (status && !["PENDING", "COMPLETED", "FAILED"].includes(status)) ||
      (accountId && !isApiId(accountId)) ||
      (direction &&
        (!accountId || !["DEBIT", "CREDIT", "INTERNAL"].includes(direction))) ||
      (from && Number.isNaN(Date.parse(from))) ||
      (to && Number.isNaN(Date.parse(to))) ||
      (from && to && Date.parse(from) >= Date.parse(to)) ||
      (minAmount && !isMoney(minAmount)) ||
      (maxAmount && !isMoney(maxAmount)) ||
      (minAmount &&
        maxAmount &&
        BigInt(minAmount.replace(".", "")) > BigInt(maxAmount.replace(".", "")))
    )
      return problem(400, "VALIDATION_ERROR");
    if (accountId && accountId !== "3001")
      return accountId === "3002"
        ? problem(403, "AUTH_FORBIDDEN")
        : problem(404, "ACCOUNT_NOT_FOUND");
    const items =
      historyScenario === "empty"
        ? []
        : historyItems.filter(
            (item) =>
              (!status || item.status === status) &&
              (!direction || item.direction === direction) &&
              (!from || Date.parse(item.createdAt) >= Date.parse(from)) &&
              (!to || Date.parse(item.createdAt) < Date.parse(to)) &&
              (!minAmount ||
                BigInt(item.amount.replace(".", "")) >=
                  BigInt(minAmount.replace(".", ""))) &&
              (!maxAmount ||
                BigInt(item.amount.replace(".", "")) <=
                  BigInt(maxAmount.replace(".", ""))),
          );
    const number = Number(page),
      pageSize = Number(size),
      pages = Math.ceil(items.length / pageSize);
    return HttpResponse.json(
      {
        items: items.slice(number * pageSize, (number + 1) * pageSize),
        page: {
          number,
          size: pageSize,
          totalElements: items.length,
          totalPages: pages,
          first: number === 0,
          last: number >= pages - 1,
        },
        asOf: "2026-11-13T03:00:00Z",
      },
      { headers: correlationHeaders },
    );
  }),
  http.get("*/api/v2/transactions/:reference", async ({ params }) => {
    if (!signedIn) return problem(401, "AUTH_SESSION_EXPIRED");
    if (historyScenario === "slow") await delay(1500);
    if (historyScenario === "error") return problem(503, "SERVICE_UNAVAILABLE");
    const reference = String(params.reference);
    if (!/^TXN[0-9]{8}[A-Z0-9]{8,20}$/.test(reference))
      return problem(400, "VALIDATION_ERROR");
    if (reference === "TXN20261113FORBID00")
      return problem(403, "AUTH_FORBIDDEN");
    const item = historyItems.find((value) => value.reference === reference);
    return item
      ? HttpResponse.json(historyDetail(item), { headers: correlationHeaders })
      : problem(404, "TRANSFER_NOT_FOUND");
  }),
];
