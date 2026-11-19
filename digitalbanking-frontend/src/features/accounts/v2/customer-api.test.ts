import { afterAll, afterEach, beforeAll, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { createApiClient } from "../../../shared/api/client";
import { accounts } from "../../../mocks/fixtures";
import { customerApi } from "./customer-api";

const base = "http://localhost/api/v2";
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
it("preserves an unknown status for the safe UI fallback and drops unexpected fields", async () => {
  server.use(
    http.get(`${base}/accounts`, () =>
      HttpResponse.json({
        ...accounts,
        items: [
          {
            ...accounts.items[0],
            status: "FUTURE_STATUS",
            accountNumber: "unmasked-should-not-be-retained",
          },
        ],
      }),
    ),
  );
  const client = createApiClient({ baseUrl: base });
  try {
    const result = await customerApi.accounts(
      client,
      new AbortController().signal,
    );
    expect(result.items[0].status).toBe("FUTURE_STATUS");
    expect(result.items[0]).not.toHaveProperty("accountNumber");
  } finally {
    client.dispose();
  }
});
it("rejects an invalid decimal amount instead of displaying a corrupted balance", async () => {
  server.use(
    http.get(`${base}/accounts`, () =>
      HttpResponse.json({
        ...accounts,
        items: [{ ...accounts.items[0], availableBalance: "100.5" }],
      }),
    ),
  );
  const client = createApiClient({ baseUrl: base });
  try {
    await expect(
      customerApi.accounts(client, new AbortController().signal),
    ).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  } finally {
    client.dispose();
  }
});
