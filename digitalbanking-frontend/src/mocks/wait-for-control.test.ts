import { afterEach, expect, it, vi } from "vitest";
import { waitForMockControl } from "./wait-for-control";

afterEach(() => vi.useRealTimers());
function fixture() {
  const target = new EventTarget();
  let controller: object | null = null;
  return {
    container: Object.assign(target, {
      get controller() {
        return controller;
      },
    }) as unknown as ServiceWorkerContainer,
    control() {
      controller = {};
      Object.defineProperty(target, "controller", {
        value: controller,
        configurable: true,
      });
      target.dispatchEvent(new Event("controllerchange"));
    },
  };
}
it("permits startup immediately when a worker controls the page", async () => {
  const worker = fixture();
  worker.control();
  await expect(waitForMockControl(worker.container)).resolves.toBeUndefined();
});
it("holds startup until controllerchange, ignoring events without a controller", async () => {
  const worker = fixture();
  let mounted = false;
  const ready = waitForMockControl(worker.container).then(() => {
    mounted = true;
  });
  worker.container.dispatchEvent(new Event("controllerchange"));
  await Promise.resolve();
  expect(mounted).toBe(false);
  worker.control();
  await ready;
  expect(mounted).toBe(true);
});
it("fails closed after the dev startup timeout and removes the listener", async () => {
  vi.useFakeTimers();
  const worker = fixture();
  const remove = vi.spyOn(worker.container, "removeEventListener");
  const ready = waitForMockControl(worker.container);
  const assertion = expect(ready).rejects.toThrow("no API consumer mounted");
  await vi.advanceTimersByTimeAsync(10_000);
  await assertion;
  expect(remove).toHaveBeenCalledWith("controllerchange", expect.any(Function));
});
