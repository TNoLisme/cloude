import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { setupServer } from "msw/node";
import { createV2Session } from "./session";
import {
  currentV2FixtureOtp,
  resetV2MockSession,
  selectV2MockScenario,
  v2AuthHandlers,
} from "../../mocks/v2-auth";

const server = setupServer(...v2AuthHandlers);
const baseUrl = "http://localhost/api/v2";
const instances: ReturnType<typeof createV2Session>[] = [];
const make = () => {
  const s = createV2Session({ baseUrl, onSessionCleared: vi.fn() });
  instances.push(s);
  return s;
};
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  instances.splice(0).forEach((s) => s.dispose());
  resetV2MockSession();
  server.resetHandlers();
});
afterAll(() => server.close());
async function challenge(s: ReturnType<typeof make>) {
  await s.login({ username: "fixture-mfa", password: "fixture-only" });
  return s.getSnapshot().challenge!.challenge.challengeId;
}
describe("login MFA controller and explicit MSW fixtures", () => {
  it("verifies the current challenge and refuses reuse without creating another session", async () => {
    const s = make();
    const id = await challenge(s);
    expect(
      (await s.verifyOtp({ challengeId: id, otp: currentV2FixtureOtp() })).user
        .userId,
    ).toBe("1001");
    expect(s.getSnapshot().phase).toBe("authenticated");
    const result = await fetch(`${baseUrl}/auth/otp/verify`, {
      method: "POST",
      body: JSON.stringify({ challengeId: id, otp: currentV2FixtureOtp() }),
    });
    expect(result.status).toBe(409);
    expect((await result.json()).code).toBe("INVALID_STATE_TRANSITION");
  });
  it("resend changes the fixture OTP without resetting used attempts", async () => {
    const s = make();
    const id = await challenge(s);
    await expect(
      s.verifyOtp({ challengeId: id, otp: "000000" }),
    ).rejects.toMatchObject({ code: "AUTH_OTP_INVALID" });
    expect(s.getSnapshot().phase).toBe("challenge");
    const before = currentV2FixtureOtp();
    const response = await s.resendChallenge();
    expect(response.challenge.remainingAttempts).toBe(4);
    expect(currentV2FixtureOtp()).not.toBe(before);
    await expect(
      s.verifyOtp({ challengeId: id, otp: before }),
    ).rejects.toMatchObject({ code: "AUTH_OTP_INVALID" });
    await s.verifyOtp({ challengeId: id, otp: currentV2FixtureOtp() });
    expect(s.getSnapshot().phase).toBe("authenticated");
  });
  it("locks after the attempt count declared by the synthetic challenge and cannot resend", async () => {
    const s = make();
    const id = await challenge(s);
    const max = s.getSnapshot().challenge!.challenge.maxAttempts;
    for (let i = 0; i < max - 1; i++)
      await expect(
        s.verifyOtp({ challengeId: id, otp: "000000" }),
      ).rejects.toMatchObject({ code: "AUTH_OTP_INVALID" });
    await expect(
      s.verifyOtp({ challengeId: id, otp: "000000" }),
    ).rejects.toMatchObject({ code: "AUTH_CHALLENGE_LOCKED" });
    await expect(s.resendChallenge()).rejects.toMatchObject({
      code: "AUTH_CHALLENGE_LOCKED",
    });
    expect(await make().bootstrap()).toBeNull();
  });
  it("does not accept or resend an expired fixture challenge", async () => {
    selectV2MockScenario("expired-otp");
    const s = make();
    const id = await challenge(s);
    await expect(
      s.verifyOtp({ challengeId: id, otp: "123456" }),
    ).rejects.toMatchObject({ code: "AUTH_OTP_EXPIRED" });
    await expect(s.resendChallenge()).rejects.toMatchObject({
      code: "AUTH_CHALLENGE_EXPIRED",
    });
  });
  it("requires the exact current challenge and clears local context when restarting", async () => {
    const s = make();
    const old = await challenge(s);
    const current = await challenge(s);
    expect(current).not.toBe(old);
    await expect(
      s.verifyOtp({ challengeId: old, otp: "123456" }),
    ).rejects.toThrow("matching login challenge");
    s.restartLogin();
    expect(s.getSnapshot().challenge).toBeNull();
    await expect(s.resendChallenge()).rejects.toThrow("login challenge");
  });
  it.each(["12345", "123456789", "12A456", "123456\n"])(
    "rejects OTP outside the wire schema: %j",
    async (otp) => {
      const s = make();
      const id = await challenge(s);
      await expect(s.verifyOtp({ challengeId: id, otp })).rejects.toBeDefined();
      expect(s.getSnapshot().phase).toBe("challenge");
    },
  );
});
