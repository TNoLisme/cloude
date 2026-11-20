import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Input, Select, Spin } from "antd";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { BrowserRouter, useMatch } from "react-router-dom";
import { Brand, Heading, Panel, date } from "../components/shared";
import {
  createApiClient,
  ApiError,
  type ApiClient,
} from "../shared/api/client";
import { formatMoney } from "../shared/lib/money";
import { isApiId } from "../shared/lib/id";
import { staffCustomerApi } from "../features/staff/v2/staff-customer-api";
import {
  prepareStaffCustomerFixture,
  selectStaffCustomerScenario,
} from "./staff-customer-handlers";
import styles from "../layouts/AppLayout.module.css";

type Scenario = "default" | "empty" | "slow" | "error" | "forbidden";
export function prepareStaffCustomerPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  prepareStaffCustomerFixture();
}
function errorMessage(error: unknown) {
  const code = error instanceof ApiError ? error.code : "";
  if (code === "AUTH_FORBIDDEN")
    return "Bạn không có quyền xem khách hàng này.";
  if (code === "CUSTOMER_NOT_FOUND") return "Không tìm thấy khách hàng.";
  return "Không thể tải dữ liệu. Vui lòng thử lại.";
}
function Content({ client }: { client: ApiClient }) {
  const match = useMatch("/staff/customers/:customerId");
  const customerId = match?.params.customerId ?? "";
  const validId = isApiId(customerId);
  const [scenario, setScenario] = useState<Scenario>("default");
  const profile = useQuery({
    queryKey: ["staff-customer-preview", customerId, scenario],
    queryFn: ({ signal }) =>
      staffCustomerApi.profile(client, customerId, signal),
    enabled: validId,
    retry: false,
  });
  const accounts = useQuery({
    queryKey: ["staff-customer-accounts-preview", customerId, scenario],
    queryFn: ({ signal }) =>
      staffCustomerApi.accounts(client, customerId, signal),
    enabled: validId && !!profile.data,
    retry: false,
  });
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview staff">
          <span>Khách hàng · chỉ đọc</span>
        </nav>
        <p>Preview staff độc lập · dữ liệu tổng hợp</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Tra cứu khách hàng</span>
        </header>
        <main className={styles.main}>
          <Heading
            title="Thông tin khách hàng"
            sub="Bản xem mock theo customerId mẫu; quyền và phạm vi staff chờ BE/Security chốt."
          />
          <Panel title="Tìm khách hàng theo số điện thoại">
            <label htmlFor="staff-phone-search">Số điện thoại</label>
            <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
              <Input
                id="staff-phone-search"
                type="tel"
                disabled
                placeholder="Chờ contract tìm kiếm từ BE/BA"
                style={{ maxWidth: 300 }}
              />
              <Button disabled>Tra cứu</Button>
            </div>
            <p role="note">
              Chờ contract tìm theo số điện thoại. Bản xem bên dưới dùng
              customerId tổng hợp; không gửi yêu cầu tìm kiếm.
            </p>
          </Panel>
          <div style={{ marginTop: 20, marginBottom: 20 }}>
            <label htmlFor="staff-customer-scenario">Kịch bản mock</label>
            <Select
              id="staff-customer-scenario"
              aria-label="Kịch bản customer staff"
              value={scenario}
              style={{ width: 230, display: "block" }}
              onChange={(value: Scenario) => {
                selectStaffCustomerScenario(value);
                setScenario(value);
              }}
              options={[
                { value: "default", label: "Dữ liệu mẫu" },
                { value: "empty", label: "Không có tài khoản" },
                { value: "slow", label: "Tải chậm" },
                { value: "error", label: "Lỗi dịch vụ" },
                { value: "forbidden", label: "Không có quyền" },
              ]}
            />
          </div>
          {!validId ? (
            <Alert
              type="warning"
              role="alert"
              message="Customer ID không hợp lệ."
            />
          ) : (
            <>
              {profile.isPending && (
                <div role="status">
                  <Spin /> Đang tải hồ sơ
                </div>
              )}
              {profile.isError && (
                <Alert
                  type="error"
                  role="alert"
                  message={errorMessage(profile.error)}
                  action={
                    <Button onClick={() => void profile.refetch()}>
                      Thử lại
                    </Button>
                  }
                />
              )}
              {profile.data && (
                <Panel title={`Hồ sơ #${profile.data.customerId}`}>
                  <Descriptions
                    column={1}
                    colon={false}
                    items={[
                      {
                        key: "name",
                        label: "Họ và tên",
                        children: profile.data.fullName,
                      },
                      {
                        key: "phone",
                        label: "Điện thoại",
                        children:
                          profile.data.phoneMasked ?? "Chưa có thông tin",
                      },
                      {
                        key: "email",
                        label: "Email",
                        children:
                          profile.data.emailMasked ?? "Chưa có thông tin",
                      },
                      {
                        key: "status",
                        label: "Trạng thái",
                        children: profile.data.status,
                      },
                      {
                        key: "created",
                        label: "Tạo lúc",
                        children: date(profile.data.createdAt),
                      },
                    ]}
                  />
                </Panel>
              )}
              {profile.data && accounts.isPending && (
                <div role="status">
                  <Spin /> Đang tải tài khoản
                </div>
              )}
              {accounts.isError && (
                <Alert
                  type="error"
                  role="alert"
                  message={errorMessage(accounts.error)}
                  action={
                    <Button onClick={() => void accounts.refetch()}>
                      Thử lại
                    </Button>
                  }
                />
              )}
              {accounts.data && (
                <Panel title="Tài khoản của khách hàng">
                  {accounts.data.items.length ? (
                    accounts.data.items.map((account) => (
                      <Descriptions
                        key={account.accountId}
                        column={1}
                        colon={false}
                        items={[
                          {
                            key: "number",
                            label: "Số tài khoản",
                            children: account.accountNumberMasked,
                          },
                          {
                            key: "balance",
                            label: "Số dư khả dụng",
                            children: formatMoney(account.availableBalance),
                          },
                          {
                            key: "currency",
                            label: "Tiền tệ",
                            children: account.currency,
                          },
                          {
                            key: "status",
                            label: "Trạng thái",
                            children: account.status,
                          },
                          {
                            key: "asOf",
                            label: "Cập nhật",
                            children: date(account.asOf),
                          },
                        ]}
                      />
                    ))
                  ) : (
                    <p>Khách hàng chưa có tài khoản.</p>
                  )}
                  <p>Cập nhật danh sách: {date(accounts.data.asOf)}</p>
                </Panel>
              )}
            </>
          )}
        </main>
      </div>
    </div>
  );
}
export function StaffCustomerPreview() {
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
