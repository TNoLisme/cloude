// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { TransferPreview, prepareTransferPreview } from "./TransferPreview";
import { resetV2MockSession, v2AuthHandlers } from "./v2-auth";

const server = setupServer(...v2AuthHandlers);
const requests: { url: string; method: string }[] = [];
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  const interceptedFetch = globalThis.fetch;
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const resolved =
      typeof input === "string" ? new URL(input, location.origin) : input;
    requests.push({
      url: String(resolved instanceof Request ? resolved.url : resolved),
      method: init?.method ?? "GET",
    });
    return interceptedFetch(resolved, init);
  });
});
afterAll(() => {
  vi.unstubAllGlobals();
  server.close();
});
afterEach(() => {
  cleanup();
  resetV2MockSession();
  requests.length = 0;
  history.replaceState(null, "", "/");
});
it("loads only source accounts and keeps submit disabled while recipient confirmation is unresolved", async () => {
  history.replaceState(null, "", "/customer/transfers/new?transferPreview=v2");
  prepareTransferPreview();
  render(<TransferPreview />);
  expect(
    await screen.findByText(
      "Số tài khoản nhận chưa được hệ thống xác minh. Không có lệnh chuyển tiền nào được gửi từ bản xem này.",
    ),
  ).toBeTruthy();
  expect(
    await screen.findByRole("combobox", { name: "Tài khoản nguồn" }),
  ).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Xem lại thông tin" }));
  expect(
    screen.getByText("Chọn tài khoản nguồn trong danh sách hiện có."),
  ).toBeTruthy();
  expect(requests).toHaveLength(1);
  expect(requests[0].url).toContain("/api/v2/accounts");
  expect(requests[0].method).toBe("GET");
  expect(screen.queryByRole("button", { name: /Gửi lệnh/ })).toBeNull();
});

it("reviews schema-valid input while keeping the transfer action disabled", async () => {
  history.replaceState(null, "", "/customer/transfers/new?transferPreview=v2");
  prepareTransferPreview();
  render(<TransferPreview />);
  const source = await screen.findByRole("combobox", {
    name: "Tài khoản nguồn",
  });
  fireEvent.mouseDown(source);
  fireEvent.click(await screen.findByText("**** **** 3001 · VND"));
  fireEvent.change(screen.getByLabelText("Số tài khoản nhận"), {
    target: { value: "0123456789" },
  });
  fireEvent.change(screen.getByLabelText("Số tiền (VND, hai số lẻ)"), {
    target: { value: "150000.00" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Xem lại thông tin" }));
  expect(await screen.findByText("Kiểm tra thông tin đã nhập")).toBeTruthy();
  expect(screen.getByText("0123456789")).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: /Gửi lệnh/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(requests.every((request) => request.method === "GET")).toBe(true);
});
