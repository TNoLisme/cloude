// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { ApprovalPreview, prepareApprovalPreview } from "./ApprovalPreview";
import {
  approvalHandlers,
  resetApprovalFixture,
  selectApprovalScenario,
} from "./approval-handlers";

const server = setupServer(...approvalHandlers);
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
  cleanup();
  resetApprovalFixture();
  server.resetHandlers();
  history.replaceState(null, "", "/");
});
function mount(path = "/staff/approvals?approvalPreview=v2") {
  history.replaceState(null, "", path);
  prepareApprovalPreview();
  return render(<ApprovalPreview />);
}
it("shows only approval metadata and disables decisions", async () => {
  mount();
  expect(await screen.findByText("Yêu cầu #8001")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Xem chi tiết #8001" }));
  expect(
    await screen.findByRole("heading", { name: "Chi tiết yêu cầu phê duyệt" }),
  ).toBeTruthy();
  expect(
    await screen.findByText(
      "Nội dung thay đổi và kết quả chi tiết chờ contract field/allowlist.",
    ),
  ).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: "Phê duyệt" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(
    (screen.getByRole("button", { name: "Từ chối" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(screen.queryByText("payload")).toBeNull();
});
it("shows empty and error states from explicit fixture scenarios", async () => {
  mount();
  await screen.findByText("Yêu cầu #8001");
  cleanup();
  prepareApprovalPreview();
  selectApprovalScenario("empty");
  render(<ApprovalPreview />);
  expect(await screen.findByText("Không có yêu cầu phù hợp.")).toBeTruthy();
  cleanup();
  prepareApprovalPreview();
  selectApprovalScenario("error");
  render(<ApprovalPreview />);
  expect(
    await screen.findByText("Không thể tải danh sách phê duyệt."),
  ).toBeTruthy();
});
it("applies contract request type filter and gives a safe missing-detail state", async () => {
  mount("/staff/approvals?approvalPreview=v2&requestType=LIMIT_CHANGE");
  expect(await screen.findByText("Yêu cầu #8001")).toBeTruthy();
  expect(screen.queryByText("Yêu cầu #8002")).toBeNull();
  cleanup();
  mount("/staff/approvals/9999?approvalPreview=v2");
  expect(
    await screen.findByText(
      "Không thể xem yêu cầu này hoặc bạn không có quyền truy cập.",
    ),
  ).toBeTruthy();
  expect(screen.queryByText("APPROVAL_NOT_FOUND")).toBeNull();
});
