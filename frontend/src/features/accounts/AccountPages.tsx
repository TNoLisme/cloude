import { useQuery } from "@tanstack/react-query";
import { Button, Descriptions, Form, Input, Alert } from "antd";
import {
  CreditCardOutlined,
  SwapOutlined,
  HistoryOutlined,
} from "@ant-design/icons";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  accountCheck,
  get,
  page,
  post,
  proofCheck,
  type Schema,
} from "../../api/client";
import { session } from "../../stores/session";
import { refreshCustomerData } from "../../app/query";
import {
  ActionButton,
  CodeField,
  EmptyState,
  ErrorPanel,
  Heading,
  Loading,
  Money,
  Note,
  Panel,
  Status,
  date,
  matchRule,
  useAction,
} from "../../components/shared";
import { HistoryList } from "../transfers/HistoryPages";
import { useState } from "react";

export function useAccounts() {
  const user = session((s) => s.user);
  return useQuery({
    queryKey: [user?.userId, "accounts"],
    queryFn: ({ signal }) =>
      page<Schema<"Account">>("/accounts", signal, accountCheck),
    enabled: !!user,
  });
}
export function useProfile() {
  const user = session((s) => s.user);
  return useQuery({
    queryKey: [user?.userId, "profile"],
    queryFn: ({ signal }) =>
      get<Schema<"CustomerProfile">>(
        "/customers/me",
        signal,
        (v) =>
          ["customerId", "fullName", "phone", "email", "createdAt"].every(
            (k) => typeof v[k] === "string",
          ) && typeof v.isPinSet === "boolean",
      ),
    enabled: !!user,
  });
}
function AccountInfo({
  account,
  owner,
}: {
  account: Schema<"Account">;
  owner?: string;
}) {
  return (
    <Descriptions
      column={1}
      colon={false}
      items={[
        { key: "owner", label: "Chủ tài khoản", children: owner },
        {
          key: "number",
          label: "Số tài khoản",
          children: account.accountNumberMasked,
        },
        { key: "currency", label: "Loại tiền", children: account.currency },
        {
          key: "status",
          label: "Trạng thái",
          children: <Status value={account.status} />,
        },
        { key: "date", label: "Ngày mở", children: date(account.openedAt) },
      ]}
    />
  );
}
export function DashboardPage() {
  const accounts = useAccounts();
  const user = session((s) => s.user);
  return (
    <>
      <Heading
        title="Tổng quan"
        sub={`Xin chào, ${user?.displayName}. Đây là tài khoản mô phỏng của bạn.`}
      />
      <ErrorPanel
        error={accounts.error}
        retry={() => void accounts.refetch()}
      />
      {accounts.isPending ? (
        <Loading />
      ) : !accounts.data?.items.length ? (
        !accounts.error && <EmptyState text="Bạn chưa có tài khoản." />
      ) : (
        accounts.data.items.map((account) => (
          <div className="overview-grid" key={account.accountId}>
            <Panel>
              <div className="balance-card">
                <div className="balance-top">
                  <span className="account-label">
                    <CreditCardOutlined /> Tài khoản VND
                  </span>
                  <Status value={account.status} />
                </div>
                <p className="muted small">
                  Số dư khả dụng · {account.accountNumberMasked}
                </p>
                <Money value={account.balance} className="balance-value" />
                <p className="muted small">Tiền VND giả lập trong hệ thống</p>
                <div className="actions spaced">
                  <Link to="/customer/transfers/new">
                    <Button type="primary" icon={<SwapOutlined />}>
                      Chuyển tiền
                    </Button>
                  </Link>
                  <Link to="/customer/transfers">
                    <Button icon={<HistoryOutlined />}>
                      Lịch sử giao dịch
                    </Button>
                  </Link>
                </div>
              </div>
            </Panel>
            <Panel title="Thông tin tài khoản">
              <AccountInfo account={account} owner={user?.displayName} />
              <Link to={`/customer/accounts/${account.accountId}`}>
                Xem chi tiết tài khoản →
              </Link>
            </Panel>
          </div>
        ))
      )}
      <Panel title="Giao dịch gần đây" sub="Các giao dịch đã hoàn tất của bạn">
        <HistoryList recent />
        <div className="panel-bottom">
          <Link to="/customer/transfers">Xem tất cả →</Link>
        </div>
      </Panel>
      <p className="footnote">
        Số dư được hiển thị theo kết quả đã xác nhận của hệ thống.
      </p>
    </>
  );
}
export function AccountPage() {
  const { accountId } = useParams();
  const user = session((s) => s.user);
  const query = useQuery({
    queryKey: [user?.userId, "account", accountId],
    queryFn: ({ signal }) =>
      get<Schema<"Account">>(
        `/accounts/${encodeURIComponent(accountId || "")}`,
        signal,
        accountCheck,
      ),
  });
  return (
    <>
      <Heading
        title="Chi tiết tài khoản"
        sub="Thông tin tài khoản thuộc quyền sở hữu của bạn."
      />
      <ErrorPanel error={query.error} retry={() => void query.refetch()} />
      {query.isPending ? (
        <Loading />
      ) : (
        query.data && (
          <Panel narrow title="Tài khoản VND">
            <Money value={query.data.balance} className="balance-value" />
            <AccountInfo account={query.data} owner={user?.displayName} />
            <Link to="/customer/transfers/new">
              <Button type="primary">Chuyển tiền</Button>
            </Link>
          </Panel>
        )
      )}
    </>
  );
}
export function ProfilePage() {
  const query = useProfile();
  return (
    <>
      <Heading title="Hồ sơ" sub="Thông tin cá nhân chỉ đọc." />
      <ErrorPanel error={query.error} retry={() => void query.refetch()} />
      {query.isPending ? (
        <Loading />
      ) : (
        query.data && (
          <Panel narrow title="Thông tin của bạn">
            <Descriptions
              column={1}
              items={[
                {
                  key: "name",
                  label: "Họ và tên",
                  children: query.data.fullName,
                },
                {
                  key: "phone",
                  label: "Điện thoại",
                  children: query.data.phone,
                },
                { key: "email", label: "Email", children: query.data.email },
                {
                  key: "date",
                  label: "Ngày tham gia",
                  children: date(query.data.createdAt),
                },
                {
                  key: "pin",
                  label: "PIN giao dịch",
                  children: query.data.isPinSet
                    ? "Đã thiết lập"
                    : "Chưa thiết lập",
                },
              ]}
            />
            <div className="actions">
              {query.data.isPinSet ? (
                <>
                  <Link to="/customer/pin/change">
                    <Button type="primary">Đổi PIN</Button>
                  </Link>
                  <Link to="/customer/pin/reset">
                    <Button>Quên PIN</Button>
                  </Link>
                </>
              ) : (
                <Link to="/customer/pin/setup">
                  <Button type="primary">Thiết lập PIN</Button>
                </Link>
              )}
            </div>
          </Panel>
        )
      )}
    </>
  );
}
export function PinPage({ mode }: { mode: "setup" | "change" | "reset" }) {
  const navigate = useNavigate();
  const action = useAction();
  const [form] = Form.useForm();
  const [sent, setSent] = useState(false);
  const [ttl, setTtl] = useState(0);
  return (
    <>
      <Heading
        title={
          mode === "setup"
            ? "Thiết lập PIN giao dịch"
            : mode === "change"
              ? "Đổi PIN giao dịch"
              : "Khôi phục PIN"
        }
        sub="PIN gồm 6 chữ số, được hệ thống xác minh khi chuyển tiền."
      />
      <Panel
        narrow
        title={
          mode === "setup"
            ? "Bảo vệ giao dịch của bạn"
            : "Xác nhận và đặt PIN mới"
        }
      >
        <ErrorPanel error={action.error} />
        {mode === "reset" && !sent ? (
          <>
            <Note>OTP gửi đến điện thoại đăng ký của bạn.</Note>
            <ActionButton
              action={action}
              onClick={() =>
                void action.run(async () => {
                  const response = await post<Schema<"SendOtpResponse">>(
                    "/customers/me/pin/forgot/initiate",
                    undefined,
                    true,
                    undefined,
                    proofCheck("phone"),
                  );
                  setTtl(response.expiresInSeconds);
                  setSent(true);
                })
              }
            >
              Gửi mã OTP
            </ActionButton>
          </>
        ) : (
          <Form
            key={mode}
            preserve={false}
            form={form}
            layout="vertical"
            scrollToFirstError
            onFinish={(v) =>
              action.run(async () => {
                try {
                  const body =
                    mode === "setup"
                      ? { pin: v.pin, confirmPin: v.confirmPin }
                      : mode === "change"
                        ? {
                            currentPin: v.currentPin,
                            newPin: v.pin,
                            confirmNewPin: v.confirmPin,
                          }
                        : {
                            otp: v.otp,
                            newPin: v.pin,
                            confirmNewPin: v.confirmPin,
                          };
                  await post<Schema<"MessageResponse">>(
                    `/customers/me/pin/${mode === "reset" ? "forgot/confirm" : mode}`,
                    body,
                  );
                  session.getState().pinConfigured();
                  refreshCustomerData();
                  navigate("/customer", { replace: true });
                } finally {
                  form.resetFields();
                }
              })
            }
          >
            {mode === "change" && (
              <CodeField name="currentPin" label="PIN hiện tại" secret />
            )}
            {mode === "reset" && (
              <>
                <Alert
                  className="flow-alert"
                  showIcon
                  type="info"
                  message={`OTP đã được gửi. Thời hạn do hệ thống cấp: ${ttl}s.`}
                />
                <CodeField />
              </>
            )}
            <CodeField
              name="pin"
              label={mode === "setup" ? "PIN giao dịch" : "PIN mới"}
              secret
            />
            <Form.Item
              name="confirmPin"
              label="Xác nhận PIN"
              dependencies={["pin"]}
              rules={[
                { required: true },
                { pattern: /^\d{6}$/, message: "PIN gồm 6 chữ số." },
                matchRule("pin", "PIN xác nhận"),
              ]}
            >
              <Input.Password
                inputMode="numeric"
                maxLength={6}
                autoComplete="off"
                visibilityToggle={false}
                className="code-input"
              />
            </Form.Item>
            <ActionButton action={action} htmlType="submit" block>
              Xác nhận PIN
            </ActionButton>
            {mode === "reset" && (
              <Button
                type="link"
                disabled={action.disabled}
                onClick={() => {
                  form.resetFields();
                  setSent(false);
                }}
              >
                Yêu cầu OTP mới
              </Button>
            )}
          </Form>
        )}
      </Panel>
    </>
  );
}
