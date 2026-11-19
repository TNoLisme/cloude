import { useEffect, useState } from "react";
import { Button, Descriptions, Select, Spin, Alert } from "antd";
import { Icon, Illustration } from "../components/icons";
import {
  BrowserRouter,
  Navigate,
  useLocation,
  useMatch,
  useNavigate,
} from "react-router-dom";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { Brand, Heading, Panel, Simulation, date } from "../components/shared";
import {
  createApiClient,
  ApiError,
  type ApiClient,
} from "../shared/api/client";
import { customerApi } from "../features/accounts/v2/customer-api";
import {
  HistoryView,
  RecentTransactions,
} from "../features/history/v2/HistoryView";
import { LimitsView } from "../features/limits/v2/LimitsView";
import {
  CustomerAccountCard,
  CustomerAccountDetailPanel,
  CustomerProfilePanel,
} from "../features/accounts/v2/CustomerPanels";
import {
  seedAuthenticatedV2Fixture,
  selectCustomerScenario,
  selectHistoryScenario,
  selectLimitsScenario,
} from "./v2-auth";
import styles from "../layouts/AppLayout.module.css";
import previewStyles from "../features/auth/v2/AuthSplitLayout.module.css";
import homeStyles from "./CustomerHome.module.css";

type Scenario = "default" | "empty" | "slow" | "error";
type View =
  | "accounts"
  | "profile"
  | "detail"
  | "history"
  | "transaction"
  | "limits";
export function prepareCustomerPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  selectCustomerScenario("default");
  selectHistoryScenario("default");
  selectLimitsScenario("default");
  seedAuthenticatedV2Fixture();
}
function Problem({ error, retry }: { error: unknown; retry: () => void }) {
  const code = error instanceof ApiError ? error.code : "";
  const message =
    code === "AUTH_FORBIDDEN"
      ? "Bạn không có quyền xem tài khoản này."
      : code === "ACCOUNT_NOT_FOUND"
        ? "Không tìm thấy tài khoản."
        : "Không thể tải dữ liệu. Vui lòng thử lại.";
  return (
    <Alert
      type="error"
      showIcon
      role="alert"
      message={message}
      action={<Button onClick={retry}>Thử lại</Button>}
    />
  );
}
export function CustomerDashboard({
  client,
  userId = "fixture-1001",
  displayName = "Khách hàng",
  mode = "customerPreview",
  logout,
  ending = false,
}: {
  client: ApiClient;
  userId?: string;
  displayName?: string;
  mode?: "customerPreview" | "authPreview";
  logout?: () => Promise<void>;
  ending?: boolean;
}) {
  const [scenario, setScenario] = useState<Scenario>("default");
  const [historyScenario, setHistoryScenario] = useState<Scenario>("default");
  const [limitsScenario, setLimitsScenario] = useState<Scenario>("default");
  const location = useLocation(),
    navigate = useNavigate();
  const detailMatch = useMatch("/customer/accounts/:accountId");
  const transactionMatch = useMatch("/customer/history/:reference");
  const view: View = detailMatch
    ? "detail"
    : transactionMatch
      ? "transaction"
      : location.pathname === "/customer/history"
        ? "history"
        : location.pathname === "/customer/limits"
          ? "limits"
          : location.pathname === "/customer/profile"
            ? "profile"
            : "accounts";
  const accountId = detailMatch?.params.accountId ?? "";
  const go = (path: string) => void navigate(`${path}?${mode}=v2`);
  const profile = useQuery({
    queryKey: [userId, "profile", scenario],
    queryFn: ({ signal }) => customerApi.profile(client, signal),
    enabled: view === "profile",
    retry: false,
  });
  const accounts = useQuery({
    queryKey: [userId, "accounts", scenario],
    queryFn: ({ signal }) => customerApi.accounts(client, signal),
    enabled: view === "accounts",
    retry: false,
  });
  const detail = useQuery({
    queryKey: [userId, "account", accountId, scenario],
    queryFn: ({ signal }) => customerApi.detail(client, accountId, signal),
    enabled: view === "detail",
    retry: false,
  });
  const current =
    view === "profile" ? profile : view === "detail" ? detail : accounts;
  const openDetail = (id: string) => {
    go(`/customer/accounts/${id}`);
  };
  if (
    ![
      "/customer",
      "/customer/profile",
      "/customer/history",
      "/customer/limits",
    ].includes(location.pathname) &&
    !detailMatch &&
    !transactionMatch
  )
    return <Navigate to={`/customer?${mode}=v2`} replace />;
  return (
    <>
      <details className={previewStyles.previewTools}>
        <summary>Điều khiển bản xem thử</summary>
        <aside
          className="preview-toolbar"
          aria-label="Điều khiển customer mock"
        >
          <strong>Customer v2 · dữ liệu tổng hợp</strong>
          <Select
            aria-label="Kịch bản customer"
            value={scenario}
            style={{ width: 220 }}
            onChange={(value: Scenario) => {
              selectCustomerScenario(value);
              setScenario(value);
            }}
            options={[
              { value: "default", label: "Dữ liệu mẫu" },
              { value: "empty", label: "Danh sách rỗng" },
              { value: "slow", label: "Tải chậm" },
              { value: "error", label: "Lỗi dịch vụ" },
            ]}
          />
          {(view === "history" || view === "transaction") && (
            <Select
              aria-label="Kịch bản lịch sử"
              value={historyScenario}
              style={{ width: 220 }}
              onChange={(value: Scenario) => {
                selectHistoryScenario(value);
                setHistoryScenario(value);
              }}
              options={[
                { value: "default", label: "Lịch sử mẫu" },
                { value: "empty", label: "Lịch sử rỗng" },
                { value: "slow", label: "Lịch sử tải chậm" },
                { value: "error", label: "Lịch sử lỗi dịch vụ" },
              ]}
            />
          )}
          {view === "limits" && (
            <Select
              aria-label="Kịch bản hạn mức"
              value={limitsScenario}
              style={{ width: 220 }}
              onChange={(value: Scenario) => {
                selectLimitsScenario(value);
                setLimitsScenario(value);
              }}
              options={[
                { value: "default", label: "Hạn mức mẫu" },
                { value: "empty", label: "Hạn mức rỗng" },
                { value: "slow", label: "Hạn mức tải chậm" },
                { value: "error", label: "Hạn mức lỗi dịch vụ" },
              ]}
            />
          )}
          <div className="actions">
            <Button
              onClick={() => {
                selectCustomerScenario("default");
                setScenario("default");
                openDetail("3002");
              }}
            >
              Xem fixture 403
            </Button>
            <Button
              onClick={() => {
                selectCustomerScenario("default");
                setScenario("default");
                openDetail("9999");
              }}
            >
              Xem fixture 404
            </Button>
            <a href="/login?authPreview=v2">Xem auth v2</a>
          </div>
          <span>
            Fixture chỉ phục vụ xem UI; không chứng minh quyền hay phiên
            backend.
          </span>
        </aside>
      </details>
      <div className={`${styles.shell} ${styles.customer} ${homeStyles.shell}`}>
        <aside className={`${styles.sidebar} ${homeStyles.sidebar}`}>
          <nav className={homeStyles.nav} aria-label="Điều hướng khách hàng">
            <button
              type="button"
              className={`${homeStyles.navItem} ${view === "accounts" ? homeStyles.active : ""}`}
              onClick={() => go("/customer")}
              aria-current={view === "accounts" ? "page" : undefined}
            >
              <Icon name="home" /> <span>Trang chủ</span>
            </button>
            <button
              type="button"
              className={`${homeStyles.navItem} ${view === "detail" ? homeStyles.active : ""}`}
              onClick={() => {
                const id = accountId || accounts.data?.items[0]?.accountId;
                if (id) openDetail(id);
                else go("/customer");
              }}
              aria-current={view === "detail" ? "page" : undefined}
            >
              <Icon name="account" /> <span>Thông tin tài khoản</span>
              <Icon
                name="chevronRight"
                size="sm"
                className={homeStyles.chevron}
              />
            </button>
            <button
              type="button"
              className={`${homeStyles.navItem} ${view === "profile" ? homeStyles.active : ""}`}
              onClick={() => go("/customer/profile")}
              aria-current={view === "profile" ? "page" : undefined}
            >
              <Icon name="profile" /> <span>Hồ sơ của tôi</span>
              <Icon
                name="chevronRight"
                size="sm"
                className={homeStyles.chevron}
              />
            </button>
            <button
              type="button"
              className={`${homeStyles.navItem} ${view === "limits" ? homeStyles.active : ""}`}
              onClick={() => go("/customer/limits")}
              aria-current={view === "limits" ? "page" : undefined}
            >
              <Icon name="limits" /> <span>Hạn mức của tôi</span>
              <Icon
                name="chevronRight"
                size="sm"
                className={homeStyles.chevron}
              />
            </button>
            <button
              type="button"
              className={`${homeStyles.navItem} ${view === "history" || view === "transaction" ? homeStyles.active : ""}`}
              onClick={() => go("/customer/history")}
              aria-current={
                view === "history" || view === "transaction"
                  ? "page"
                  : undefined
              }
            >
              <Icon name="history" />
              <span>Lịch sử giao dịch</span>
              <Icon
                name="chevronRight"
                size="sm"
                className={homeStyles.chevron}
              />
            </button>
          </nav>
          <div className={homeStyles.sidebarNote}>
            Digital Banking Simulator
            <br />
            Dữ liệu hiển thị chỉ để xem thử giao diện.
          </div>
        </aside>
        <div className={styles.workspace}>
          <header className={`${styles.topbar} ${homeStyles.topbar}`}>
            <Brand compact />
            <div className={homeStyles.welcome}>
              <span>Chào mừng trở lại</span>
              <strong>{displayName}</strong>
            </div>
            <div className={styles.topActions}>
              <Simulation />
              {logout && (
                <Button
                  disabled={ending}
                  loading={ending}
                  onClick={() => void logout()}
                >
                  Đăng xuất
                </Button>
              )}
            </div>
          </header>
          <main className={`${styles.main} ${homeStyles.main}`}>
            <div className={styles.customerPreviewNav}>
              <Button onClick={() => go("/customer")}>Trang chủ</Button>
              <Button onClick={() => go("/customer/profile")}>Hồ sơ</Button>
              <Button onClick={() => go("/customer/history")}>Lịch sử</Button>
              <Button onClick={() => go("/customer/limits")}>Hạn mức</Button>
            </div>
            {view === "limits" ? (
              <LimitsView
                client={client}
                userId={userId}
                scenario={limitsScenario}
              />
            ) : view === "history" || view === "transaction" ? (
              <HistoryView
                key={location.search}
                client={client}
                userId={userId}
                mode={mode}
                reference={transactionMatch?.params.reference}
                scenario={historyScenario}
              />
            ) : (
              <>
                <Heading
                  title={
                    view === "profile"
                      ? "Hồ sơ"
                      : view === "detail"
                        ? "Chi tiết tài khoản"
                        : "Tổng quan"
                  }
                  sub={
                    view === "profile"
                      ? "Thông tin cá nhân chỉ đọc."
                      : view === "detail"
                        ? "Thông tin tài khoản thuộc quyền sở hữu của bạn."
                        : "Tài khoản của tôi"
                  }
                />
                {view === "detail" && (
                  <Button onClick={() => go("/customer")}>
                    Quay lại danh sách
                  </Button>
                )}
                {current.isPending && (
                  <div role="status">
                    <Spin /> Đang tải dữ liệu
                  </div>
                )}
                {current.isError && (
                  <Problem
                    error={current.error}
                    retry={() => void current.refetch()}
                  />
                )}
                {view === "profile" && !profile.isError && profile.data && (
                  <CustomerProfilePanel profile={profile.data} />
                )}
                {view === "accounts" &&
                  !accounts.isError &&
                  accounts.data &&
                  (accounts.data.items.length === 0 ? (
                    <div className={homeStyles.emptyAccounts}>
                      <Illustration name="emptyAccounts" />
                      <p>Chưa có tài khoản.</p>
                    </div>
                  ) : (
                    <div className={homeStyles.accountGrid}>
                      {accounts.data.items.map((a) => (
                        <div
                          className={homeStyles.accountRow}
                          key={a.accountId}
                        >
                          <CustomerAccountCard
                            account={a}
                            onDetails={() => openDetail(a.accountId)}
                          />
                          <Panel title="Thông tin tài khoản">
                            <Descriptions
                              column={1}
                              colon={false}
                              items={[
                                {
                                  key: "number",
                                  label: "Số tài khoản",
                                  children: a.accountNumberMasked,
                                },
                                {
                                  key: "currency",
                                  label: "Loại tiền",
                                  children: a.currency,
                                },
                                {
                                  key: "snapshot",
                                  label: "Cập nhật",
                                  children: date(a.asOf),
                                },
                              ]}
                            />
                          </Panel>
                        </div>
                      ))}
                    </div>
                  ))}
                {view === "detail" && !detail.isError && detail.data && (
                  <CustomerAccountDetailPanel account={detail.data} />
                )}
                {view === "accounts" && (
                  <RecentTransactions
                    client={client}
                    userId={userId}
                    mode={mode}
                  />
                )}
              </>
            )}
          </main>
        </div>
      </div>
    </>
  );
}
export function CustomerPreview() {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 0 } },
      }),
  );
  const allowed =
    (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true") ||
    import.meta.env.MODE === "test";
  const [client, setClient] = useState<ApiClient | null>(null);
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
          <CustomerDashboard client={client} />
        ) : (
          <div role="status">Đang chuẩn bị bản xem</div>
        )}
      </BrowserRouter>
    </QueryClientProvider>
  );
}
