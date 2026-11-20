// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import {
  StaffCustomerPreview,
  prepareStaffCustomerPreview,
} from "./StaffCustomerPreview";
import {
  resetStaffCustomerFixture,
  selectStaffCustomerScenario,
  staffCustomerHandlers,
} from "./staff-customer-handlers";

const server = setupServer(...staffCustomerHandlers);
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
  resetStaffCustomerFixture();
  server.resetHandlers();
  requests.length = 0;
  history.replaceState(null, "", "/");
});
function mount(path = "/staff/customers/2001?staffPreview=v2") {
  history.replaceState(null, "", path);
  prepareStaffCustomerPreview();
  return render(<StaffCustomerPreview />);
}
it("reads masked customer and accounts by ID without sending phone search", async () => {
  mount();
  expect(await screen.findByText("Hồ sơ #2001")).toBeTruthy();
  expect(await screen.findByText("**** **** 3001")).toBeTruthy();
  expect(screen.getByText("*******123")).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: "Tra cứu" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(
    (screen.getByLabelText("Số điện thoại") as HTMLInputElement).disabled,
  ).toBe(true);
  expect(requests).toHaveLength(2);
  expect(
    requests.every((url) => url.includes("/api/v2/admin/customers/2001")),
  ).toBe(true);
  expect(requests.some((url) => url.includes("?q="))).toBe(false);
});
it("shows a safe empty-account state and a forbidden state", async () => {
  mount();
  await screen.findByText("Hồ sơ #2001");
  cleanup();
  prepareStaffCustomerPreview();
  selectStaffCustomerScenario("empty");
  render(<StaffCustomerPreview />);
  expect(await screen.findByText("Khách hàng chưa có tài khoản.")).toBeTruthy();
  cleanup();
  prepareStaffCustomerPreview();
  selectStaffCustomerScenario("forbidden");
  render(<StaffCustomerPreview />);
  expect(
    await screen.findByText("Bạn không có quyền xem khách hàng này."),
  ).toBeTruthy();
});
it("shows a safe not-found state for an unknown ID", async () => {
  mount("/staff/customers/9999?staffPreview=v2");
  expect(await screen.findByText("Không tìm thấy khách hàng.")).toBeTruthy();
});
