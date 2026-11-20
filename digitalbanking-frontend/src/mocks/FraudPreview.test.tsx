// @vitest-environment jsdom
import "../test/setup-dom";
import { afterAll, afterEach, beforeAll, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { FraudPreview, prepareFraudPreview } from "./FraudPreview";
import {
  fraudHandlers,
  resetFraudFixture,
  selectFraudScenario,
} from "./fraud-handlers";

const server = setupServer(...fraudHandlers);
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
  resetFraudFixture();
  server.resetHandlers();
  requests.length = 0;
  history.replaceState(null, "", "/");
});
function mount() {
  history.replaceState(null, "", "/staff/risk?fraudPreview=v2");
  prepareFraudPreview();
  return render(<FraudPreview />);
}
it("reads flags and shows detail from list with all review decisions disabled", async () => {
  mount();
  expect(await screen.findByText("Cờ #9001")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Xem chi tiết #9001" }));
  expect(await screen.findByText("Chi tiết cờ #9001")).toBeTruthy();
  for (const decision of ["ALLOW", "BLOCK", "ESCALATE", "CLOSE"]) {
    expect(
      (screen.getByRole("button", { name: decision }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  }
  expect(requests).toHaveLength(1);
  expect(requests[0]).toContain("/api/v2/fraud-flags?page=0&size=20");
  expect(screen.queryByText("matchedValue")).toBeNull();
});
it("shows empty and forbidden states", async () => {
  mount();
  await screen.findByText("Cờ #9001");
  cleanup();
  prepareFraudPreview();
  selectFraudScenario("empty");
  render(<FraudPreview />);
  expect(await screen.findByText("Không có cờ rủi ro phù hợp.")).toBeTruthy();
  cleanup();
  prepareFraudPreview();
  selectFraudScenario("forbidden");
  render(<FraudPreview />);
  expect(
    await screen.findByText("Bạn không có quyền xem cờ rủi ro."),
  ).toBeTruthy();
});
