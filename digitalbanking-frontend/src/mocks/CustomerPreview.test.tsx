// @vitest-environment jsdom
import "../test/setup-dom";
import { StrictMode } from "react";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ConfigProvider } from "antd";
import { setupServer } from "msw/node";
import { CustomerPreview, prepareCustomerPreview } from "./CustomerPreview";
import {
  resetV2MockSession,
  selectCustomerScenario,
  selectLimitsScenario,
  v2AuthHandlers,
} from "./v2-auth";

const server = setupServer(...v2AuthHandlers);
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
  resetV2MockSession();
  server.resetHandlers();
  history.replaceState(null, "", "/");
});
function mount(path = "/customer?customerPreview=v2") {
  history.replaceState(null, "", path);
  prepareCustomerPreview();
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <CustomerPreview />
    </ConfigProvider>,
  );
}
it("shows customer account, detail and masked profile through the v2 mock", async () => {
  mount();
  expect(
    (await screen.findAllByText(/\*\*\*\* \*\*\*\* 3001/)).length,
  ).toBeGreaterThan(0);
  expect(screen.getByText("25.000.000,00 ₫")).toBeTruthy();
  expect(
    await screen.findByRole("heading", { name: "Giao dịch gần đây" }),
  ).toBeTruthy();
  expect(await screen.findByText("Nguyễn Thị B")).toBeTruthy();
  fireEvent.click(
    screen.getByRole("button", { name: "Xem chi tiết tài khoản" }),
  );
  expect(
    await screen.findByRole("heading", { name: "Chi tiết tài khoản" }),
  ).toBeTruthy();
  expect(await screen.findByText("Nguyễn Văn A")).toBeTruthy();
  fireEvent.click(screen.getAllByRole("button", { name: "Hồ sơ" }).at(-1)!);
  expect(await screen.findByText("*******123")).toBeTruthy();
  expect(screen.getByText("n***@example.test")).toBeTruthy();
  expect(screen.queryByText("PIN giao dịch")).toBeNull();
});
it("shows explicit forbidden and missing fixture outcomes", async () => {
  mount();
  await screen.findAllByText(/\*\*\*\* \*\*\*\* 3001/);
  fireEvent.click(screen.getByText("Điều khiển bản xem thử"));
  fireEvent.click(screen.getByRole("button", { name: "Xem fixture 403" }));
  expect(
    await screen.findByText("Bạn không có quyền xem tài khoản này."),
  ).toBeTruthy();
  expect(screen.queryByText("**** **** 3001")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Xem fixture 404" }));
  expect(await screen.findByText("Không tìm thấy tài khoản.")).toBeTruthy();
});
it("shows an empty list and service error without stale account data", async () => {
  history.replaceState(null, "", "/customer?customerPreview=v2");
  prepareCustomerPreview();
  selectCustomerScenario("empty");
  const page = render(<CustomerPreview />);
  expect(await screen.findByText("Chưa có tài khoản.")).toBeTruthy();
  page.unmount();
  selectCustomerScenario("error");
  render(<CustomerPreview />);
  expect(
    await screen.findByText("Không thể tải dữ liệu. Vui lòng thử lại."),
  ).toBeTruthy();
  expect(screen.queryByText("**** **** 3001")).toBeNull();
});
it("supports a direct detail URL and renders the fixture ownership denial", async () => {
  history.replaceState(null, "", "/customer/accounts/3002?customerPreview=v2");
  prepareCustomerPreview();
  render(<CustomerPreview />);
  expect(
    await screen.findByText("Bạn không có quyền xem tài khoản này."),
  ).toBeTruthy();
  expect(screen.queryByText("**** **** 3001")).toBeNull();
});
it("loads customer data under StrictMode effect replay", async () => {
  history.replaceState(null, "", "/customer?customerPreview=v2");
  prepareCustomerPreview();
  render(
    <StrictMode>
      <CustomerPreview />
    </StrictMode>,
  );
  expect(
    (await screen.findAllByText(/\*\*\*\* \*\*\*\* 3001/)).length,
  ).toBeGreaterThan(0);
});
it("opens customer history and a masked transaction detail without transfer actions", async () => {
  mount();
  await screen.findByRole("heading", { name: "Tổng quan" });
  fireEvent.click(screen.getByRole("button", { name: "Lịch sử giao dịch" }));
  expect(
    await screen.findByRole("heading", { name: "Lịch sử giao dịch" }),
  ).toBeTruthy();
  expect(await screen.findByText("Nguyễn Thị B")).toBeTruthy();
  expect(screen.getByText("**** **** 4821", { exact: false })).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: /Nguyễn Thị B/ }));
  expect(
    await screen.findByRole("heading", { name: "Chi tiết giao dịch" }),
  ).toBeTruthy();
  expect(await screen.findByText("TXN20261113A1B2C3D4")).toBeTruthy();
  expect(
    screen.queryByRole("button", { name: /xác nhận|chuyển tiền/i }),
  ).toBeNull();
});
it("keeps a contract status filter and shows a safe missing detail", async () => {
  mount("/customer/history?customerPreview=v2&status=COMPLETED");
  expect(await screen.findByText("Nguyễn Thị B")).toBeTruthy();
  expect(screen.queryByText("Trần Văn C")).toBeNull();
  cleanup();
  mount("/customer/history/TXN20261113NOTFOUND?customerPreview=v2");
  expect(await screen.findByText("Không tìm thấy giao dịch.")).toBeTruthy();
  expect(screen.queryByText("Nguyễn Thị B")).toBeNull();
});
it("filters by owned account, direction, money and an exclusive time range", async () => {
  mount(
    "/customer/history?customerPreview=v2&accountId=3001&direction=DEBIT&minAmount=100000.00&maxAmount=200000.00&from=2026-11-12T17%3A00%3A00.000Z&to=2026-11-13T17%3A00%3A00.000Z",
  );
  expect(await screen.findByText("Nguyễn Thị B")).toBeTruthy();
  expect(screen.queryByText("Trần Văn C")).toBeNull();
});
it("shows customer limits, nullable empty state and service error from the mock", async () => {
  mount("/customer/limits?customerPreview=v2");
  expect(
    await screen.findByRole("heading", { name: "Hạn mức của tôi" }),
  ).toBeTruthy();
  expect((await screen.findAllByText("17.500.000,00 ₫")).length).toBe(2);
  cleanup();
  prepareCustomerPreview();
  selectLimitsScenario("empty");
  history.replaceState(null, "", "/customer/limits?customerPreview=v2");
  render(<CustomerPreview />);
  expect(await screen.findByText("Chưa có cấu hình hạn mức.")).toBeTruthy();
  expect(screen.getAllByText("Không có dữ liệu").length).toBe(2);
  cleanup();
  prepareCustomerPreview();
  selectLimitsScenario("error");
  render(<CustomerPreview />);
  expect(
    await screen.findByText("Không thể tải hạn mức. Vui lòng thử lại."),
  ).toBeTruthy();
});
