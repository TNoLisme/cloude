// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { AuditPreview, prepareAuditPreview } from "./AuditPreview";
import {
  auditHandlers,
  resetAuditFixture,
  selectAuditScenario,
} from "./audit-handlers";

const server = setupServer(...auditHandlers);
const requests: string[] = [];
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  const interceptedFetch = globalThis.fetch;
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const resolved =
      typeof input === "string" ? new URL(input, location.origin) : input;
    requests.push(
      String(resolved instanceof Request ? resolved.url : resolved),
    );
    return interceptedFetch(resolved, init);
  });
});
afterAll(() => {
  vi.unstubAllGlobals();
  server.close();
});
afterEach(() => {
  cleanup();
  resetAuditFixture();
  server.resetHandlers();
  requests.length = 0;
  history.replaceState(null, "", "/");
});
function mount(path = "/staff/audit?auditPreview=v2") {
  history.replaceState(null, "", path);
  prepareAuditPreview();
  return render(<AuditPreview />);
}
it("reads the audit page and detail through the declared GET paths without rendering open state", async () => {
  mount();
  expect(await screen.findByText("CUSTOMER_READ · #8001")).toBeTruthy();
  expect(
    (
      screen.getByRole("button", {
        name: /Xuất bằng chứng/,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "Xem chi tiết #8001" }));
  expect(await screen.findByText("Bản ghi #8001")).toBeTruthy();
  expect(
    await screen.findByText("4b35959f-a011-4f53-bff7-4fdd519946ba"),
  ).toBeTruthy();
  expect(screen.queryByText("hidden")).toBeNull();
  expect(requests).toHaveLength(2);
  expect(requests[0]).toContain("/api/v2/audit?page=0&size=20");
  expect(requests[1]).toContain("/api/v2/audit/8001");
  expect(requests.every((url) => !url.includes("/audit/export"))).toBe(true);
});
it("shows empty and forbidden states", async () => {
  mount();
  await screen.findByText("CUSTOMER_READ · #8001");
  cleanup();
  prepareAuditPreview();
  selectAuditScenario("empty");
  render(<AuditPreview />);
  expect(await screen.findByText("Không có bản ghi phù hợp.")).toBeTruthy();
  cleanup();
  prepareAuditPreview();
  selectAuditScenario("forbidden");
  render(<AuditPreview />);
  expect(
    await screen.findByText("Bạn không có quyền xem nhật ký kiểm toán."),
  ).toBeTruthy();
});
