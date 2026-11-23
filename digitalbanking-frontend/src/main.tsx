import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import viVN from "antd/locale/vi_VN";
import { QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { AuthApiApp } from "./features/auth/v1/AuthApiApp";
import { queryClient } from "./app/query";
import "./styles.css";

async function mount() {
  const mock =
    import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true";
  // Switching from dev:mock to dev:api on the same origin must not leave an
  // already controlling MSW worker between this entry and the real backend.
  if (!mock && import.meta.env.DEV && "serviceWorker" in navigator) {
    const registrations = await navigator.serviceWorker.getRegistrations();
    const stale = registrations.filter((registration) =>
      registration.active?.scriptURL.endsWith("/mockServiceWorker.js"),
    );
    await Promise.all(stale.map((registration) => registration.unregister()));
    if (
      navigator.serviceWorker.controller?.scriptURL.endsWith(
        "/mockServiceWorker.js",
      )
    ) {
      location.reload();
      return;
    }
  }
  const customerPreview =
    mock && new URLSearchParams(location.search).get("customerPreview") === "v2"
      ? await import("./mocks/CustomerPreview")
      : null;
  const approvalPreview =
    mock && new URLSearchParams(location.search).get("approvalPreview") === "v2"
      ? await import("./mocks/ApprovalPreview")
      : null;
  const rolePreview =
    mock && new URLSearchParams(location.search).get("rolePreview") === "v2"
      ? await import("./mocks/RolePreview")
      : null;
  const staffCustomerPreview =
    mock && new URLSearchParams(location.search).get("staffPreview") === "v2"
      ? await import("./mocks/StaffCustomerPreview")
      : null;
  const fraudPreview =
    mock && new URLSearchParams(location.search).get("fraudPreview") === "v2"
      ? await import("./mocks/FraudPreview")
      : null;
  const auditPreview =
    mock && new URLSearchParams(location.search).get("auditPreview") === "v2"
      ? await import("./mocks/AuditPreview")
      : null;
  const transferPreview =
    mock && new URLSearchParams(location.search).get("transferPreview") === "v2"
      ? await import("./mocks/TransferPreview")
      : null;
  const authPreview =
    mock && new URLSearchParams(location.search).get("authPreview") === "v2"
      ? await import("./mocks/AuthPreview")
      : null;
  const Preview =
    mock &&
    !authPreview &&
    !customerPreview &&
    !approvalPreview &&
    !rolePreview &&
    !staffCustomerPreview &&
    !fraudPreview &&
    !auditPreview &&
    !transferPreview
      ? (await import("./mocks/PreviewShell")).PreviewShell
      : null;
  if (mock) {
    const { startMocks } = await import("./mocks/browser");
    await startMocks();
    if (rolePreview) rolePreview.prepareRolePreview();
    else if (fraudPreview) fraudPreview.prepareFraudPreview();
    else if (auditPreview) auditPreview.prepareAuditPreview();
    else if (transferPreview) transferPreview.prepareTransferPreview();
    else if (staffCustomerPreview)
      staffCustomerPreview.prepareStaffCustomerPreview();
    else if (approvalPreview) approvalPreview.prepareApprovalPreview();
    else if (customerPreview) customerPreview.prepareCustomerPreview();
    else if (authPreview) authPreview.prepareV2AuthPreview();
    else (await import("./mocks/handlers")).selectPreviewSession();
  }
  const app = rolePreview ? (
    <rolePreview.RolePreview />
  ) : fraudPreview ? (
    <fraudPreview.FraudPreview />
  ) : auditPreview ? (
    <auditPreview.AuditPreview />
  ) : transferPreview ? (
    <transferPreview.TransferPreview />
  ) : staffCustomerPreview ? (
    <staffCustomerPreview.StaffCustomerPreview />
  ) : approvalPreview ? (
    <approvalPreview.ApprovalPreview />
  ) : customerPreview ? (
    <customerPreview.CustomerPreview />
  ) : authPreview ? (
    <authPreview.AuthPreview />
  ) : Preview ? (
    <Preview>
      <App />
    </Preview>
  ) : (
    <AuthApiApp />
  );
  createRoot(document.getElementById("root")!).render(
    <ConfigProvider
      locale={viVN}
      theme={{
        token: {
          colorPrimary: "#2868B2",
          colorText: "#223449",
          colorTextSecondary: "#65768A",
          colorBgLayout: "#F5F7FA",
          colorBorder: "#E1E7EF",
          colorSuccess: "#237A52",
          colorWarning: "#9C5B0A",
          colorError: "#B53A3A",
          borderRadius: 8,
          fontFamily: 'Inter, "Segoe UI", Arial, sans-serif',
          fontSize: 14,
          controlHeight: 44,
        },
        components: {
          Card: { borderRadiusLG: 12 },
          Button: { fontWeight: 600 },
          Table: {
            headerBg: "#F7F9FC",
            headerColor: "#65768A",
            cellPaddingBlock: 17,
          },
        },
      }}
    >
      <QueryClientProvider client={queryClient}>{app}</QueryClientProvider>
    </ConfigProvider>,
  );
}
void mount().catch(() => {
  document.getElementById("root")!.textContent =
    "Không khởi động được mock UI. Kiểm tra worker và tải lại; không gọi backend thay thế.";
});
