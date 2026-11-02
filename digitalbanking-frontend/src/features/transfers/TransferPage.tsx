import { useRef, useState } from "react";
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  Select,
  Steps,
  Result,
} from "antd";
import { Link, useBeforeUnload } from "react-router-dom";
import {
  ApiError,
  get,
  post,
  transferCheck,
  uncertain,
  type Schema,
} from "../../api/client";
import {
  ActionButton,
  CodeField,
  Countdown,
  ErrorPanel,
  Heading,
  Loading,
  Money,
  Note,
  Panel,
  Status,
  useAction,
} from "../../components/shared";
import { useAccounts } from "../accounts/AccountPages";
import { refreshCustomerData } from "../../app/query";
import {
  transferFlow,
  nextTransferStage,
  type TransferIntent,
  type TransferResult,
} from "../../stores/transfer";

export function TransferOtp({
  transfer,
  onUpdate,
}: {
  transfer: TransferResult;
  onUpdate: (result: Schema<"Transfer">) => void;
}) {
  const action = useAction();
  const [form] = Form.useForm();
  const [unknown, setUnknown] = useState(false);
  async function reconcile() {
    await action.run(async () => {
      const result = await get<Schema<"Transfer">>(
        `/transfers/${transfer.transferId}`,
        undefined,
        transferCheck,
      );
      setUnknown(false);
      onUpdate(result);
    });
  }
  const expiry = transfer.expiresAt
    ? Date.parse(transfer.expiresAt)
    : undefined;
  return (
    <>
      <Alert
        className="flow-alert"
        type="warning"
        showIcon
        message="Chờ xác thực — tiền chưa được chuyển"
        description="Chỉ hoàn tất sau khi hệ thống xác nhận OTP và điều kiện giao dịch."
      />
      <ErrorPanel error={action.error} />
      {unknown ? (
        <>
          <Alert
            className="flow-alert"
            type="warning"
            message="Chưa xác định kết quả xác nhận OTP. Hãy kiểm tra trạng thái giao dịch."
          />
          <ActionButton action={action} onClick={() => void reconcile()}>
            Kiểm tra trạng thái
          </ActionButton>
        </>
      ) : (
        <Form
          form={form}
          layout="vertical"
          onFinish={(v) =>
            action.run(async () => {
              try {
                const result = await post<Schema<"Transfer">>(
                  `/transfers/${transfer.transferId}/confirm-otp`,
                  { otp: v.otp },
                  true,
                  undefined,
                  transferCheck,
                );
                onUpdate(result);
                refreshCustomerData();
              } catch (e) {
                if (
                  uncertain(e) ||
                  (e instanceof ApiError && e.status === 409)
                ) {
                  setUnknown(true);
                  try {
                    const result = await get<Schema<"Transfer">>(
                      `/transfers/${transfer.transferId}`,
                      undefined,
                      transferCheck,
                    );
                    setUnknown(false);
                    onUpdate(result);
                  } catch {
                    /* Keep the unknown outcome until a successful read. */
                  }
                }
                throw e;
              } finally {
                form.resetFields(["otp"]);
              }
            })
          }
          scrollToFirstError
        >
          <CodeField />
          {expiry && <Countdown expiresAt={expiry} />}
          <ActionButton action={action} htmlType="submit" block>
            Xác nhận OTP
          </ActionButton>
          <Button
            className="spaced"
            disabled={action.disabled}
            onClick={() => void reconcile()}
          >
            Kiểm tra trạng thái
          </Button>
        </Form>
      )}
    </>
  );
}
function Receipt({ intent }: { intent: TransferIntent }) {
  return (
    <div className="receipt">
      <div className="receipt-amount">
        <p className="muted small">Số tiền chuyển</p>
        <Money value={intent.body.amount} />
      </div>
      <Descriptions
        column={1}
        colon={false}
        items={[
          {
            key: "name",
            label: "Người nhận",
            children: intent.recipient.recipientDisplayName,
          },
          {
            key: "destination",
            label: "Tài khoản nhận",
            children: intent.recipient.accountNumberMasked,
          },
          {
            key: "source",
            label: "Tài khoản nguồn",
            children: intent.sourceMasked,
          },
          { key: "memo", label: "Lời nhắn", children: intent.body.memo || "—" },
        ]}
      />
    </div>
  );
}
export function TransferPage() {
  const accounts = useAccounts();
  const flow = transferFlow();
  const action = useAction();
  const [form] = Form.useForm();
  const [step, setStep] = useState(0);
  const [recipient, setRecipient] =
    useState<Schema<"RecipientConfirmation"> | null>(null);
  const resolverVersion = useRef(0);
  useBeforeUnload((event) => {
    if (flow.stage === "unknown" || action.busy) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  const activeStep = flow.intent ? (flow.stage === "review" ? 2 : 3) : step;
  function updateResult(result: Schema<"Transfer">) {
    flow.update({ result, stage: nextTransferStage(result) });
    if (result.status === "COMPLETED") refreshCustomerData();
  }
  async function reconcile() {
    if (!flow.result) return;
    await action.run(async () => {
      updateResult(
        await get<Schema<"Transfer">>(
          `/transfers/${flow.result!.transferId}`,
          undefined,
          transferCheck,
        ),
      );
    });
  }
  async function send(pin: string) {
    if (!flow.intent) return;
    await action.run(async () => {
      try {
        const result = await post<TransferResult>(
          "/transfers",
          { ...flow.intent!.body, pin },
          true,
          flow.intent!.key,
          transferCheck,
        );
        flow.update({ result, stage: nextTransferStage(result) });
        if (result.status === "COMPLETED") refreshCustomerData();
      } catch (e) {
        if (
          uncertain(e) ||
          (e instanceof ApiError && e.code === "IDEMPOTENCY_KEY_REUSED")
        )
          flow.update({ stage: "unknown" });
        throw e;
      } finally {
        form.resetFields(["pin"]);
      }
    });
  }
  return (
    <>
      <Heading
        title="Chuyển tiền"
        sub={
          flow.stage === "unknown"
            ? "Kiểm tra kết quả trước khi bắt đầu giao dịch khác."
            : "Thông tin và thao tác được trình bày theo từng bước."
        }
      />
      <Steps
        className="transfer-steps"
        size="small"
        responsive={false}
        current={activeStep}
        items={["Người nhận", "Số tiền", "Kiểm tra", "Kết quả"].map(
          (title) => ({ title }),
        )}
      />
      <Panel
        narrow
        title={
          flow.intent
            ? flow.stage === "review"
              ? "Kiểm tra giao dịch"
              : flow.stage === "otp"
                ? "Xác minh giao dịch"
                : "Kết quả giao dịch"
            : step === 0
              ? "Xác minh người nhận"
              : "Số tiền và lời nhắn"
        }
        sub={
          flow.intent && flow.stage === "review"
            ? "Xác nhận đúng người nhận và số tiền"
            : undefined
        }
      >
        <ErrorPanel error={action.error} />
        {!flow.intent ? (
          <>
            {accounts.isPending ? (
              <Loading />
            ) : (
              <Form
                form={form}
                key={step}
                preserve={false}
                layout="vertical"
                scrollToFirstError
                onFinish={(v) =>
                  action.run(async () => {
                    if (step === 0) {
                      const version = resolverVersion.current;
                      const result = await post<
                        Schema<"RecipientConfirmation">
                      >(
                        "/recipients/resolve",
                        { accountNumber: v.accountNumber },
                        true,
                        undefined,
                        (x) =>
                          [
                            "accountId",
                            "accountNumberMasked",
                            "recipientDisplayName",
                            "currency",
                          ].every((k) => typeof x[k] === "string"),
                      );
                      if (version !== resolverVersion.current) return;
                      setRecipient(result);
                      setStep(1);
                      form.resetFields();
                    } else if (recipient) {
                      const source = accounts.data?.items.find(
                        (a) => a.accountId === v.sourceAccountId,
                      );
                      if (!source || source.status !== "ACTIVE") return;
                      const intent: TransferIntent = {
                        key: crypto.randomUUID(),
                        recipient,
                        sourceMasked: source.accountNumberMasked,
                        body: {
                          sourceAccountId: source.accountId,
                          destinationAccountId: recipient.accountId,
                          amount: v.amount,
                          currency: "VND",
                          memo: v.memo || undefined,
                        },
                      };
                      flow.update({ intent, stage: "review", result: null });
                      form.resetFields();
                    }
                  })
                }
              >
                <ErrorPanel
                  error={accounts.error}
                  retry={() => void accounts.refetch()}
                />
                {step === 0 ? (
                  <>
                    <Form.Item
                      name="accountNumber"
                      label="Số tài khoản người nhận"
                      rules={[
                        { required: true },
                        {
                          pattern: /^\d{8,34}$/,
                          message: "Số tài khoản gồm 8–34 chữ số.",
                        },
                      ]}
                    >
                      <Input
                        inputMode="numeric"
                        autoComplete="off"
                        placeholder="Nhập số tài khoản"
                        onChange={() => {
                          resolverVersion.current++;
                          setRecipient(null);
                        }}
                      />
                    </Form.Item>
                    <Note>
                      Tên và số tài khoản che được xác nhận bởi hệ thống.
                    </Note>
                    <ActionButton action={action} htmlType="submit" block>
                      Xác minh người nhận
                    </ActionButton>
                  </>
                ) : (
                  <>
                    <div className="recipient">
                      <strong>{recipient?.recipientDisplayName}</strong>
                      <span>{recipient?.accountNumberMasked}</span>
                      <Status value="Đã xác minh" />
                    </div>
                    <Form.Item
                      name="sourceAccountId"
                      label="Tài khoản nguồn"
                      rules={[
                        {
                          required: true,
                          message: "Chọn tài khoản đang hoạt động.",
                        },
                      ]}
                    >
                      <Select
                        placeholder="Chọn tài khoản nguồn"
                        options={accounts.data?.items
                          .filter((a) => a.status === "ACTIVE")
                          .map((a) => ({
                            value: a.accountId,
                            label: `VND · ${a.accountNumberMasked}`,
                          }))}
                      />
                    </Form.Item>
                    <Form.Item
                      name="amount"
                      label="Số tiền chuyển"
                      extra="Từ 2.000 đến 10.000.000 ₫, nhập số nguyên VND."
                      rules={[
                        { required: true },
                        {
                          pattern: /^(?:[2-9]\d{3}|[1-9]\d{4,6}|10000000)$/,
                          message: "Số tiền hợp lệ từ 2.000 đến 10.000.000 ₫.",
                        },
                      ]}
                    >
                      <Input
                        inputMode="numeric"
                        suffix="VND"
                        placeholder="Nhập số tiền"
                        maxLength={8}
                      />
                    </Form.Item>
                    <Form.Item
                      name="memo"
                      label="Lời nhắn"
                      rules={[{ max: 140 }]}
                    >
                      <Input.TextArea rows={3} maxLength={140} />
                    </Form.Item>
                    <div className="form-actions">
                      <Button
                        disabled={action.disabled}
                        onClick={() => {
                          setStep(0);
                          setRecipient(null);
                          form.resetFields();
                        }}
                      >
                        Quay lại
                      </Button>
                      <ActionButton action={action} htmlType="submit">
                        Tiếp tục
                      </ActionButton>
                    </div>
                  </>
                )}
              </Form>
            )}
          </>
        ) : flow.stage === "review" ? (
          <>
            <Receipt intent={flow.intent} />
            <Form
              form={form}
              layout="vertical"
              onFinish={(v) => void send(v.pin)}
            >
              <CodeField name="pin" label="PIN giao dịch" secret />
              <Note>
                Số dư chỉ cập nhật sau khi hệ thống xác nhận giao dịch hoàn tất.
              </Note>
              <div className="form-actions">
                <Button
                  disabled={action.disabled}
                  onClick={() => {
                    if (!flow.intent) return;
                    setRecipient(flow.intent.recipient);
                    flow.clear();
                    form.resetFields();
                    setStep(1);
                  }}
                >
                  Quay lại
                </Button>
                <ActionButton action={action} htmlType="submit">
                  Xác nhận chuyển
                </ActionButton>
              </div>
            </Form>
          </>
        ) : flow.stage === "unknown" ? (
          <>
            <Alert
              className="flow-alert"
              type="warning"
              showIcon
              message="Chưa xác định kết quả — kiểm tra trạng thái giao dịch"
              description="Yêu cầu có thể đã được hệ thống xử lý. Không tạo giao dịch khác cho đến khi đối soát."
            />
            <Receipt intent={flow.intent} />
            {flow.result ? (
              <ActionButton action={action} onClick={() => void reconcile()}>
                Kiểm tra trạng thái giao dịch
              </ActionButton>
            ) : (
              <Form
                form={form}
                layout="vertical"
                onFinish={(v) => void send(v.pin)}
              >
                <Note>
                  Gửi lại cùng mã yêu cầu và nội dung; hệ thống kiểm tra để
                  tránh chuyển tiền lần hai.
                </Note>
                <CodeField name="pin" label="Nhập lại PIN giao dịch" secret />
                <ActionButton action={action} htmlType="submit" block>
                  Đối soát bằng cùng yêu cầu
                </ActionButton>
              </Form>
            )}
            <Link className="spaced" to="/customer/transfers">
              Tra cứu lịch sử
            </Link>
          </>
        ) : flow.stage === "otp" && flow.result ? (
          <>
            <Receipt intent={flow.intent} />
            <TransferOtp transfer={flow.result} onUpdate={updateResult} />
          </>
        ) : flow.result ? (
          <>
            <Result
              status={
                flow.result.status === "COMPLETED"
                  ? "success"
                  : flow.result.status === "FAILED"
                    ? "error"
                    : "warning"
              }
              title={
                flow.result.status === "COMPLETED"
                  ? "Chuyển tiền thành công"
                  : flow.result.status === "EXPIRED"
                    ? "Giao dịch đã hết hạn"
                    : flow.result.status === "FAILED"
                      ? "Giao dịch thất bại"
                      : "Trạng thái giao dịch cần kiểm tra"
              }
              subTitle={
                flow.result.status === "COMPLETED"
                  ? "Hệ thống đã xác nhận giao dịch hoàn tất."
                  : "Hãy xem thông tin trạng thái trước khi tiếp tục."
              }
            />
            <Status value={flow.result.status} />
            {"failureCode" in flow.result && flow.result.failureCode && (
              <Alert
                className="flow-alert"
                type="error"
                message={`Mã lý do: ${flow.result.failureCode}`}
              />
            )}
            <Receipt intent={flow.intent} />
            <div className="actions">
              <Link to={`/customer/transfers/${flow.result.transferId}`}>
                <Button type="primary">Chi tiết giao dịch</Button>
              </Link>
              {["COMPLETED", "EXPIRED", "FAILED"].includes(
                flow.result.status,
              ) && (
                <Button
                  onClick={() => {
                    flow.clear();
                    setStep(0);
                    setRecipient(null);
                    form.resetFields();
                    action.setError(null);
                  }}
                >
                  Giao dịch mới
                </Button>
              )}
            </div>
          </>
        ) : null}
      </Panel>
      <p className="footnote">
        Sau khi tải lại trang, hãy kiểm tra lịch sử trước khi gửi yêu cầu mới
        nếu kết quả cũ chưa rõ.
      </p>
    </>
  );
}
