import { expect, it, vi } from "vitest";
import { historyDetail, historyItems } from "../../../mocks/history-fixtures";
import { createV1HistoryReadApi } from "./history-read-api";

it("reads v1 history with a safe customer filter and reference detail", async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) =>
    String(url).includes("/transactions/")
      ? Response.json(historyDetail(historyItems[0]))
      : Response.json({
          items: historyItems,
          page: {
            number: 0,
            size: 20,
            totalElements: 2,
            totalPages: 1,
            first: true,
            last: true,
          },
          asOf: "2026-11-13T03:00:00Z",
        }),
  );
  const api = createV1HistoryReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  expect(
    (await api.list({ accountId: "3001", direction: "DEBIT", page: 0 })).items,
  ).toHaveLength(2);
  expect((await api.detail(historyItems[0].reference)).reference).toBe(
    historyItems[0].reference,
  );
  expect(String(fetcher.mock.calls[0][0])).toContain("/api/v1/transactions?");
  expect(String(fetcher.mock.calls[0][0])).toContain("direction=DEBIT");
  expect(String(fetcher.mock.calls[0][0])).not.toContain("customerId");
  expect(String(fetcher.mock.calls[0][0])).not.toContain("sort=");
  api.dispose();
});

it("rejects unsafe history query and reference before any request", () => {
  const fetcher = vi.fn<typeof fetch>();
  const api = createV1HistoryReadApi({ baseUrl: "/api/v1", fetch: fetcher });
  expect(() => api.list({ direction: "CREDIT" })).toThrow();
  expect(() => api.list({ minAmount: "2.00", maxAmount: "1.00" })).toThrow();
  expect(() => api.detail("../admin")).toThrow();
  expect(fetcher).not.toHaveBeenCalled();
  api.dispose();
});
