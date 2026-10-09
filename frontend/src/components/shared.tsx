import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Skeleton,
  Tag,
  type FormInstance,
} from "antd";
import {
  CheckOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LockOutlined,
  SafetyOutlined,
} from "@ant-design/icons";
import { ApiError } from "../api/client";

export const phoneRules = [
  { required: true, message: "Vui lòng nhập số điện thoại." },
  {
    pattern: /^0[3-9]\d{8}$/,
    message: "Số điện thoại Việt Nam gồm 10 chữ số.",
  },
];
export const codeRules = [
  { required: true, message: "Vui lòng nhập mã." },
  { pattern: /^\d{6}$/, message: "Mã phải gồm đúng 6 chữ số." },
];
export const passwordRules = [
  { required: true, message: "Vui lòng nhập mật khẩu." },
  { min: 12, max: 128, message: "Mật khẩu cần từ 12 đến 128 ký tự." },
];
export const emailRules = [
  { required: true, message: "Vui lòng nhập email." },
  { type: "email" as const, max: 254, message: "Email không hợp lệ." },
];
export function matchRule(field: string, label: string) {
  return ({ getFieldValue }: { getFieldValue: (name: string) => unknown }) => ({
    validator(_: unknown, value: string) {
      return !value || value === getFieldValue(field)
        ? Promise.resolve()
        : Promise.reject(new Error(`${label} chưa trùng khớp.`));
    },
  });
}
export function CodeField({
  name = "otp",
  label = "Mã OTP",
  secret = false,
}: {
  name?: string;
  label?: string;
  secret?: boolean;
}) {
  const props = {
    inputMode: "numeric" as const,
    maxLength: 6,
    autoComplete: secret ? "off" : "one-time-code",
    className: "code-input",
    placeholder: "••••••",
  };
  return (
    <Form.Item
      name={name}
      label={label}
      rules={codeRules}
      extra="Có thể dán mã và giữ số 0 đầu. Bấm xác nhận để gửi."
    >
      {secret ? (
        <Input.Password {...props} visibilityToggle={false} />
      ) : (
        <Input {...props} />
      )}
    </Form.Item>
  );
}
export function Simulation() {
  return (
    <span className="simulation">
      <SafetyOutlined /> Mô phỏng — không sử dụng tiền thật
    </span>
  );
}
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="brand">
      <span className="brand-mark">DB</span>
      <span>
        <strong>Digital Banking</strong>
        <small>
          {compact ? "SIMULATOR" : "SIMULATOR · NGÂN HÀNG MÔ PHỎNG"}
        </small>
      </span>
    </div>
  );
}
export function Heading({
  title,
  sub,
  action,
}: {
  title: string;
  sub?: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <h1 tabIndex={-1}>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {action}
    </div>
  );
}
export function Panel({
  title,
  sub,
  children,
  narrow = false,
}: {
  title?: string;
  sub?: string;
  children: ReactNode;
  narrow?: boolean;
}) {
  return (
    <Card
      className={narrow ? "panel form-panel" : "panel"}
      title={
        title ? (
          <div>
            <h2>{title}</h2>
            {sub && <p className="muted small">{sub}</p>}
          </div>
        ) : undefined
      }
    >
      {children}
    </Card>
  );
}
export function money(value: string) {
  if (!/^\d+$/.test(value)) return "—";
  return `${BigInt(value).toLocaleString("vi-VN")} ₫`;
}
export function Money({
  value,
  className = "",
}: {
  value: string;
  className?: string;
}) {
  return <span className={`money ${className}`}>{money(value)}</span>;
}
export function date(value?: string | null) {
  if (!value || Number.isNaN(Date.parse(value))) return "—";
  return new Intl.DateTimeFormat("vi-VN", {
    timeZone: "Asia/Ho_Chi_Minh",
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}
export function Status({ value }: { value: string }) {
  const color = ["ACTIVE", "COMPLETED", "SUCCESS"].includes(value)
    ? "success"
    : ["AWAITING_OTP", "EXPIRED"].includes(value)
      ? "warning"
      : ["BLOCKED", "CLOSED", "FAILED", "FAILURE"].includes(value)
        ? "error"
        : "default";
  const icon =
    color === "success" ? (
      <CheckOutlined />
    ) : color === "warning" ? (
      <ClockCircleOutlined />
    ) : color === "error" ? (
      <CloseCircleOutlined />
    ) : undefined;
  return (
    <Tag color={color} icon={icon}>
      {value}
    </Tag>
  );
}
const errors: Record<string, string> = {
  NETWORK_ERROR: "Không thể kết nối. Kiểm tra kết nối và thử lại.",
  INVALID_RESPONSE:
    "Phản hồi hệ thống không hợp lệ. Vui lòng thử lại hoặc liên hệ hỗ trợ.",
  INVALID_CREDENTIALS: "Số điện thoại hoặc mật khẩu không đúng.",
  OTP_INVALID:
    "OTP không đúng, đã hết hạn hoặc đã được sử dụng. Kiểm tra mã đã nhận.",
  RECOVERY_TOKEN_INVALID:
    "Phiên khôi phục đã hết hạn hoặc không còn hợp lệ. Hãy yêu cầu OTP mới.",
  REGISTRATION_TOKEN_INVALID:
    "Quyền đăng ký đã hết hạn hoặc không còn hợp lệ. Hãy xác minh OTP lại.",
  CREDENTIALS_INVALID: "Số điện thoại hoặc mật khẩu không đúng.",
  PIN_INVALID: "PIN giao dịch chưa đúng.",
  PIN_LOCKED: "PIN đang bị khóa tạm thời. Vui lòng thử lại sau.",
  PIN_ALREADY_SET: "PIN đã được thiết lập. Hãy dùng chức năng đổi PIN.",
  INSUFFICIENT_FUNDS: "Số dư không đủ để chuyển tiền.",
  ACCOUNT_NOT_ELIGIBLE: "Tài khoản không còn đủ điều kiện giao dịch.",
  TRANSFER_EXPIRED: "Giao dịch đã hết hạn xác thực.",
  STATE_CONFLICT:
    "Trạng thái đã thay đổi. Kiểm tra kết quả từ hệ thống trước khi tiếp tục.",
  IDEMPOTENCY_KEY_REUSED:
    "Mã yêu cầu đã gắn với nội dung khác. Dừng thao tác và kiểm tra giao dịch.",
  PHONE_ALREADY_REGISTERED: "Số điện thoại đã được đăng ký.",
  EMAIL_ALREADY_REGISTERED: "Email đã được đăng ký.",
  CUSTOMER_NOT_FOUND: "Không tìm thấy khách hàng theo thông tin tra cứu.",
  RECIPIENT_NOT_FOUND: "Không tìm thấy người nhận phù hợp.",
  ACCOUNT_NOT_FOUND: "Không tìm thấy tài khoản.",
  TRANSFER_NOT_FOUND: "Không tìm thấy giao dịch.",
  OTP_DISPATCH_FAILED:
    "Không thể gửi OTP. Kiểm tra trạng thái trước khi tiếp tục.",
  VALIDATION_ERROR: "Thông tin chưa hợp lệ. Kiểm tra các trường và thử lại.",
  RATE_LIMITED: "Bạn thao tác quá nhanh. Vui lòng chờ trước khi thử lại.",
};
export function errorText(error: unknown) {
  if (!(error instanceof ApiError))
    return "Không thể hoàn tất thao tác. Vui lòng thử lại.";
  return (
    errors[error.code] ||
    (error.status === 401
      ? "Phiên đã hết hạn. Vui lòng đăng nhập lại."
      : error.status === 403
        ? "Bạn không có quyền thực hiện thao tác này."
        : error.status === 404
          ? "Không tìm thấy nội dung."
          : error.status === 409
            ? "Dữ liệu đã thay đổi. Kiểm tra trạng thái trước khi tiếp tục."
            : error.status === 429
              ? errors.RATE_LIMITED
              : "Hệ thống chưa xử lý được yêu cầu. Vui lòng thử lại.")
  );
}
export function ErrorPanel({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <Alert
      className="flow-alert"
      type="error"
      showIcon
      role="alert"
      message={errorText(error)}
      description={
        error instanceof ApiError && error.correlationId
          ? `Mã đối chiếu: ${error.correlationId}`
          : undefined
      }
      action={retry ? <Button onClick={retry}>Thử lại</Button> : undefined}
    />
  );
}
export function Loading() {
  return (
    <div role="status" aria-label="Đang tải dữ liệu">
      <Skeleton active paragraph={{ rows: 4 }} />
    </div>
  );
}
export function EmptyState({ text = "Chưa có dữ liệu" }: { text?: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
}
export function Note({ children }: { children: ReactNode }) {
  return (
    <p className="security-note">
      <LockOutlined /> {children}
    </p>
  );
}
export function useNow() {
  const [now, set] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => set(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}
export function useFormErrors(
  error: unknown,
  form: FormInstance,
  known: string[],
) {
  const names = known.join(",");
  useEffect(() => {
    if (!(error instanceof ApiError)) return;
    const fields = error.fields.filter((name) =>
      names.split(",").includes(name),
    );
    const businessField =
      error.code === "OTP_INVALID"
        ? "otp"
        : error.code === "PHONE_ALREADY_REGISTERED"
          ? "phone"
          : error.code === "EMAIL_ALREADY_REGISTERED"
            ? "email"
            : error.code === "PIN_INVALID"
              ? "pin"
              : null;
    if (businessField && names.split(",").includes(businessField))
      fields.push(businessField);
    if (fields.length) {
      form.setFields(
        fields.map((name) => ({
          name,
          errors: [
            error.code === "VALIDATION_ERROR"
              ? "Thông tin chưa hợp lệ theo yêu cầu hệ thống."
              : errorText(error),
          ],
        })),
      );
      form.scrollToField(fields[0], { focus: true });
    }
  }, [error, form, names]);
}
export function Countdown({ expiresAt }: { expiresAt: number }) {
  const remaining = Math.max(0, Math.ceil((expiresAt - useNow()) / 1000));
  return (
    <p className="countdown">
      Thời hạn mã{" "}
      <strong>
        {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}
      </strong>
      {!remaining && " · Mã đã hết hạn."}
    </p>
  );
}
// Secrets stay in the form/request closure, never in a mutation cache.
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const lock = useRef(false);
  const mounted = useRef(true);
  const now = useNow();
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const cooldown =
    error instanceof ApiError
      ? Math.max(0, Math.ceil((error.retryAt - now) / 1000))
      : 0;
  async function run(work: () => Promise<unknown>) {
    if (lock.current || cooldown) return false;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await work();
      return true;
    } catch (e) {
      if (
        mounted.current &&
        !(e instanceof DOMException && e.name === "AbortError")
      )
        setError(e);
      return false;
    } finally {
      lock.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  return {
    busy,
    error,
    setError,
    run,
    cooldown,
    disabled: busy || cooldown > 0,
  };
}
export function ActionButton({
  action,
  children,
  ...props
}: {
  action: ReturnType<typeof useAction>;
  children: ReactNode;
  block?: boolean;
  htmlType?: "button" | "submit";
  onClick?: () => void;
}) {
  return (
    <Button
      type="primary"
      loading={action.busy}
      disabled={action.disabled}
      {...props}
    >
      {action.cooldown ? `Thử lại sau ${action.cooldown}s` : children}
    </Button>
  );
}
