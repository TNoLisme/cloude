// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setupServer } from "msw/node";
import { approvalHandlers, resetApprovalFixture } from "./approval-handlers";
import {
  prepareRolePreview,
  RolePreview,
  rolePreviewDraft,
} from "./RolePreview";

const server = setupServer(...approvalHandlers);
const requests: { url: string; method: string }[] = [];
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  const originalFetch = globalThis.fetch;
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const resolved =
      typeof input === "string" ? new URL(input, location.origin) : input;
    requests.push({
      url: String(resolved instanceof Request ? resolved.url : resolved),
      method:
        init?.method ?? (resolved instanceof Request ? resolved.method : "GET"),
    });
    return originalFetch(resolved, init);
  });
});
afterAll(() => {
  vi.unstubAllGlobals();
  server.close();
});
afterEach(() => {
  cleanup();
  resetApprovalFixture();
  server.resetHandlers();
  requests.length = 0;
});

it("builds exactly the four contract discriminants without mixing role and privilege fields", () => {
  expect(rolePreviewDraft("ASSIGN_ROLE", "ADMIN", "mẫu")).toEqual({
    operation: "ASSIGN_ROLE",
    targetUserId: "4002",
    roleCode: "ADMIN",
    reason: "mẫu",
  });
  expect(rolePreviewDraft("REVOKE_ROLE", "AUDITOR", "")).toEqual({
    operation: "REVOKE_ROLE",
    targetUserId: "4002",
    roleCode: "AUDITOR",
  });
  expect(rolePreviewDraft("GRANT_PRIVILEGE", "ADMIN", "")).toEqual({
    operation: "GRANT_PRIVILEGE",
    targetUserId: "4002",
    privilegeCode: "CHECKER",
  });
  expect(rolePreviewDraft("REVOKE_PRIVILEGE", "ADMIN", "")).toEqual({
    operation: "REVOKE_PRIVILEGE",
    targetUserId: "4002",
    privilegeCode: "CHECKER",
  });
});

it("shows review and reads only ROLE_CHANGE approvals without sending a mutation", async () => {
  prepareRolePreview();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RolePreview />
    </QueryClientProvider>,
  );
  expect(await screen.findByText(/Yêu cầu #8002/)).toBeTruthy();
  expect(screen.queryByText(/Yêu cầu #8001/)).toBeNull();
  fireEvent.change(screen.getByLabelText("Lý do (không bắt buộc)"), {
    target: { value: "Mẫu kiểm tra" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Kiểm tra yêu cầu" }));
  expect(screen.getByText("Mẫu kiểm tra")).toBeTruthy();
  expect(
    (screen.getByRole("button", { name: "Tạo yêu cầu" }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  expect(requests).toEqual([
    {
      url: expect.stringContaining(
        "/api/v2/admin/approvals?page=0&size=20&requestType=ROLE_CHANGE",
      ),
      method: "GET",
    },
  ]);
});

it("switches the approval panel to an empty GET fixture", async () => {
  prepareRolePreview();
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RolePreview />
    </QueryClientProvider>,
  );
  expect(await screen.findByText(/Yêu cầu #8002/)).toBeTruthy();
  fireEvent.mouseDown(
    screen.getByRole("combobox", { name: "Kịch bản danh sách" }),
  );
  fireEvent.click(screen.getByText("Danh sách rỗng"));
  expect(await screen.findByText("Chưa có yêu cầu đổi quyền.")).toBeTruthy();
  expect(requests.every((request) => request.method === "GET")).toBe(true);
});
