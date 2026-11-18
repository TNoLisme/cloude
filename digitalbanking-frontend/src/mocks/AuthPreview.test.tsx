// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { ConfigProvider } from "antd";
import { setupServer } from "msw/node";
import { AuthPreview, prepareV2AuthPreview } from "./AuthPreview";
import {
  disableV2FixturePersistence,
  resetV2MockSession,
  v2AuthHandlers,
  v2FixtureStorageKey,
} from "./v2-auth";
import { queryClient } from "../app/query";
import { session as legacySession } from "../stores/session";

const server = setupServer(...v2AuthHandlers);
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  // Node fetch needs an absolute URL; browsers resolve the same relative request.
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
  disableV2FixturePersistence();
  resetV2MockSession();
  sessionStorage.clear();
  queryClient.clear();
  server.resetHandlers();
  history.replaceState(null, "", "/");
});
function mount() {
  prepareV2AuthPreview();
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <AuthPreview />
    </ConfigProvider>,
  );
}
async function login(username: string) {
  fireEvent.change(await screen.findByLabelText("Tên đăng nhập"), {
    target: { value: username },
  });
  fireEvent.change(screen.getByLabelText("Mật khẩu"), {
    target: { value: "fixture-only" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));
}
it("mounts the actual preview host, clears old cache, restores its fixture on remount and clears it on logout", async () => {
  history.replaceState(null, "", "/login?authPreview=v2");
  queryClient.setQueryData(["old-user-private-data"], { balance: "123" });
  const first = mount();
  await login("fixture-customer");
  await screen.findByRole("heading", { name: "Phiên hiện tại" });
  expect(queryClient.getQueryData(["old-user-private-data"])).toBeUndefined();
  expect(legacySession.getState().token).toBeNull();
  expect(location.pathname).toBe("/workspace");
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBe("1");
  first.unmount();
  mount();
  await screen.findByRole("heading", { name: "Phiên hiện tại" });
  fireEvent.click(screen.getByRole("button", { name: "Đăng xuất" }));
  await screen.findByText("Đã đăng xuất.");
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBeNull();
  expect(location.pathname).toBe("/login");
});
it("runs MFA through the actual preview host and exposes only an explicit synthetic OTP helper", async () => {
  history.replaceState(null, "", "/login?authPreview=v2");
  mount();
  await login("fixture-mfa");
  await screen.findByRole("heading", { name: "Xác minh đăng nhập" });
  expect(location.pathname).toBe("/auth/challenge");
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBeNull();
  fireEvent.click(screen.getByText("Điều khiển bản xem thử"));
  fireEvent.click(screen.getByRole("button", { name: "Xem mã OTP fixture" }));
  expect(screen.getByText("Mã fixture vừa lấy: 123456")).toBeTruthy();
  fireEvent.change(screen.getByLabelText("Mã OTP"), {
    target: { value: "123456" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Xác minh OTP" }));
  await screen.findByRole("heading", { name: "Phiên hiện tại" });
  await waitFor(() => expect(location.pathname).toBe("/workspace"));
  expect(sessionStorage.getItem(v2FixtureStorageKey)).toBe("1");
});
