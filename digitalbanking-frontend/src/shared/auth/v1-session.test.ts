import { describe, expect, it, vi } from "vitest";
import { createV1Session } from "./session";

const challenge = {
  code: "AUTH_CHALLENGE_REQUIRED",
  message: "OTP required",
  correlationId: "test-correlation",
  challenge: {
    challengeId: "11111111-2222-4333-8444-555555555555",
    purpose: "LOGIN_NEW_DEVICE",
    methods: ["OTP"],
    channel: "SMS",
    maskedDestination: "******0011",
    expiresAt: "2026-11-23T14:00:00Z",
    maxAttempts: 5,
    remainingAttempts: 5,
  },
};
const auth = {
  user: {
    userId: "1001",
    customerId: "2001",
    userType: "CUSTOMER",
    displayName: "QA Customer",
    roles: ["CUSTOMER"],
    privileges: [],
  },
  session: {
    idleExpiresAt: "2026-11-23T14:15:00Z",
    absoluteExpiresAt: "2026-11-23T22:00:00Z",
    csrfToken: "csrf-one",
    deviceTrusted: false,
  },
  correlationId: "test-correlation",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

describe("BE v1.1.0 auth boundary", () => {
  it("uses 202 LOGIN_NEW_DEVICE, OTP session, rotated CSRF and 204 logout", async () => {
    const calls: { path: string; init: RequestInit }[] = [];
    const fetcher: typeof fetch = vi.fn(async (input, init) => {
      const path = String(input);
      calls.push({ path, init: init! });
      if (path.endsWith("/auth/login")) return json(challenge, 202);
      if (path.endsWith("/auth/otp/verify"))
        return json({ ...auth, accessToken: "discard-this" });
      if (path.endsWith("/auth/session"))
        return json({
          ...auth,
          session: { ...auth.session, csrfToken: "csrf-two" },
        });
      if (path.endsWith("/auth/logout"))
        return new Response(null, { status: 204 });
      throw new Error(`Unexpected ${path}`);
    }) as typeof fetch;
    const session = createV1Session({
      baseUrl: "/api/v1",
      fetch: fetcher,
      onSessionCleared: vi.fn(),
    });
    try {
      const login = await session.login({
        username: "QA-SENDER",
        password: " keep ",
      });
      expect(login.kind).toBe("challenge");
      expect(session.getSnapshot().auth).toBeNull();
      expect(session.getSnapshot().challenge?.challenge.purpose).toBe(
        "LOGIN_NEW_DEVICE",
      );
      await session.verifyOtp({
        challengeId: challenge.challenge.challengeId,
        otp: "123456",
      });
      expect(session.getSnapshot().phase).toBe("authenticated");
      expect(JSON.stringify(session.getSnapshot())).not.toContain(
        "accessToken",
      );
      await session.bootstrap();
      expect(session.getSnapshot().auth?.session.csrfToken).toBe("csrf-two");
      expect(await session.logout()).toEqual({ serverConfirmed: true });
      expect(session.getSnapshot().phase).toBe("anonymous");
      expect(calls.map(({ path }) => path)).toEqual([
        "/api/v1/auth/login",
        "/api/v1/auth/otp/verify",
        "/api/v1/auth/session",
        "/api/v1/auth/logout",
      ]);
      expect(calls.every(({ init }) => init.credentials === "include")).toBe(
        true,
      );
      expect(
        calls.every(
          ({ init }) => !(init.headers as Headers).has("Authorization"),
        ),
      ).toBe(true);
      expect(JSON.parse(calls[0].init.body as string)).toEqual({
        username: "qa-sender",
        password: " keep ",
      });
      expect((calls[3].init.headers as Headers).get("X-CSRF-Token")).toBe(
        "csrf-two",
      );
    } finally {
      session.dispose();
    }
  });

  it("rejects v2 login purpose and wrong base without retaining a session", async () => {
    const session = createV1Session({
      baseUrl: "/api/v1",
      fetch: vi.fn(async () =>
        json(
          {
            ...challenge,
            challenge: { ...challenge.challenge, purpose: "LOGIN_MFA" },
          },
          202,
        ),
      ) as typeof fetch,
      onSessionCleared: vi.fn(),
    });
    try {
      await expect(
        session.login({ username: "qa-sender", password: "password" }),
      ).rejects.toMatchObject({
        code: "INVALID_RESPONSE",
      });
      expect(session.getSnapshot().auth).toBeNull();
      expect(session.getSnapshot().challenge).toBeNull();
    } finally {
      session.dispose();
    }
    expect(() =>
      createV1Session({ baseUrl: "/api/v2", onSessionCleared: vi.fn() }),
    ).toThrow("/api/v1");
  });
});
