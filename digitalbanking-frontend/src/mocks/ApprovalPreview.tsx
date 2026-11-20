import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Select, Spin } from "antd";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import {
  BrowserRouter,
  useLocation,
  useMatch,
  useNavigate,
} from "react-router-dom";
import { Brand, Heading, Panel, date } from "../components/shared";
import { createApiClient, type ApiClient } from "../shared/api/client";
import { approvalApi } from "../features/approvals/v2/approval-api";
import {
  prepareApprovalFixture,
  selectApprovalScenario,
} from "./approval-handlers";
import styles from "../layouts/AppLayout.module.css";

type Scenario = "default" | "empty" | "slow" | "error";
export function prepareApprovalPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  prepareApprovalFixture();
}
const statuses = [
  "PENDING",
  "APPROVED",
  "REJECTED",
  "APPLIED",
  "CANCELLED",
] as const;
const statusLabel: Record<string, string> = {
  PENDING: "Chờ duyệt",
  APPROVED: "Đã duyệt",
  REJECTED: "Từ chối",
  APPLIED: "Đã áp dụng",
  CANCELLED: "Đã hủy",
};
function Content({ client }: { client: ApiClient }) {
  const location = useLocation(),
    navigate = useNavigate();
  const detailMatch = useMatch("/staff/approvals/:approvalId");
  const params = new URLSearchParams(location.search);
  const requestType =
    params.get("requestType") === "LIMIT_CHANGE" ||
    params.get("requestType") === "ROLE_CHANGE"
      ? (params.get("requestType") as "LIMIT_CHANGE" | "ROLE_CHANGE")
      : undefined;
  const rawStatus = params.get("status");
  const status = statuses.find((value) => value === rawStatus);
  const rawPage = Number(params.get("page") ?? "0");
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const [scenario, setScenario] = useState<Scenario>("default");
  const setFilters = (next: {
    requestType?: string;
    status?: string;
    page?: number;
  }) => {
    const query = new URLSearchParams({ approvalPreview: "v2" });
    if (next.requestType) query.set("requestType", next.requestType);
    if (next.status) query.set("status", next.status);
    if (next.page) query.set("page", String(next.page));
    void navigate(`/staff/approvals?${query}`);
  };
  const list = useQuery({
    queryKey: ["approval-preview", requestType, status, page, scenario],
    queryFn: ({ signal }) =>
      approvalApi.list(client, { page, requestType, status }, signal),
    enabled: !detailMatch,
    retry: false,
  });
  const detail = useQuery({
    queryKey: [
      "approval-preview-detail",
      detailMatch?.params.approvalId,
      scenario,
    ],
    queryFn: ({ signal }) =>
      approvalApi.detail(client, detailMatch?.params.approvalId ?? "", signal),
    enabled: !!detailMatch,
    retry: false,
  });
  const goBack = () => setFilters({ requestType, status, page });
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview staff">
          <Button type="text" onClick={goBack}>
            Yêu cầu phê duyệt
          </Button>
        </nav>
        <p>Preview staff độc lập · dữ liệu tổng hợp</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Demo phê duyệt chỉ đọc</span>
        </header>
        <main className={styles.main}>
          <Heading
            title={
              detailMatch ? "Chi tiết yêu cầu phê duyệt" : "Yêu cầu phê duyệt"
            }
            sub="Dữ liệu mock để xem giao diện; quyền và phạm vi staff chờ BE/Security chốt."
          />
          <label htmlFor="approval-scenario">Kịch bản mock</label>
          <Select
            id="approval-scenario"
            aria-label="Kịch bản approval"
            value={scenario}
            style={{ width: 220, display: "block", marginBottom: 20 }}
            onChange={(value: Scenario) => {
              selectApprovalScenario(value);
              setScenario(value);
            }}
            options={[
              { value: "default", label: "Dữ liệu mẫu" },
              { value: "empty", label: "Danh sách rỗng" },
              { value: "slow", label: "Tải chậm" },
              { value: "error", label: "Lỗi dịch vụ" },
            ]}
          />
          {detailMatch ? (
            <>
              <Button onClick={goBack}>Về danh sách</Button>
              {detail.isPending && (
                <div role="status">
                  <Spin /> Đang tải chi tiết
                </div>
              )}
              {detail.isError && (
                <Alert
                  type="error"
                  role="alert"
                  showIcon
                  message="Không thể xem yêu cầu này hoặc bạn không có quyền truy cập."
                  action={
                    <Button onClick={() => void detail.refetch()}>
                      Thử lại
                    </Button>
                  }
                />
              )}
              {detail.data && (
                <Panel title={`Yêu cầu #${detail.data.approvalId}`}>
                  <Descriptions
                    column={1}
                    colon={false}
                    items={[
                      {
                        key: "type",
                        label: "Loại yêu cầu",
                        children: detail.data.requestType,
                      },
                      {
                        key: "target",
                        label: "Đối tượng",
                        children: `${detail.data.targetType} · ${detail.data.targetId}`,
                      },
                      {
                        key: "status",
                        label: "Trạng thái",
                        children: statusLabel[detail.data.status],
                      },
                      {
                        key: "maker",
                        label: "Người tạo",
                        children: detail.data.maker.displayName,
                      },
                      ...(detail.data.checker
                        ? [
                            {
                              key: "checker",
                              label: "Người kiểm tra",
                              children: detail.data.checker.displayName,
                            },
                          ]
                        : []),
                      {
                        key: "created",
                        label: "Tạo lúc",
                        children: date(detail.data.createdAt),
                      },
                      ...(detail.data.decidedAt
                        ? [
                            {
                              key: "decided",
                              label: "Quyết định lúc",
                              children: date(detail.data.decidedAt),
                            },
                          ]
                        : []),
                      ...(detail.data.appliedAt
                        ? [
                            {
                              key: "applied",
                              label: "Áp dụng lúc",
                              children: date(detail.data.appliedAt),
                            },
                          ]
                        : []),
                      {
                        key: "correlation",
                        label: "Mã đối chiếu",
                        children: detail.data.correlationId,
                      },
                    ]}
                  />
                  <p>
                    Nội dung thay đổi và kết quả chi tiết chờ contract
                    field/allowlist.
                  </p>
                  <Button disabled>Phê duyệt</Button>{" "}
                  <Button disabled>Từ chối</Button>
                </Panel>
              )}
            </>
          ) : (
            <>
              <div
                style={{
                  display: "flex",
                  gap: 12,
                  flexWrap: "wrap",
                  marginBottom: 20,
                }}
              >
                <Select
                  aria-label="Lọc loại yêu cầu"
                  value={requestType ?? ""}
                  style={{ width: 190 }}
                  onChange={(value: string) =>
                    setFilters({ requestType: value, status })
                  }
                  options={[
                    { value: "", label: "Mọi loại" },
                    { value: "LIMIT_CHANGE", label: "Đổi hạn mức" },
                    { value: "ROLE_CHANGE", label: "Đổi quyền" },
                  ]}
                />
                <Select
                  aria-label="Lọc trạng thái"
                  value={status ?? ""}
                  style={{ width: 190 }}
                  onChange={(value: string) =>
                    setFilters({ requestType, status: value })
                  }
                  options={[
                    { value: "", label: "Mọi trạng thái" },
                    ...statuses.map((value) => ({
                      value,
                      label: statusLabel[value],
                    })),
                  ]}
                />
              </div>
              {list.isPending && (
                <div role="status">
                  <Spin /> Đang tải yêu cầu
                </div>
              )}
              {list.isError && (
                <Alert
                  type="error"
                  role="alert"
                  showIcon
                  message="Không thể tải danh sách phê duyệt."
                  action={
                    <Button onClick={() => void list.refetch()}>Thử lại</Button>
                  }
                />
              )}
              {list.data &&
                (list.data.items.length ? (
                  list.data.items.map((item) => (
                    <Panel
                      key={item.approvalId}
                      title={`Yêu cầu #${item.approvalId}`}
                    >
                      <p>
                        {item.requestType} · {statusLabel[item.status]} ·{" "}
                        {item.maker.displayName} · {date(item.createdAt)}
                      </p>
                      <Button
                        onClick={() =>
                          void navigate(
                            `/staff/approvals/${item.approvalId}${location.search}`,
                          )
                        }
                      >
                        Xem chi tiết #{item.approvalId}
                      </Button>
                    </Panel>
                  ))
                ) : (
                  <p>Không có yêu cầu phù hợp.</p>
                ))}
              {list.data && (
                <div style={{ display: "flex", gap: 12, marginTop: 20 }}>
                  <Button
                    disabled={page === 0}
                    onClick={() =>
                      setFilters({ requestType, status, page: page - 1 })
                    }
                  >
                    Trang trước
                  </Button>
                  <span>
                    Trang {page + 1} / {Math.max(1, list.data.page.totalPages)}
                  </span>
                  <Button
                    disabled={list.data.page.last}
                    onClick={() =>
                      setFilters({ requestType, status, page: page + 1 })
                    }
                  >
                    Trang sau
                  </Button>
                </div>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  );
}
export function ApprovalPreview() {
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
