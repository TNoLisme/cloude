import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Result,
} from "antd";
import { Link } from "react-router-dom";
import {
  accountCheck,
  ApiError,
  get,
  post,
  proofCheck,
  queryString,
  uncertain,
  type Schema,
} from "../../api/client";
import { session } from "../../stores/session";
import { operatorFlow, type SeedIntent } from "../../stores/operator";
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
  emailRules,
  passwordRules,
  phoneRules,
  useAction,
  useNow,
} from "../../components/shared";
import { queryClient } from "../../app/query";

export function OperatorPage() {
  const user = session((s) => s.user);
  const [lookup, setLookup] = useState<Record<string, string> | null>(null);
  const [mode, setMode] = useState<"phone" | "email">("phone");
  const [searchForm] = Form.useForm();
  const [selected, setSelected] = useState<{
    account: Schema<"Account">;
    action: "seed" | "block" | "unblock";
  } | null>(null);
  const [intent, setIntent] = useState<SeedIntent | null>(null);
  const pending = operatorFlow((s) => s.seed);
  const [reason, setReason] = useState("");
  const [success, setSuccess] = useState("");
  const [form] = Form.useForm();
  const action = useAction();
  const now = useNow();
  const query = useQuery({
    queryKey: [user?.userId, "operator-customer", lookup],
    enabled: !!lookup,
    queryFn: ({ signal }) =>
      get<Schema<"OperatorCustomerView">>(
        `/operator/customers${queryString(lookup || {})}`,
        signal,
        (v) =>
          ["customerId", "fullName", "phone", "email"].every(
            (k) => typeof v[k] === "string",
          ) &&
          Array.isArray(v.accounts) &&
          v.accounts.every(
            (a) =>
              !!a &&
              typeof a === "object" &&
              accountCheck(a as Record<string, unknown>),
          ),
      ),
  });
  const wait =
    query.error instanceof ApiError
      ? Math.max(0, Math.ceil((query.error.retryAt - now) / 1000))
      : 0;
  async function reloadLookup() {
    await queryClient.invalidateQueries({
      queryKey: [user?.userId, "operator-customer"],
    });
  }
  function close() {
    if (action.busy || pending?.unknown) return;
    setSelected(null);
    setIntent(null);
    setReason("");
    action.setError(null);
    form.resetFields();
    operatorFlow.getState().setSeed(null);
  }
  async function seedSubmit(next: SeedIntent) {
    await action.run(async () => {
      operatorFlow.getState().setSeed(next);
      try {
        const response = await post<Schema<"SeedBalanceResponse">>(
          `/operator/accounts/${next.accountId}/seed-balance`,
          next.body,
          true,
          next.key,
          (v) =>
            typeof v.seedTransactionId === "string" &&
            typeof v.balanceAfter === "string" &&
            /^\d+$/.test(v.balanceAfter),
        );
        operatorFlow.getState().setSeed(null);
        setIntent(null);
        setSelected(null);
        form.resetFields();
        setSuccess(
          `Đã nạp số dư mô phỏng. Số dư xác nhận: ${response.balanceAfter.replace(/\B(?=(\d{3})+(?!\d))/g, ".")} ₫`,
        );
        void reloadLookup();
      } catch (e) {
        if (
          uncertain(e) ||
          (e instanceof ApiError && e.code === "IDEMPOTENCY_KEY_REUSED")
        )
          operatorFlow.getState().setSeed({ ...next, unknown: true });
        else operatorFlow.getState().setSeed(null);
        throw e;
      }
    });
  }
  const seed = pending || intent;
  return (
    <>
      <Heading
        title="Tra cứu khách hàng"
        sub="Tìm chính xác theo số điện thoại hoặc email."
        action={
          <Link to="/staff/customers/new">
            <Button type="primary">Tạo khách tại quầy</Button>
          </Link>
        }
      />
      {success && (
        <Alert
          className="flow-alert"
          type="success"
          showIcon
          closable
          onClose={() => setSuccess("")}
          message={success}
        />
      )}
      {pending?.unknown && (
        <Alert
          className="flow-alert"
          showIcon
          type="warning"
          message={`Chưa xác định kết quả nạp vào ${pending.masked}. Đối soát bằng cùng mã yêu cầu trong dialog trước khi tạo lần nạp khác.`}
        />
      )}
      <Panel title="Thông tin tra cứu">
        <Form
          form={searchForm}
          layout="vertical"
          className="search-form"
          onFinish={(v) => {
            if (!wait) {
              setLookup({ [mode]: v.identifier });
              setSuccess("");
            }
          }}
        >
          <Form.Item label="Tìm theo">
            <Select
              value={mode}
              onChange={(v) => {
                setMode(v);
                setLookup(null);
                searchForm.resetFields();
              }}
              options={[
                { value: "phone", label: "Số điện thoại" },
                { value: "email", label: "Email" },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="identifier"
            label={mode === "phone" ? "Số điện thoại" : "Email"}
            rules={mode === "phone" ? phoneRules : emailRules}
          >
            <Input
              type={mode === "phone" ? "tel" : "email"}
              placeholder="Nhập thông tin chính xác"
              onChange={() => setLookup(null)}
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            disabled={!!wait}
            loading={query.isFetching}
          >
            {wait ? `Chờ ${wait}s` : "Tra cứu"}
          </Button>
        </Form>
      </Panel>
      <div className="spaced">
        <ErrorPanel
          error={
            query.error &&
            !(
              query.error instanceof ApiError &&
              query.error.code === "CUSTOMER_NOT_FOUND"
            )
              ? query.error
              : null
          }
          retry={wait ? undefined : () => void query.refetch()}
        />
        {lookup && query.isPending ? (
          <Loading />
        ) : query.data && lookup ? (
          <Panel
            title={query.data.fullName}
            sub={`${query.data.phone} · ${query.data.email}`}
          >
            <h3>Tài khoản khách hàng</h3>
            {query.data.accounts.length ? (
              query.data.accounts.map((account) => (
                <div key={account.accountId} className="operator-account">
                  <div>
                    <div className="account-label">
                      VND · {account.accountNumberMasked}{" "}
                      <Status value={account.status} />
                    </div>
                    <p className="muted small spaced">Số dư mô phỏng</p>
                    <Money value={account.balance} className="balance-value" />
                    <div className="actions spaced">
                      <Button
                        type="primary"
                        disabled={account.status !== "ACTIVE" || !!pending}
                        onClick={() => {
                          setSelected({ account, action: "seed" });
                          form.resetFields();
                          action.setError(null);
                        }}
                      >
                        Nạp số dư mô phỏng
                      </Button>
                      <Button
                        disabled={account.status === "CLOSED" || !!pending}
                        danger={account.status === "ACTIVE"}
                        onClick={() => {
                          setSelected({
                            account,
                            action:
                              account.status === "BLOCKED"
                                ? "unblock"
                                : "block",
                          });
                          setReason("");
                          action.setError(null);
                        }}
                      >
                        {account.status === "BLOCKED"
                          ? "Mở khóa tài khoản"
                          : "Khóa tài khoản"}
                      </Button>
                    </div>
                  </div>
                </div>
              ))
            ) : (
              <EmptyState text="Khách hàng chưa có tài khoản." />
            )}
          </Panel>
        ) : (
          <Panel>
            <EmptyState
              text={
                query.error instanceof ApiError &&
                query.error.code === "CUSTOMER_NOT_FOUND"
                  ? "Không tìm thấy khách hàng theo thông tin tra cứu."
                  : "Nhập số điện thoại hoặc email để tra cứu."
              }
            />
          </Panel>
        )}
      </div>
      <Modal
        open={!!selected || !!pending}
        title={
          seed
            ? "Xác nhận nạp số dư?"
            : selected?.action === "seed"
              ? "Nạp số dư mô phỏng"
              : selected?.action === "block"
                ? "Khóa tài khoản?"
                : "Mở khóa tài khoản?"
        }
        onCancel={close}
        closable={!action.busy && !pending?.unknown}
        maskClosable={false}
        keyboard={!action.busy && !pending?.unknown}
        footer={null}
        destroyOnHidden
      >
        <ErrorPanel error={action.error} />
        {seed ? (
          <>
            <Descriptions
              column={1}
              items={[
                { key: "account", label: "Tài khoản", children: seed.masked },
                {
                  key: "amount",
                  label: "Số tiền",
                  children: <Money value={seed.body.amount} />,
                },
                {
                  key: "reference",
                  label: "Tham chiếu",
                  children: seed.body.reference,
                },
              ]}
            />
            {seed.unknown && (
              <Alert
                className="flow-alert"
                type="warning"
                message="Yêu cầu có thể đã được xử lý. Gửi lại cùng mã và nội dung để tránh nạp lần hai."
              />
            )}
            <div className="form-actions">
              {!seed.unknown && (
                <Button disabled={action.busy} onClick={close}>
                  Hủy
                </Button>
              )}
              <ActionButton
                action={action}
                onClick={() => void seedSubmit(seed)}
              >
                {seed.unknown ? "Đối soát cùng yêu cầu" : "Xác nhận nạp"}
              </ActionButton>
            </div>
          </>
        ) : selected?.action === "seed" ? (
          <Form
            layout="vertical"
            form={form}
            onFinish={(v) =>
              setIntent({
                accountId: selected.account.accountId,
                masked: selected.account.accountNumberMasked,
                key: crypto.randomUUID(),
                body: {
                  amount: v.amount,
                  currency: "VND",
                  reference: v.reference,
                },
                unknown: false,
              })
            }
          >
            <Form.Item
              name="amount"
              label="Số tiền VND"
              rules={[
                { required: true },
                {
                  pattern: /^(?:[1-9]\d{0,7}|100000000)$/,
                  message: "Số tiền nguyên từ 1 đến 100.000.000 ₫.",
                },
              ]}
            >
              <Input inputMode="numeric" maxLength={9} />
            </Form.Item>
            <Form.Item
              name="reference"
              label="Tham chiếu"
              rules={[{ required: true, whitespace: true, max: 100 }]}
            >
              <Input maxLength={100} />
            </Form.Item>
            <Note>Số dư chỉ cập nhật sau khi hệ thống xác nhận.</Note>
            <Button block type="primary" htmlType="submit">
              Kiểm tra và xác nhận
            </Button>
          </Form>
        ) : (
          selected && (
            <>
              <p>
                VND · {selected.account.accountNumberMasked}{" "}
                <Status value={selected.account.status} />
              </p>
              <Form
                layout="vertical"
                onFinish={() =>
                  action.run(async () => {
                    const current = selected;
                    await post<Schema<"Account">>(
                      `/operator/accounts/${current.account.accountId}/${current.action}`,
                      { reason },
                      true,
                      undefined,
                      accountCheck,
                    );
                    setSelected(null);
                    setSuccess(
                      "Trạng thái tài khoản đã được hệ thống xác nhận.",
                    );
                    void reloadLookup();
                  })
                }
              >
                <Form.Item
                  label="Lý do bắt buộc"
                  name="reason"
                  rules={[{ required: true, whitespace: true, max: 500 }]}
                >
                  <Input.TextArea
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    maxLength={500}
                  />
                </Form.Item>
                <Note>
                  Nếu kết nối bị gián đoạn, tra cứu lại trạng thái trước khi
                  thực hiện hành động tiếp theo.
                </Note>
                <div className="form-actions">
                  <Button disabled={action.busy} onClick={close}>
                    Hủy
                  </Button>
                  <ActionButton action={action} htmlType="submit">
                    {selected.action === "block"
                      ? "Xác nhận khóa"
                      : "Xác nhận mở khóa"}
                  </ActionButton>
                </div>
              </Form>
            </>
          )
        )}
      </Modal>
    </>
  );
}
export function CreateCustomerPage() {
  const [form] = Form.useForm();
  const action = useAction();
  const [sentPhone, setSentPhone] = useState<string | null>(null);
  const [created, setCreated] = useState<Schema<"RegistrationResponse"> | null>(
    null,
  );
  const [confirm, setConfirm] = useState(false);
  if (created)
    return (
      <>
        <Heading title="Tạo khách tại quầy" />
        <Panel narrow>
          <Result
            status="success"
            title="Đã tạo khách hàng"
            subTitle={`Tài khoản VND · ${created.account.accountNumberMasked}`}
            extra={
              <Link to="/staff/customers">
                <Button type="primary">Tra cứu và nạp số dư</Button>
              </Link>
            }
          />
        </Panel>
      </>
    );
  return (
    <>
      <Heading
        title="Tạo khách tại quầy"
        sub="OTP gửi đến điện thoại khách hàng trước khi xác nhận tạo tài khoản."
      />
      <Panel narrow title="Thông tin khách hàng">
        <ErrorPanel error={action.error} />
        {!!action.error && uncertain(action.error) && (
          <Alert
            className="flow-alert"
            type="warning"
            message="Chưa rõ kết quả tạo khách. Hãy tra cứu chính xác trước khi gửi lại."
          />
        )}
        <Form
          layout="vertical"
          form={form}
          scrollToFirstError
          onValuesChange={(changed) => {
            if ("phone" in changed) setSentPhone(null);
          }}
          onFinish={() => setConfirm(true)}
        >
          <Form.Item
            name="phone"
            label="Số điện thoại khách"
            rules={phoneRules}
          >
            <Input type="tel" />
          </Form.Item>
          <ActionButton
            action={action}
            onClick={() => {
              void form
                .validateFields(["phone"])
                .then((v) =>
                  action.run(async () => {
                    await post<Schema<"SendOtpResponse">>(
                      "/operator/customers/send-otp",
                      { phone: v.phone },
                      true,
                      undefined,
                      proofCheck("phone"),
                    );
                    setSentPhone(v.phone);
                  }),
                )
                .catch(() => undefined);
            }}
          >
            Gửi OTP khách hàng
          </ActionButton>
          {sentPhone && (
            <Alert
              className="flow-alert spaced"
              showIcon
              type="info"
              message="OTP đã gửi đến điện thoại khách. Hệ thống xác minh khi tạo tài khoản."
            />
          )}
          <Form.Item
            className="spaced"
            name="fullName"
            label="Họ và tên"
            rules={[{ required: true, whitespace: true, max: 120 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="email" label="Email" rules={emailRules}>
            <Input type="email" />
          </Form.Item>
          <Form.Item
            name="initialPassword"
            label="Mật khẩu ban đầu"
            rules={passwordRules}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="address"
            label="Địa chỉ (tùy chọn)"
            rules={[{ max: 300 }]}
          >
            <Input.TextArea rows={2} maxLength={300} />
          </Form.Item>
          <CodeField />
          <Button
            type="primary"
            htmlType="submit"
            block
            disabled={!sentPhone || action.disabled}
          >
            Kiểm tra và tạo tài khoản
          </Button>
        </Form>
        <Modal
          open={confirm}
          title="Xác nhận tạo tài khoản khách?"
          maskClosable={false}
          closable={!action.busy}
          onCancel={() => {
            if (!action.busy) setConfirm(false);
          }}
          footer={null}
        >
          <p>
            Hệ thống sẽ xác minh OTP của khách và tạo tài khoản VND mô phỏng.
          </p>
          <ErrorPanel error={action.error} />
          <div className="form-actions">
            <Button disabled={action.busy} onClick={() => setConfirm(false)}>
              Hủy
            </Button>
            <ActionButton
              action={action}
              onClick={() =>
                void action.run(async () => {
                  const v = form.getFieldsValue();
                  if (!sentPhone || sentPhone !== v.phone) return;
                  try {
                    const result = await post<Schema<"RegistrationResponse">>(
                      "/operator/customers",
                      {
                        phone: v.phone,
                        email: v.email,
                        fullName: v.fullName,
                        initialPassword: v.initialPassword,
                        otp: v.otp,
                        ...(v.address ? { address: v.address } : {}),
                      },
                      true,
                      undefined,
                      (x) => typeof x.customerId === "string" && !!x.account,
                    );
                    setCreated(result);
                    setConfirm(false);
                  } finally {
                    form.resetFields(["otp", "initialPassword"]);
                    setConfirm(false);
                  }
                })
              }
            >
              Tạo tài khoản
            </ActionButton>
          </div>
        </Modal>
      </Panel>
    </>
  );
}
