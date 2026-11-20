import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Select, Spin } from "antd";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";
import { Brand, Heading, Panel, date } from "../components/shared";
import {
  createApiClient,
  ApiError,
  type ApiClient,
} from "../shared/api/client";
import {
  fraudApi,
  fraudSeverities,
  fraudStatuses,
} from "../features/fraud/v2/fraud-api";
import { prepareFraudFixture, selectFraudScenario } from "./fraud-handlers";
import styles from "../layouts/AppLayout.module.css";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
const statusLabels: Record<string, string> = {
  OPEN: "Chưa rà soát",
  UNDER_REVIEW: "Đang rà soát",
  ALLOWED: "Đã cho phép",
  BLOCKED: "Đã chặn",
  CLOSED: "Đã đóng",
};
const severityLabels: Record<string, string> = {
  LOW: "Thấp",
  MEDIUM: "Trung bình",
  HIGH: "Cao",
  CRITICAL: "Rất cao",
};
export function prepareFraudPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  prepareFraudFixture();
}
function Content({ client }: { client: ApiClient }) {
  const [scenario, setScenario] = useState<Scenario>("default");
  const [status, setStatus] = useState("");
  const [severity, setSeverity] = useState("");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const list = useQuery({
    queryKey: ["fraud-preview", scenario, status, severity, page],
    queryFn: ({ signal }) =>
      fraudApi.list(
        client,
        {
          page,
          status: status
            ? (status as (typeof fraudStatuses)[number])
            : undefined,
          severity: severity
            ? (severity as (typeof fraudSeverities)[number])
            : undefined,
        },
        signal,
      ),
    retry: false,
  });
  const selected = list.data?.items.find((item) => item.flagId === selectedId);
  const problem =
    list.error instanceof ApiError && list.error.code === "AUTH_FORBIDDEN"
      ? "Bạn không có quyền xem cờ rủi ro."
      : "Không thể tải danh sách cờ rủi ro.";
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview staff">Cờ rủi ro · chỉ đọc</nav>
        <p>Preview staff độc lập · dữ liệu tổng hợp</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Rà soát rủi ro</span>
        </header>
        <main className={styles.main}>
          <Heading
            title="Cờ rủi ro"
            sub="Dữ liệu mock để xem UI; quyền staff và tác động quyết định chờ BE/BA chốt."
          />
          <Alert
            type="info"
            showIcon
            message="Cờ rủi ro là dấu hiệu cần rà soát, không tự xác nhận giao dịch đã bị chặn."
            style={{ marginBottom: 20 }}
          />
          <div
            style={{
              display: "flex",
              gap: 12,
              flexWrap: "wrap",
              marginBottom: 20,
            }}
          >
            <div>
              <label htmlFor="fraud-scenario">Kịch bản mock</label>
              <Select
                id="fraud-scenario"
                aria-label="Kịch bản fraud"
                value={scenario}
                style={{ width: 190, display: "block" }}
                onChange={(value: Scenario) => {
                  selectFraudScenario(value);
                  setScenario(value);
                  setPage(0);
                  setSelectedId(null);
                }}
                options={[
                  { value: "default", label: "Dữ liệu mẫu" },
                  { value: "empty", label: "Danh sách rỗng" },
                  { value: "slow", label: "Tải chậm" },
                  { value: "error", label: "Lỗi dịch vụ" },
                  { value: "forbidden", label: "Không có quyền" },
                ]}
              />
            </div>
            <div>
              <label htmlFor="fraud-status">Trạng thái</label>
              <Select
                id="fraud-status"
                aria-label="Lọc trạng thái"
                value={status}
                style={{ width: 190, display: "block" }}
                onChange={(value: string) => {
                  setStatus(value);
                  setPage(0);
                  setSelectedId(null);
                }}
                options={[
                  { value: "", label: "Mọi trạng thái" },
                  ...fraudStatuses.map((value) => ({
                    value,
                    label: statusLabels[value],
                  })),
                ]}
              />
            </div>
            <div>
              <label htmlFor="fraud-severity">Mức độ</label>
              <Select
                id="fraud-severity"
                aria-label="Lọc mức độ"
                value={severity}
                style={{ width: 190, display: "block" }}
                onChange={(value: string) => {
                  setSeverity(value);
                  setPage(0);
                  setSelectedId(null);
                }}
                options={[
                  { value: "", label: "Mọi mức độ" },
                  ...fraudSeverities.map((value) => ({
                    value,
                    label: severityLabels[value],
                  })),
                ]}
              />
            </div>
          </div>
          {list.isPending && (
            <div role="status">
              <Spin /> Đang tải cờ rủi ro
            </div>
          )}
          {list.isError && (
            <Alert
              type="error"
              role="alert"
              showIcon
              message={problem}
              action={
                <Button onClick={() => void list.refetch()}>Thử lại</Button>
              }
            />
          )}
          {list.data && (
            <>
              {list.data.items.length ? (
                list.data.items.map((item) => (
                  <Panel key={item.flagId} title={`Cờ #${item.flagId}`}>
                    <p>
                      {item.rule.code} · {severityLabels[item.severity]} ·{" "}
                      {statusLabels[item.status]}
                    </p>
                    <p>
                      Giao dịch {item.transferReference} ·{" "}
                      {date(item.createdAt)}
                    </p>
                    <Button onClick={() => setSelectedId(item.flagId)}>
                      Xem chi tiết #{item.flagId}
                    </Button>
                  </Panel>
                ))
              ) : (
                <p>Không có cờ rủi ro phù hợp.</p>
              )}
              <div style={{ display: "flex", gap: 12, marginTop: 20 }}>
                <Button
                  disabled={page === 0}
                  onClick={() => {
                    setPage(page - 1);
                    setSelectedId(null);
                  }}
                >
                  Trang trước
                </Button>
                <span>
                  Trang {page + 1} / {Math.max(1, list.data.page.totalPages)}
                </span>
                <Button
                  disabled={list.data.page.last}
                  onClick={() => {
                    setPage(page + 1);
                    setSelectedId(null);
                  }}
                >
                  Trang sau
                </Button>
              </div>
            </>
          )}
          {selected && (
            <Panel title={`Chi tiết cờ #${selected.flagId}`}>
              <Button onClick={() => setSelectedId(null)}>Đóng chi tiết</Button>
              <Descriptions
                column={1}
                colon={false}
                items={[
                  {
                    key: "reference",
                    label: "Mã giao dịch",
                    children: selected.transferReference,
                  },
                  {
                    key: "customer",
                    label: "Customer ID",
                    children: selected.customerId,
                  },
                  {
                    key: "rule",
                    label: "Quy tắc / phiên bản",
                    children: `${selected.rule.code} / ${selected.rule.version}`,
                  },
                  {
                    key: "severity",
                    label: "Mức độ",
                    children: severityLabels[selected.severity],
                  },
                  {
                    key: "status",
                    label: "Trạng thái",
                    children: statusLabels[selected.status],
                  },
                  {
                    key: "created",
                    label: "Phát hiện lúc",
                    children: date(selected.createdAt),
                  },
                  ...(selected.decision
                    ? [
                        {
                          key: "decision",
                          label: "Quyết định đã ghi",
                          children: selected.decision,
                        },
                      ]
                    : []),
                  ...(selected.reviewedBy
                    ? [
                        {
                          key: "reviewer",
                          label: "Người rà soát",
                          children: selected.reviewedBy.displayName,
                        },
                      ]
                    : []),
                  ...(selected.reviewedAt
                    ? [
                        {
                          key: "reviewed",
                          label: "Rà soát lúc",
                          children: date(selected.reviewedAt),
                        },
                      ]
                    : []),
                  {
                    key: "correlation",
                    label: "Mã đối chiếu",
                    children: selected.correlationId,
                  },
                ]}
              />
              <p>
                Giá trị khớp quy tắc và tác động quyết định chờ
                contract/allowlist được chốt.
              </p>
              <Button disabled>ALLOW</Button> <Button disabled>BLOCK</Button>{" "}
              <Button disabled>ESCALATE</Button> <Button disabled>CLOSE</Button>
            </Panel>
          )}
        </main>
      </div>
    </div>
  );
}
export function FraudPreview() {
  const [client, setClient] = useState<ApiClient | null>(null);
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 0 } },
      }),
  );
  const allowed =
    (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true") ||
    import.meta.env.MODE === "test";
  useEffect(() => {
    if (!allowed) return;
    const instance = createApiClient({ baseUrl: "/api/v2" });
    setClient(instance);
    return () => instance.dispose();
  }, [allowed]);
  if (!allowed) return null;
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        {client ? (
          <Content client={client} />
        ) : (
          <p role="status">Đang chuẩn bị bản xem</p>
        )}
      </BrowserRouter>
    </QueryClientProvider>
  );
}
