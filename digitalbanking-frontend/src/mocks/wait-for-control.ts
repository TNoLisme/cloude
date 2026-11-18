/** Dev startup gate: do not mount API consumers before MSW controls this page. */
export function waitForMockControl(serviceWorker: ServiceWorkerContainer) {
  if (serviceWorker.controller) return Promise.resolve();
  return new Promise<void>((resolve, reject) => {
    const finish = () => {
      if (!serviceWorker.controller) return;
      cleanup();
      resolve();
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(
        new Error("Mock worker did not take control; no API consumer mounted"),
      );
    }, 10_000);
    const cleanup = () => {
      clearTimeout(timer);
      serviceWorker.removeEventListener("controllerchange", finish);
    };
    serviceWorker.addEventListener("controllerchange", finish);
    finish();
  });
}
