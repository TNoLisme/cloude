// @vitest-environment jsdom
import "../../../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { setupServer } from "msw/node";
import { createApiClient } from "../../../shared/api/client";
import { fraudApi } from "./fraud-api";
import {
  fraudHandlers,
  prepareFraudFixture,
  resetFraudFixture,
} from "../../../mocks/fraud-handlers";

const server = setupServer(...fraudHandlers);
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  const interceptedFetch = globalThis.fetch;
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) =>
    interceptedFetch(
      typeof input === "string" ? new URL(input, location.origin) : input,
      init,
    ),
  );
});
afterAll(() => {
  vi.unstubAllGlobals();
  server.close();
});
afterEach(() => {
  resetFraudFixture();
  server.resetHandlers();
});
it("filters contract GET by status and keeps page metadata", async () => {
  prepareFraudFixture();
  const client = createApiClient({ baseUrl: "/api/v2" });
  const result = await fraudApi.list(
    client,
    { page: 0, status: "OPEN" },
    new AbortController().signal,
  );
  expect(result.items.map((item) => item.flagId)).toEqual(["9001"]);
  expect(result.page.totalElements).toBe(1);
  client.dispose();
});
it("rejects invalid pagination before any request", async () => {
  const client = createApiClient({ baseUrl: "/api/v2" });
  await expect(
    fraudApi.list(client, { page: -1 }, new AbortController().signal),
  ).rejects.toThrow("Invalid page");
  client.dispose();
});
