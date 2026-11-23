import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Form, Input } from "antd";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ApiError, formErrors } from "../../../shared/api/client";
import {
  loginSchema,
  otpSchema,
  type LoginRequest,
  type AuthResponse,
} from "../../../shared/auth/contracts";
import type {
  createV2Session,
  SessionChallenge,
} from "../../../shared/auth/session";

export type V2Session = ReturnType<typeof createV2Session>;
export function V2SessionSummary({
  auth,
  logout,
  ending,
}: {
  auth: AuthResponse;
  logout: () => Promise<void>;
  ending: boolean;
}) {
  const time = (value: string) =>
    new Intl.DateTimeFormat("vi-VN", {
      dateStyle: "short",
      timeStyle: "short",
      timeZone: "Asia/Ho_Chi_Minh",
    }).format(new Date(value));
  return (
    <>
      <h1 tabIndex={-1}>Phiên hiện tại</h1>
      <p className="auth-sub">
        Chào {auth.user.displayName}. Bạn đã đăng nhập.
      </p>
      <Descriptions
        column={1}
        size="small"
        items={[
          { key: "user", label: "Mã người dùng", children: auth.user.userId },
          {
            key: "customer",
            label: "Mã khách hàng",
            children: auth.user.customerId ?? "—",
          },
          {
            key: "type",
            label: "Loại người dùng",
            children:
              auth.user.userType === "CUSTOMER" ? "Khách hàng" : "Nhân viên",
          },
          {
            key: "roles",
            label: "Vai trò",
            children: auth.user.roles.join(", ") || "—",
          },
          {
            key: "privileges",
            label: "Đặc quyền",
            children: auth.user.privileges.join(", ") || "—",
          },
          {
            key: "device",
            label: "Thiết bị tin cậy",
            children: auth.session.deviceTrusted ? "Có" : "Không",
          },
          {
            key: "idle",
            label: "Thời hạn không hoạt động",
            children: (
              <time dateTime={auth.session.idleExpiresAt}>
                {time(auth.session.idleExpiresAt)}
              </time>
            ),
          },
          {
            key: "absolute",
            label: "Thời hạn tối đa",
            children: (
              <time dateTime={auth.session.absoluteExpiresAt}>
                {time(auth.session.absoluteExpiresAt)}
              </time>
            ),
          },
        ]}
      />
      <p className="small muted spaced">
        Khu vực nghiệp vụ sẽ được nối ở bước tiếp theo.
      </p>
      <Button
        type="primary"
        block
        loading={ending}
        disabled={ending}
        onClick={() => void logout()}
      >
        Đăng xuất
      </Button>
    </>
  );
}
const loginFormSchema = loginSchema.extend({
  username: z
    .string()
    .min(1, "Vui lòng nhập tên đăng nhập.")
    .max(64, "Tên đăng nhập tối đa 64 ký tự."),
  password: z
    .string()
    .min(1, "Vui lòng nhập mật khẩu.")
    .max(128, "Mật khẩu tối đa 128 ký tự."),
});
export function useClock() {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
const descriptions: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: "Tên đăng nhập hoặc mật khẩu không hợp lệ.",
  AUTH_ACCOUNT_LOCKED:
    "Tài khoản đang bị khóa. Vui lòng kiểm tra lại sau hoặc liên hệ hỗ trợ.",
  AUTH_OTP_INVALID: "Mã OTP không đúng. Vui lòng kiểm tra và nhập lại.",
  AUTH_OTP_EXPIRED: "Mã OTP đã hết hạn. Vui lòng bắt đầu đăng nhập lại.",
  AUTH_CHALLENGE_EXPIRED:
    "Yêu cầu xác thực đã hết hạn. Vui lòng đăng nhập lại.",
  AUTH_CHALLENGE_LOCKED: "Yêu cầu xác thực đã bị khóa. Vui lòng bắt đầu lại.",
  AUTH_CHALLENGE_NOT_FOUND:
    "Không tìm thấy yêu cầu xác thực. Vui lòng đăng nhập lại.",
  INVALID_STATE_TRANSITION:
    "Yêu cầu xác thực không còn dùng được. Vui lòng bắt đầu lại.",
  RATE_LIMITED: "Bạn đã gửi quá nhiều yêu cầu. Vui lòng chờ trước khi thử lại.",
};
export function AuthError({ error }: { error: unknown }) {
  if (!error || (error instanceof DOMException && error.name === "AbortError"))
    return null;
  const text =
    error instanceof ApiError
      ? Object.hasOwn(descriptions, error.code)
        ? descriptions[error.code]
        : error.message
      : "Không thể hoàn tất thao tác. Vui lòng thử lại.";
  return (
    <div role="alert">
      <Alert type="error" showIcon message={text} />
      {error instanceof ApiError && error.meta.correlationId && (
        <details className="small">
          <summary>Mã hỗ trợ</summary>
          <span>{error.meta.correlationId}</span>
        </details>
      )}
    </div>
  );
}
function waitUntil(error: unknown) {
  if (
    !(error instanceof ApiError) ||
    error.meta.status !== 429 ||
    !error.meta.retryAfter
  )
    return 0;
  const value = error.meta.retryAfter;
  if (/^[0-9]+$/.test(value)) return Date.now() + Number(value) * 1000;
  const date = Date.parse(value);
  return Number.isFinite(date) ? date : 0;
}

export function V2LoginForm({ session }: { session: V2Session }) {
  const {
    control,
    handleSubmit,
    resetField,
    setError,
    formState: { errors },
  } = useForm<LoginRequest>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: { username: "", password: "" },
  });
  const [busy, setBusy] = useState(false),
    [error, setFailure] = useState<unknown>(null);
  const [retryAt, setRetryAt] = useState(0);
  const now = useClock();
  const locked = useRef(false);
  const submit = handleSubmit(async (values) => {
    if (locked.current || Date.now() < retryAt) return;
    locked.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await session.login(values);
    } catch (failure) {
      setFailure(failure);
      setRetryAt(waitUntil(failure));
      if (failure instanceof ApiError)
        for (const e of formErrors(failure, ["username", "password"]))
          setError(e.field as "username" | "password", { message: e.message });
    } finally {
      resetField("password", { keepError: true });
      locked.current = false;
      setBusy(false);
    }
  });
  return (
    <>
      <p className="login-welcome">Chào mừng bạn đến với</p>
      <h1 tabIndex={-1}>Digital Banking</h1>
      <AuthError error={error} />
      <form onSubmit={submit} noValidate aria-busy={busy}>
        <Form.Item
          label="Tên đăng nhập"
          colon={false}
          htmlFor="v2-username"
          required
          validateStatus={errors.username ? "error" : undefined}
          help={
            errors.username && (
              <span id="v2-username-error">{errors.username.message}</span>
            )
          }
        >
          <Controller
            name="username"
            control={control}
            render={({ field }) => (
              <Input
                {...field}
                id="v2-username"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={64}
                placeholder="Nhập tên đăng nhập"
                disabled={busy}
                aria-required="true"
                aria-invalid={!!errors.username}
                aria-describedby={
                  errors.username ? "v2-username-error" : undefined
                }
              />
            )}
          />
        </Form.Item>
        <Form.Item
          label="Mật khẩu"
          colon={false}
          htmlFor="v2-password"
          required
          validateStatus={errors.password ? "error" : undefined}
          help={
            errors.password && (
              <span id="v2-password-error">{errors.password.message}</span>
            )
          }
        >
          <Controller
            name="password"
            control={control}
            render={({ field }) => (
              <Input.Password
                {...field}
                id="v2-password"
                autoComplete="current-password"
                maxLength={128}
                placeholder="Nhập mật khẩu"
                disabled={busy}
                aria-required="true"
                aria-invalid={!!errors.password}
                aria-describedby={
                  errors.password ? "v2-password-error" : undefined
                }
              />
            )}
          />
        </Form.Item>
        <Button
          type="primary"
          htmlType="submit"
          block
          loading={busy}
          disabled={busy || now < retryAt}
        >
          {now < retryAt
            ? `Thử lại sau ${Math.ceil((retryAt - now) / 1000)} giây`
            : "Đăng nhập"}
        </Button>
      </form>
      <p className="auth-bottom muted">
        Đăng ký và khôi phục tài khoản đang chờ hoàn thiện hợp đồng.
      </p>
    </>
  );
}

const otpFormSchema = z.object({
  otp: z
    .string()
    .regex(/^[0-9]{6,8}$/, "Mã OTP gồm 6–8 chữ số.")
    .refine(
      (value) => otpSchema.shape.otp.safeParse(value).success,
      "Mã OTP gồm 6–8 chữ số.",
    ),
});
const terminalCodes = new Set([
  "AUTH_OTP_EXPIRED",
  "AUTH_CHALLENGE_EXPIRED",
  "AUTH_CHALLENGE_LOCKED",
  "AUTH_CHALLENGE_NOT_FOUND",
  "INVALID_STATE_TRANSITION",
]);
export function V2ChallengeForm({
  session,
  response,
}: {
  session: V2Session;
  response: SessionChallenge;
}) {
  const challenge = response.challenge;
  const {
    control,
    handleSubmit,
    resetField,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<z.infer<typeof otpFormSchema>>({
    resolver: zodResolver(otpFormSchema),
    defaultValues: { otp: "" },
  });
  const [error, setFailure] = useState<unknown>(null),
    [busy, setBusy] = useState(false);
  const [terminal, setTerminal] = useState(false),
    [attemptsKnown, setAttemptsKnown] = useState(true);
  const [retryAt, setRetryAt] = useState(0);
  const locked = useRef(false);
  const now = useClock();
  useEffect(() => {
    resetField("otp");
    setAttemptsKnown(true);
    setTerminal(false);
  }, [challenge.challengeId, challenge.expiresAt, resetField]);
  const expired = Date.parse(challenge.expiresAt) <= now;
  const otpSupported =
    challenge.methods.length === 1 && challenge.methods[0] === "OTP";
  const disabled =
    busy || terminal || expired || now < retryAt || !otpSupported;
  const run = async (action: () => Promise<unknown>) => {
    if (locked.current || disabled) return;
    locked.current = true;
    setBusy(true);
    setFailure(null);
    clearErrors("otp");
    try {
      await action();
    } catch (failure) {
      setFailure(failure);
      setRetryAt(waitUntil(failure));
      if (failure instanceof ApiError) {
        if (failure.code === "AUTH_OTP_INVALID") {
          setAttemptsKnown(false);
          setError("otp", { message: descriptions.AUTH_OTP_INVALID });
        }
        if (terminalCodes.has(failure.code)) {
          setTerminal(true);
          setAttemptsKnown(false);
        }
      }
    } finally {
      resetField("otp", { keepError: true });
      locked.current = false;
      setBusy(false);
    }
  };
  const seconds = Math.max(
    0,
    Math.ceil((Date.parse(challenge.expiresAt) - now) / 1000),
  );
  return (
    <>
      <h1 tabIndex={-1}>Xác minh đăng nhập</h1>
      <p className="auth-sub">
        Nhập mã OTP gửi tới{" "}
        {challenge.maskedDestination ?? "kênh xác thực đã đăng ký"}.
      </p>
      <AuthError error={error} />
      {!otpSupported && (
        <Alert
          type="warning"
          message="Phương thức xác thực này chưa được hỗ trợ trong bản xem hiện tại."
        />
      )}
      <p>
        Hết hạn lúc{" "}
        <time dateTime={challenge.expiresAt}>
          {new Intl.DateTimeFormat("vi-VN", {
            timeStyle: "medium",
            timeZone: "Asia/Ho_Chi_Minh",
          }).format(new Date(challenge.expiresAt))}
        </time>{" "}
        <span aria-hidden="true">
          ({Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")})
        </span>
      </p>
      {attemptsKnown && !terminal && !expired && (
        <p className="small muted">
          Số lần thử còn lại theo phản hồi gần nhất:{" "}
          {challenge.remainingAttempts}
        </p>
      )}
      <div role="status" aria-live="polite">
        {expired
          ? "Mã OTP đã hết hạn. Vui lòng đăng nhập lại."
          : terminal
            ? "Yêu cầu xác thực không còn dùng được."
            : "Mã OTP chỉ sử dụng cho lần đăng nhập này."}
      </div>
      <form
        onSubmit={handleSubmit(({ otp }) =>
          run(() =>
            session.verifyOtp({ challengeId: challenge.challengeId, otp }),
          ),
        )}
        noValidate
        aria-busy={busy}
      >
        <Form.Item
          label="Mã OTP"
          htmlFor="v2-otp"
          required
          validateStatus={errors.otp ? "error" : undefined}
          help={
            errors.otp && <span id="v2-otp-error">{errors.otp.message}</span>
          }
        >
          <Controller
            name="otp"
            control={control}
            render={({ field }) => (
              <Input
                {...field}
                id="v2-otp"
                autoComplete="one-time-code"
                inputMode="numeric"
                maxLength={8}
                placeholder="Nhập mã OTP"
                disabled={disabled}
                aria-required="true"
                aria-invalid={!!errors.otp}
                aria-describedby={errors.otp ? "v2-otp-error" : undefined}
              />
            )}
          />
        </Form.Item>
        <Button
          type="primary"
          htmlType="submit"
          block
          loading={busy}
          disabled={disabled}
        >
          Xác minh OTP
        </Button>
      </form>
      <Button
        className="spaced"
        block
        disabled={disabled}
        onClick={() => void run(() => session.resendChallenge())}
      >
        {now < retryAt
          ? `Thử lại sau ${Math.ceil((retryAt - now) / 1000)} giây`
          : "Gửi lại mã OTP"}
      </Button>
      <Button
        type="link"
        disabled={busy}
        onClick={() => session.restartLogin()}
      >
        Đăng nhập lại
      </Button>
    </>
  );
}
