import { setupWorker } from "msw/browser";
import { handlers } from "./handlers";

export const worker = setupWorker(...handlers);
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
