import { expect, it, vi } from "vitest";
import { approvalApi } from "./approval-api";
import type { ApiClient } from "../../../shared/api/client";

const summary = {
  approvalId: "8001",
  requestType: "LIMIT_CHANGE",
  targetType: "CUSTOMER",
  targetId: "2001",
  status: "PENDING",
  maker: { userId: "1001", displayName: "Nhân viên mẫu" },
  createdAt: "2026-11-19T03:00:00Z",
  correlationId: "fixture-correlation",
};
function client(data: unknown) {
  const request = vi.fn().mockResolvedValue({ status: 200, data });
  return { client: { request } as unknown as ApiClient, request };
}
it("requests only contract list filters and validates page metadata", async () => {
  const mock = client({
    items: [summary],
    page: {
      number: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    },
    asOf: "2026-11-19T03:00:00Z",
  });
  const result = await approvalApi.list(
    mock.client,
    { page: 0, requestType: "LIMIT_CHANGE", status: "PENDING" },
    new AbortController().signal,
  );
  expect(result.items[0].approvalId).toBe("8001");
  const [path, options] = mock.request.mock.calls[0] as [
    string,
    { query: URLSearchParams },
  ];
  expect(path).toBe("/admin/approvals");
  expect(options.query.toString()).toBe(
    "page=0&size=20&requestType=LIMIT_CHANGE&status=PENDING",
  );
  expect(options.query.has("sort")).toBe(false);
});
it("rejects malformed approval IDs before sending a request", async () => {
  const mock = client({ ...summary, payload: {} });
  expect(() =>
    approvalApi.detail(mock.client, "8001?x=1", new AbortController().signal),
  ).toThrow("Invalid approval ID");
  expect(mock.request).not.toHaveBeenCalled();
});
