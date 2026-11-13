import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import viVN from "antd/locale/vi_VN";
import { QueryClientProvider } from "@tanstack/react-query";
import { App } from "./app/App";
import { queryClient } from "./app/query";
import "./styles.css";

async function mount() {
  const mock =
    import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true";
  const Preview = mock
    ? (await import("./mocks/PreviewShell")).PreviewShell
    : null;
  if (mock) {
    const { startMocks } = await import("./mocks/browser");
    await startMocks();
    (await import("./mocks/handlers")).selectPreviewSession();
  }
  const app = Preview ? (
    <Preview>
      <App />
    </Preview>
  ) : (
    <App />
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
