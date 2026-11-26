import { expect, it, vi } from "vitest";
import { limitsFixture } from "../../../mocks/limits-fixtures";
import { createV1LimitsReadApi } from "./limits-read-api";

it("reads customer self limits by optional account scope", async () => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValue(Response.json(limitsFixture));
  const api = createV1LimitsReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  expect((await api.get("3001")).items[0].limitId).toBe("9001");
  expect(String(fetcher.mock.calls[0][0])).toContain(
    "/api/v1/limits?accountId=3001",
  );
  api.dispose();
});

it("rejects unsafe scope and malformed limits response", async () => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockResolvedValue(
      Response.json({ ...limitsFixture, effectiveRemainingDaily: "1.2" }),
    );
  const api = createV1LimitsReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  await expect(api.get("../1")).rejects.toThrow("Invalid account ID");
  await expect(api.get()).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  api.dispose();
});
