// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { previewRequest } from "./legacy-preview";

afterEach(() => vi.unstubAllGlobals());
describe("UI fixture adapter boundary", () => {
  it("blocks every legacy mutation before a network request is made", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    for (const path of [
      "/auth/login",
      "/auth/register",
      "/auth/recover/initiate",
      "/transfers",
      "/recipients/resolve",
      "/operator/accounts/3001/seed-balance",
    ]) {
      await expect(
        previewRequest(path, { method: "POST" }),
      ).rejects.toMatchObject({ code: "PREVIEW_CONTRACT_PENDING" });
    }
    expect(fetch).not.toHaveBeenCalled();
  });
  it("does not silently lose new-contract decimal fractions in the legacy renderer", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            items: [{ availableBalance: "25000000.50" }],
            asOf: "2026-11-13T03:00:00Z",
          }),
          { status: 200 },
        ),
      ),
    );
    await expect(previewRequest("/accounts", {})).rejects.toMatchObject({
      code: "PREVIEW_DECIMAL_UNSUPPORTED",
    });
  });
});
