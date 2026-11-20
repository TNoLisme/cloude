import { setupWorker } from "msw/browser";
import { http, HttpResponse } from "msw";
import { handlers } from "./handlers";
import { v2AuthHandlers } from "./v2-auth";
import { approvalHandlers } from "./approval-handlers";
import { staffCustomerHandlers } from "./staff-customer-handlers";
import { waitForMockControl } from "./wait-for-control";

export const worker = setupWorker(
  ...handlers,
  ...v2AuthHandlers,
  ...approvalHandlers,
  ...staffCustomerHandlers,
  http.all("*/api/*", () => HttpResponse.error()),
);
export async function startMocks() {
  if (!import.meta.env.DEV || import.meta.env.VITE_ENABLE_MOCKS !== "true") {
    throw new Error(
      "Mocks may only start in explicitly enabled development mode.",
    );
  }
  await worker.start({
    onUnhandledRequest(request, print) {
      if (new URL(request.url).pathname.startsWith("/api/")) print.error();
    },
  });
  await waitForMockControl(navigator.serviceWorker);
}
