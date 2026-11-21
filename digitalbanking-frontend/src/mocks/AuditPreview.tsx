import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Input, Select, Spin } from "antd";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { BrowserRouter, useMatch, useNavigate } from "react-router-dom";
import { Brand, Heading, Panel, date } from "../components/shared";
import {
  ApiError,
  createApiClient,
  type ApiClient,
} from "../shared/api/client";
import { isApiId } from "../shared/lib/id";
import {
  auditApi,
  outcomes,
  type AuditFilters,
} from "../features/audit/v2/audit-api";
import { prepareAuditFixture, selectAuditScenario } from "./audit-handlers";
import styles from "../layouts/AppLayout.module.css";
import auditStyles from "./AuditPreview.module.css";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
const outcomeLabels: Record<string, string> = {
  SUCCESS: "Thành công",
  FAILURE: "Thất bại",
  REJECTED: "Bị từ chối",
};
export function prepareAuditPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  prepareAuditFixture();
}
function message(error: unknown) {
  if (error instanceof ApiError && error.code === "AUTH_FORBIDDEN")
    return "Bạn không có quyền xem nhật ký kiểm toán.";
  if (error instanceof ApiError && error.code === "AUDIT_NOT_FOUND")
    return "Không tìm thấy bản ghi kiểm toán.";
  return "Không thể tải nhật ký kiểm toán. Vui lòng thử lại.";
}
const validInstant = (value: string) =>
  /(?:Z|[+-]\d{2}:\d{2})$/.test(value) && !Number.isNaN(Date.parse(value));
function Content({ client }: { client: ApiClient }) {
  const navigate = useNavigate();
  const match = useMatch("/staff/audit/:auditId");
  const auditId = match?.params.auditId;
  const [scenario, setScenario] = useState<Scenario>("default");
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [outcome, setOutcome] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [validation, setValidation] = useState("");
  const [filters, setFilters] = useState<AuditFilters>({ page: 0 });
  const list = useQuery({
    queryKey: ["audit-preview", scenario, filters],
    queryFn: ({ signal }) => auditApi.search(client, filters, signal),
    enabled: !auditId,
    retry: false,
  });
  const detail = useQuery({
    queryKey: ["audit-preview-detail", scenario, auditId],
    queryFn: ({ signal }) => auditApi.detail(client, auditId ?? "", signal),
    enabled: !!auditId && isApiId(auditId),
    retry: false,
  });
  function applyFilters() {
    if (actor && !isApiId(actor)) {
      setValidation("Actor ID phải là ID hợp lệ.");
      return;
    }
    if (action.length > 64) {
      setValidation("Action tối đa 64 ký tự.");
      return;
    }
    if ((from && !validInstant(from)) || (to && !validInstant(to))) {
      setValidation(
        "Nhập thời gian ISO 8601 có múi giờ, ví dụ 2026-11-20T00:00:00Z.",
      );
      return;
    }
    if (from && to && new Date(from).getTime() >= new Date(to).getTime()) {
      setValidation("Mốc đến phải sau mốc từ.");
      return;
    }
    setValidation("");
    setFilters({
      page: 0,
      actorUserId: actor || undefined,
      action: action || undefined,
      outcome: (outcome || undefined) as AuditFilters["outcome"],
      from: from || undefined,
      to: to || undefined,
    });
  }
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview staff">
          Nhật ký kiểm toán · chỉ đọc
        </nav>
        <p>Preview độc lập · dữ liệu tổng hợp</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Tra soát kiểm toán</span>
        </header>
        <main className={styles.main}>
          <Heading
            title="Nhật ký kiểm toán"
            sub="Bản xem mock chỉ đọc; quyền phiên staff và xuất tệp chưa tích hợp."
          />
          <Panel title="Bộ lọc">
            <div className={auditStyles.filters}>
              <div>
                <label htmlFor="audit-scenario">Kịch bản mock</label>
                <Select
                  id="audit-scenario"
                  aria-label="Kịch bản audit"
                  value={scenario}
                  onChange={(value: Scenario) => {
                    selectAuditScenario(value);
                    setScenario(value);
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
                <label htmlFor="audit-actor">Actor ID</label>
                <Input
                  id="audit-actor"
                  value={actor}
                  onChange={(event) => setActor(event.target.value)}
                />
              </div>
              <div>
                <label htmlFor="audit-action">Action</label>
                <Input
                  id="audit-action"
                  value={action}
                  onChange={(event) => setAction(event.target.value)}
                  maxLength={64}
                />
              </div>
              <div>
                <label htmlFor="audit-outcome">Kết quả</label>
                <Select
                  id="audit-outcome"
                  aria-label="Kết quả"
                  value={outcome}
                  onChange={setOutcome}
                  options={[
                    { value: "", label: "Tất cả" },
                    ...outcomes.map((value) => ({
                      value,
                      label: outcomeLabels[value],
                    })),
                  ]}
                />
              </div>
              <div>
                <label htmlFor="audit-from">Từ (ISO có múi giờ)</label>
                <Input
                  id="audit-from"
                  placeholder="2026-11-20T00:00:00Z"
                  value={from}
                  onChange={(event) => setFrom(event.target.value)}
                />
              </div>
              <div>
                <label htmlFor="audit-to">
                  Đến, không gồm (ISO có múi giờ)
                </label>
                <Input
                  id="audit-to"
                  placeholder="2026-11-21T00:00:00Z"
                  value={to}
                  onChange={(event) => setTo(event.target.value)}
                />
              </div>
            </div>
            {validation && (
              <Alert
                type="error"
                role="alert"
                message={validation}
                style={{ marginTop: 12 }}
              />
            )}
            <Button
              type="primary"
              onClick={applyFilters}
              style={{ marginTop: 16 }}
            >
              Lọc
            </Button>
            <Button disabled style={{ marginLeft: 12 }}>
              Xuất bằng chứng · chờ GAP-12
            </Button>
          </Panel>
          <div style={{ marginTop: 20 }}>
            {auditId ? (
              <Panel title={`Bản ghi #${auditId}`}>
                <Button
                  onClick={() => void navigate("/staff/audit?auditPreview=v2")}
                >
                  Quay lại danh sách
                </Button>
                {!isApiId(auditId) && (
                  <Alert type="error" message="Audit ID không hợp lệ." />
                )}
                {detail.isPending && isApiId(auditId) && (
                  <div role="status">
                    <Spin /> Đang tải chi tiết
                  </div>
                )}
                {detail.isError && (
                  <Alert
                    type="error"
                    role="alert"
                    message={message(detail.error)}
                    action={
                      <Button onClick={() => void detail.refetch()}>
                        Thử lại
                      </Button>
                    }
                  />
                )}
                {detail.data && (
                  <Descriptions
                    column={1}
                    bordered
                    style={{ marginTop: 16 }}
                    items={[
                      {
                        key: "id",
                        label: "Audit ID",
                        children: detail.data.auditId,
                      },
                      {
                        key: "time",
                        label: "Thời gian",
                        children: date(detail.data.occurredAt),
                      },
                      {
                        key: "actor",
                        label: "Người thực hiện",
                        children: `${detail.data.actor.type} · ${detail.data.actor.userId ?? "—"} · ${detail.data.actor.role}`,
                      },
                      {
                        key: "action",
                        label: "Action",
                        children: detail.data.action,
                      },
                      {
                        key: "entity",
                        label: "Đối tượng",
                        children: `${detail.data.entity.type} · ${detail.data.entity.id}`,
                      },
                      {
                        key: "outcome",
                        label: "Kết quả",
                        children: outcomeLabels[detail.data.outcome],
                      },
                      {
                        key: "correlation",
                        label: "Correlation ID",
                        children: detail.data.correlationId,
                      },
                      {
                        key: "request",
                        label: "Request ID",
                        children: detail.data.requestId ?? "—",
                      },
                    ]}
                  />
                )}
              </Panel>
            ) : (
              <Panel title="Danh sách bản ghi">
                {list.isPending && (
                  <div role="status">
                    <Spin /> Đang tải nhật ký
                  </div>
                )}
                {list.isError && (
                  <Alert
                    type="error"
                    role="alert"
                    message={message(list.error)}
                    action={
                      <Button onClick={() => void list.refetch()}>
                        Thử lại
                      </Button>
                    }
                  />
                )}
                {list.data && (
                  <>
                    <p>Ảnh chụp dữ liệu: {date(list.data.asOf)}</p>
                    {list.data.items.length ? (
                      list.data.items.map((item) => (
                        <Panel
                          key={item.auditId}
                          title={`${item.action} · #${item.auditId}`}
                        >
                          <p>
                            {date(item.occurredAt)} ·{" "}
                            {outcomeLabels[item.outcome]} · {item.entity.type} #
                            {item.entity.id}
                          </p>
                          <p>
                            Actor: {item.actor.userId ?? "Hệ thống"} ·
                            Correlation: {item.correlationId}
                          </p>
                          <Button
                            onClick={() =>
                              void navigate(
                                `/staff/audit/${item.auditId}?auditPreview=v2`,
                              )
                            }
                          >
                            Xem chi tiết #{item.auditId}
                          </Button>
                        </Panel>
                      ))
                    ) : (
                      <p>Không có bản ghi phù hợp.</p>
                    )}
                    <div
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 12,
                        marginTop: 16,
                      }}
                    >
                      <Button
                        disabled={filters.page === 0}
                        onClick={() =>
                          setFilters((current) => ({
                            ...current,
                            page: current.page - 1,
                          }))
                        }
                      >
                        Trang trước
                      </Button>
                      <span>
                        Trang {filters.page + 1} /{" "}
                        {Math.max(1, list.data.page.totalPages)}
                      </span>
                      <Button
                        disabled={list.data.page.last}
                        onClick={() =>
                          setFilters((current) => ({
                            ...current,
                            page: current.page + 1,
                          }))
                        }
                      >
                        Trang sau
                      </Button>
                    </div>
                  </>
                )}
              </Panel>
            )}
          </div>
        </main>
      </div>
    </div>
  );
}
export function AuditPreview() {
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
