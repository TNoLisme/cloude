import { ApiError, createApiClient, type ApiResponse } from "../api/client";
import {
  authSchema,
  challengeSchema,
  loginSchema,
  otpSchema,
  type AuthResponse,
  type ChallengeResponse,
  type LoginRequest,
  type OtpVerifyRequest,
} from "./contracts";
import {
  v1AuthSchema,
  v1ChallengeSchema,
  type V1ChallengeResponse,
} from "./v1-contracts";
import type { SessionSync } from "./session-sync";

export type SessionChallenge = ChallengeResponse | V1ChallengeResponse;

type State = {
  phase:
    | "idle"
    | "loading"
    | "anonymous"
    | "authenticated"
    | "challenge"
    | "error";
  auth: AuthResponse | null;
  challenge: SessionChallenge | null;
};
const blank = (phase: State["phase"]): State => ({
  phase,
  auth: null,
  challenge: null,
});

/** Memory session primitives; the preview host owns query/router integration. */
type SessionConfig = {
  baseUrl: string;
  onSessionCleared: () => void;
  sync?: SessionSync;
  fetch?: typeof fetch;
};

function createSession(config: SessionConfig, version: "v1" | "v2") {
  const url = new URL(config.baseUrl, "http://relative-base.invalid");
  const expectedBase = `/api/${version}`;
  if (
    url.pathname.replace(/\/$/, "") !== expectedBase ||
    url.search ||
    url.hash
  )
    throw new TypeError(
      `Session controller requires the explicit ${expectedBase} base`,
    );
  const sessionSchema = version === "v1" ? v1AuthSchema : authSchema;
  const loginChallengeSchema =
    version === "v1" ? v1ChallengeSchema : challengeSchema;
  let state: State = blank("idle");
  let generation = 0;
  let disposed = false;
  let publicAuthCsrf: string | undefined;
  const listeners = new Set<() => void>();
  const emit = (next: State) => {
    state = next;
    for (const listener of listeners) listener();
  };
  const client = createApiClient({
    baseUrl: config.baseUrl,
    fetch: config.fetch,
    getCsrfToken: () => state.auth?.session.csrfToken,
    onSessionExpired: () => {
      if (state.phase !== "anonymous") clear("anonymous", true);
    },
  });
  function clear(phase: State["phase"], broadcast = false) {
    generation++;
    client.invalidate();
    state = blank(phase);
    publicAuthCsrf = undefined;
    config.onSessionCleared();
    if (broadcast) config.sync?.publish();
    emit(state);
  }
  const unsubscribe = config.sync?.subscribe(() => clear("anonymous"));
  function begin(broadcast = false) {
    if (disposed) throw new Error("Session controller has been disposed");
    clear("loading", broadcast);
    return generation;
  }
  const assertCurrent = (started: number) => {
    if (disposed || started !== generation)
      throw new DOMException("Session context changed", "AbortError");
  };
  function accept(response: ApiResponse<unknown>) {
    if (response.status !== 200)
      throw new ApiError("INVALID_RESPONSE", response);
    const parsed = sessionSchema.safeParse(response.data);
    if (!parsed.success) throw new ApiError("INVALID_RESPONSE", response);
    emit({ phase: "authenticated", auth: parsed.data, challenge: null });
    publicAuthCsrf = undefined;
    return parsed.data;
  }
  async function bootstrap() {
    const started = begin();
    try {
      const response = await client.request("/auth/session");
      assertCurrent(started);
      return accept(response);
    } catch (error) {
      if (error instanceof ApiError && error.code === "AUTH_SESSION_EXPIRED") {
        if (generation === started + 1 && state.phase === "anonymous")
          return null;
        assertCurrent(started);
      }
      if (started === generation) emit(blank("error"));
      throw error;
    }
  }
  async function login(input: LoginRequest) {
    const body = loginSchema.parse({
      ...input,
      username: input.username.toLowerCase(),
    });
    // A public login may still carry a valid ambient cookie. Preserve the known
    // CSRF value for that request even though local user data is cleared first.
    const csrfToken = state.auth?.session.csrfToken;
    const started = begin(true);
    publicAuthCsrf = csrfToken;
    try {
      const response = await client.request("/auth/login", {
        method: "POST",
        csrf: "optional",
        csrfToken,
        body,
      });
      assertCurrent(started);
      if (response.status === 202) {
        const parsed = loginChallengeSchema.safeParse(response.data);
        if (!parsed.success) throw new ApiError("INVALID_RESPONSE", response);
        emit({ phase: "challenge", auth: null, challenge: parsed.data });
        return { kind: "challenge" as const, data: parsed.data };
      }
      const data = accept(response);
      config.sync?.publish();
      return { kind: "authenticated" as const, data };
    } catch (error) {
      if (started === generation) {
        publicAuthCsrf = undefined;
        emit(blank("error"));
      }
      throw error;
    }
  }
  async function verifyOtp(input: OtpVerifyRequest) {
    const body = otpSchema.parse(input);
    if (
      !state.challenge ||
      state.challenge.challenge.challengeId !== body.challengeId
    )
      throw new Error("A matching login challenge is required");
    const started = generation;
    const response = await client.request("/auth/otp/verify", {
      method: "POST",
      csrf: "optional",
      csrfToken: publicAuthCsrf,
      body,
    });
    assertCurrent(started);
    const data = accept(response);
    config.sync?.publish();
    return data;
  }
  async function resendChallenge() {
    if (!state.challenge) throw new Error("A login challenge is required");
    const challengeId = state.challenge.challenge.challengeId;
    const started = generation;
    const response = await client.request(
      `/auth/challenges/${challengeId}/resend`,
      {
        method: "POST",
        csrf: "optional",
        csrfToken: publicAuthCsrf,
      },
    );
    assertCurrent(started);
    const parsed = loginChallengeSchema.safeParse(response.data);
    if (response.status !== 202 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    emit({ phase: "challenge", auth: null, challenge: parsed.data });
    return parsed.data;
  }
  async function logout() {
    if (disposed) throw new Error("Session controller has been disposed");
    const token = state.auth?.session.csrfToken;
    clear("anonymous", true);
    const started = generation;
    try {
      const response = await client.request("/auth/logout", {
        method: "POST",
        csrfToken: token,
      });
      assertCurrent(started);
      if (response.status !== 204)
        throw new ApiError("INVALID_RESPONSE", response);
      config.sync?.publish();
      return { serverConfirmed: true as const };
    } catch (error) {
      // No stale completion can clear a later user's session.
      assertCurrent(started);
      return { serverConfirmed: false as const, error };
    }
  }
  return {
    client,
    login,
    verifyOtp,
    resendChallenge,
    restartLogin: () => {
      if (!disposed) clear("anonymous");
    },
    bootstrap,
    logout,
    getSnapshot: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      unsubscribe?.();
      clear("anonymous");
      client.dispose();
      config.sync?.close();
      listeners.clear();
    },
  };
}

/** BE v1.1.0 session: DB_SESSION/Path=/ is managed by the browser and BE. */
export function createV1Session(config: SessionConfig) {
  return createSession(config, "v1");
}

/** FE/mock v2 remains a separate draft contract and explicit preview. */
export function createV2Session(config: SessionConfig) {
  return createSession(config, "v2");
}
