import { expect, it, vi } from "vitest";
import { account, accounts, profile } from "../../../mocks/fixtures";
import { createV1CustomerReadApi } from "./customer-read-api";

it("reads v1 customer endpoints and retains only the masked owner projection", async () => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValueOnce(Response.json(profile))
    .mockResolvedValueOnce(Response.json(accounts))
    .mockResolvedValueOnce(
      Response.json({
        ...account,
        owner: { ...account.owner, fullName: "unexpected" },
      }),
    );
  const api = createV1CustomerReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  expect((await api.profile()).customerId).toBe("2001");
  expect((await api.accounts()).items[0].accountId).toBe("3001");
  expect((await api.detail("3001")).owner).toEqual({
    customerId: "2001",
    status: "ACTIVE",
  });
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
    "/api/v1/customers/me",
    "/api/v1/accounts",
    "/api/v1/accounts/3001",
  ]);
  api.dispose();
});

it("rejects wrong version, unsafe IDs and invalid v1 balances", async () => {
  expect(() => createV1CustomerReadApi({ baseUrl: "/api/v2" })).toThrow();
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    Response.json({
      ...accounts,
      items: [{ ...account, availableBalance: "1.1" }],
    }),
  );
  const api = createV1CustomerReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  expect(() => api.detail("../3001")).toThrow();
  expect(fetcher).not.toHaveBeenCalled();
  await expect(api.accounts()).rejects.toMatchObject({
    code: "INVALID_RESPONSE",
  });
  api.dispose();
});

it("preserves a v1 forbidden response for the caller", async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    Response.json(
      {
        type: "about:blank",
        title: "Forbidden",
        status: 403,
        detail: "Denied",
        instance: "/api/v1/accounts/3001",
        code: "AUTH_FORBIDDEN",
        correlationId: "fixture-id",
        timestamp: "2026-11-23T00:00:00Z",
        retryable: false,
      },
      { status: 403, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  const api = createV1CustomerReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  await expect(api.detail("3001")).rejects.toMatchObject({
    code: "AUTH_FORBIDDEN",
    meta: { status: 403 },
  });
  api.dispose();
});

it("notifies the auth host when a customer GET finds an expired session", async () => {
  const onSessionExpired = vi.fn();
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    Response.json(
      {
        type: "about:blank",
        title: "Unauthorized",
        status: 401,
        detail: "Session expired",
        instance: "/api/v1/accounts",
        code: "AUTH_SESSION_EXPIRED",
        correlationId: "fixture-id",
        timestamp: "2026-11-24T00:00:00Z",
        retryable: false,
      },
      { status: 401, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  const api = createV1CustomerReadApi({
    baseUrl: "/api/v1",
    fetch: fetcher,
    onSessionExpired,
  });
  await expect(api.accounts()).rejects.toMatchObject({
    code: "AUTH_SESSION_EXPIRED",
  });
  expect(onSessionExpired).toHaveBeenCalledOnce();
  api.dispose();
});
