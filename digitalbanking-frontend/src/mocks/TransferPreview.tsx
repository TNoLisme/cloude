import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Input, Select, Spin, Steps } from "antd";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { Brand, Heading, Panel } from "../components/shared";
import { createApiClient, type ApiClient } from "../shared/api/client";
import { customerApi } from "../features/accounts/v2/customer-api";
import { formatMoney, isMoney, moneyToMinorUnits } from "../shared/lib/money";
import { seedAuthenticatedV2Fixture, selectCustomerScenario } from "./v2-auth";
import styles from "../layouts/AppLayout.module.css";

type Scenario = "default" | "empty" | "slow" | "error";
const accountNumberPattern = /^[0-9]{6,20}$/;
export function prepareTransferPreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  selectCustomerScenario("default");
  seedAuthenticatedV2Fixture();
}
function Content({ client }: { client: ApiClient }) {
  const [scenario, setScenario] = useState<Scenario>("default");
  const [sourceAccountId, setSourceAccountId] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [review, setReview] = useState(false);
  const [error, setError] = useState("");
  const accounts = useQuery({
    queryKey: ["transfer-preview-accounts", scenario],
    queryFn: ({ signal }) => customerApi.accounts(client, signal),
    retry: false,
  });
  const available = (accounts.data?.items ?? []).filter(
    (item) => item.status === "ACTIVE" && item.currency === "VND",
  );
  const source = available.find((item) => item.accountId === sourceAccountId);
  function showReview() {
    if (!source) {
      setError("Chọn tài khoản nguồn trong danh sách hiện có.");
      return;
    }
    if (!accountNumberPattern.test(accountNumber)) {
      setError("Số tài khoản nhận phải gồm 6–20 chữ số theo schema.");
      return;
    }
    if (!isMoney(amount) || moneyToMinorUnits(amount) <= 0n) {
      setError("Số tiền phải lớn hơn 0 và có đúng hai chữ số thập phân.");
      return;
    }
    if (description.length > 255) {
      setError("Lời nhắn tối đa 255 ký tự.");
      return;
    }
    setError("");
    setReview(true);
  }
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview customer">
          Chuyển tiền · bản xem
        </nav>
        <p>Fixture tổng hợp · không gửi lệnh</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Chuyển tiền</span>
        </header>
        <main className={styles.main}>
          <Heading
            title="Chuyển tiền"
            sub="Bản xem form và bước kiểm tra theo schema; chưa xác minh người nhận hoặc tạo giao dịch."
          />
          <Steps
            current={review ? 1 : 0}
            items={[
              { title: "Nhập thông tin" },
              { title: "Kiểm tra" },
              { title: "Kết quả · chờ contract" },
            ]}
            style={{ maxWidth: 760, margin: "0 auto 24px" }}
          />
          <Panel
            title={
              review ? "Kiểm tra thông tin đã nhập" : "Thông tin chuyển tiền"
            }
          >
            <Alert
              type="warning"
              showIcon
              message="Số tài khoản nhận chưa được hệ thống xác minh. Không có lệnh chuyển tiền nào được gửi từ bản xem này."
              style={{ marginBottom: 16 }}
            />
            {!review ? (
              <>
                <label htmlFor="transfer-scenario">
                  Kịch bản tài khoản nguồn
                </label>
                <Select
                  id="transfer-scenario"
                  aria-label="Kịch bản tài khoản nguồn"
                  value={scenario}
                  style={{ display: "block", width: 200, marginBottom: 16 }}
                  onChange={(value: Scenario) => {
                    selectCustomerScenario(value);
                    setScenario(value);
                    setSourceAccountId("");
                  }}
                  options={[
                    { value: "default", label: "Dữ liệu mẫu" },
                    { value: "empty", label: "Danh sách rỗng" },
                    { value: "slow", label: "Tải chậm" },
                    { value: "error", label: "Lỗi dịch vụ" },
                  ]}
                />
                {accounts.isPending && (
                  <div role="status">
                    <Spin /> Đang tải tài khoản nguồn
                  </div>
                )}
                {accounts.isError && (
                  <Alert
                    type="error"
                    role="alert"
                    message="Không thể tải tài khoản nguồn."
                    action={
                      <Button onClick={() => void accounts.refetch()}>
                        Thử lại
                      </Button>
                    }
                  />
                )}
                {accounts.data && (
                  <>
                    <label htmlFor="transfer-source">Tài khoản nguồn</label>
                    <Select
                      id="transfer-source"
                      aria-label="Tài khoản nguồn"
                      placeholder="Chọn tài khoản"
                      value={sourceAccountId || undefined}
                      onChange={setSourceAccountId}
                      style={{
                        display: "block",
                        width: "100%",
                        maxWidth: 420,
                        marginBottom: 16,
                      }}
                      options={available.map((item) => ({
                        value: item.accountId,
                        label: `${item.accountNumberMasked} · ${item.currency}`,
                      }))}
                    />
                    {!available.length && (
                      <p>Không có tài khoản nguồn ACTIVE trong fixture.</p>
                    )}
                  </>
                )}
                <label htmlFor="transfer-destination">Số tài khoản nhận</label>
                <Input
                  id="transfer-destination"
                  inputMode="numeric"
                  autoComplete="off"
                  value={accountNumber}
                  onChange={(event) => setAccountNumber(event.target.value)}
                  maxLength={20}
                  style={{ display: "block", maxWidth: 420, marginBottom: 16 }}
                />
                <label htmlFor="transfer-amount">
                  Số tiền (VND, hai số lẻ)
                </label>
                <Input
                  id="transfer-amount"
                  inputMode="decimal"
                  placeholder="150000.00"
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  style={{ display: "block", maxWidth: 420, marginBottom: 16 }}
                />
                <label htmlFor="transfer-description">
                  Lời nhắn (không bắt buộc)
                </label>
                <Input.TextArea
                  id="transfer-description"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={255}
                  rows={3}
                  style={{ display: "block", maxWidth: 520, marginBottom: 16 }}
                />
                {error && (
                  <Alert
                    type="error"
                    role="alert"
                    message={error}
                    style={{ marginBottom: 16 }}
                  />
                )}
                <Button type="primary" onClick={showReview}>
                  Xem lại thông tin
                </Button>
              </>
            ) : (
              <>
                <Descriptions
                  column={1}
                  bordered
                  items={[
                    {
                      key: "source",
                      label: "Tài khoản nguồn",
                      children: source?.accountNumberMasked ?? "—",
                    },
                    {
                      key: "destination",
                      label: "Số tài khoản nhận (chưa xác minh)",
                      children: accountNumber,
                    },
                    {
                      key: "amount",
                      label: "Số tiền",
                      children: isMoney(amount) ? formatMoney(amount) : "—",
                    },
                    { key: "currency", label: "Tiền tệ", children: "VND" },
                    {
                      key: "description",
                      label: "Lời nhắn",
                      children: description || "—",
                    },
                  ]}
                />
                <div style={{ display: "flex", gap: 12, marginTop: 20 }}>
                  <Button onClick={() => setReview(false)}>
                    Sửa thông tin
                  </Button>
                  <Button type="primary" disabled>
                    Gửi lệnh · chờ chốt contract
                  </Button>
                </div>
                <p role="note">
                  Chờ GAP-02 xác minh người nhận, GAP-08 step-up/binding và
                  GAP-09 recovery trước thao tác gửi lệnh.
                </p>
              </>
            )}
          </Panel>
        </main>
      </div>
    </div>
  );
}
export function TransferPreview() {
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
      {client ? (
        <Content client={client} />
      ) : (
        <p role="status">Đang chuẩn bị bản xem</p>
      )}
    </QueryClientProvider>
  );
}
