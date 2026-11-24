// @vitest-environment jsdom
import "../../../test/setup-dom";
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ConfigProvider } from "antd";
import {
  account,
  accounts,
  authFixture,
  profile,
} from "../../../mocks/fixtures";
import { createV1CustomerReadApi } from "./customer-read-api";
import { V1CustomerDashboard } from "./V1CustomerDashboard";

afterEach(cleanup);

function mount(path: string, fetcher: typeof fetch) {
  const reader = createV1CustomerReadApi({
    baseUrl: "/api/v1",
    fetch: fetcher,
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const page = render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <V1CustomerDashboard
            auth={authFixture()}
            reader={reader}
            logout={async () => undefined}
            ending={false}
          />
        </MemoryRouter>
      </QueryClientProvider>
    </ConfigProvider>,
  );
  return () => {
    page.unmount();
    reader.dispose();
    queryClient.clear();
  };
}

it("reads masked v1 accounts, opens detail without inventing owner name, and reads profile", async () => {
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
    if (url === "/api/v1/accounts") return Response.json(accounts);
    if (url === "/api/v1/accounts/3001") return Response.json(account);
    if (url === "/api/v1/customers/me") return Response.json(profile);
    throw new Error(`Unexpected request: ${url}`);
  });
  const unmount = mount("/customer", fetcher);
  expect((await screen.findAllByText("**** **** 3001")).length).toBeGreaterThan(
    0,
  );
  expect(screen.queryByText("Đang hoạt động")).toBeNull();
  fireEvent.click(
    screen.getByRole("button", { name: "Xem chi tiết tài khoản" }),
  );
  expect(await screen.findByText("Mã khách hàng")).toBeTruthy();
  expect(screen.getByText("2001")).toBeTruthy();
  expect(screen.queryByText("Chủ tài khoản")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Hồ sơ của tôi" }));
  expect(await screen.findByText("*******123")).toBeTruthy();
  expect(screen.getByText("n***@example.test")).toBeTruthy();
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
    "/api/v1/accounts",
    "/api/v1/accounts/3001",
    "/api/v1/customers/me",
  ]);
  unmount();
});

it("does not show another account when v1 rejects a direct URL", async () => {
  const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
    Response.json(
      {
        type: "about:blank",
        title: "Forbidden",
        status: 403,
        detail: "Denied",
        instance: "/api/v1/accounts/3002",
        code: "AUTH_FORBIDDEN",
        correlationId: "fixture-id",
        timestamp: "2026-11-24T00:00:00Z",
        retryable: false,
      },
      { status: 403, headers: { "Content-Type": "application/problem+json" } },
    ),
  );
  const unmount = mount("/customer/accounts/3002", fetcher);
  expect(
    await screen.findByText("Bạn không có quyền xem dữ liệu này."),
  ).toBeTruthy();
  expect(screen.queryByText("Mã khách hàng")).toBeNull();
  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
    "/api/v1/accounts/3002",
  ]);
  unmount();
});
