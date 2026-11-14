import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";
import { v2AuthHandlers } from "./v2-auth";

export const worker = setupWorker(...handlers, ...v2AuthHandlers);
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
}
