import { useRef, useState } from "react";
import { Alert, Button, Form, Input, Segmented, Result } from "antd";
import {
  ArrowRightOutlined,
  CheckOutlined,
  SafetyOutlined,
} from "@ant-design/icons";
import { Link, useNavigate } from "react-router-dom";
import {
  ApiError,
  login,
  post,
  proofCheck,
  uncertain,
  type Schema,
} from "../../api/client";
import { session, landing, workspaces } from "../../stores/session";
import {
  ActionButton,
  CodeField,
  Countdown,
  ErrorPanel,
  Note,
  emailRules,
  matchRule,
  passwordRules,
  phoneRules,
  useAction,
  useNow,
  useFormErrors,
} from "../../components/shared";

export function LoginPage() {
  const action = useAction();
  const navigate = useNavigate();
  const [form] = Form.useForm();
  useFormErrors(action.error, form, ["phone", "password"]);
  return (
    <>
      <h1>Chào mừng trở lại</h1>
      <p className="auth-sub">Đăng nhập để tiếp tục thực hành ngân hàng số.</p>
      <ErrorPanel error={action.error} />
      <Form
        form={form}
        layout="vertical"
        onFinish={(v: Schema<"LoginRequest">) =>
          action.run(async () => {
            try {
              const user = await login(v);
              navigate(landing(user), { replace: true });
            } finally {
              form.resetFields(["password"]);
            }
          })
        }
        scrollToFirstError
      >
        <Form.Item label="Số điện thoại" name="phone" rules={phoneRules}>
          <Input
            type="tel"
            autoComplete="tel"
            placeholder="Nhập số điện thoại"
          />
        </Form.Item>
        <Form.Item
          label="Mật khẩu"
          name="password"
          rules={[
            { required: true, message: "Vui lòng nhập mật khẩu." },
            { max: 128 },
          ]}
        >
          <Input.Password
            autoComplete="current-password"
            placeholder="Nhập mật khẩu"
          />
        </Form.Item>
        <div className="auth-row">
          <span className="muted small">Đăng nhập bằng điện thoại</span>
          <Link to="/recover">Quên mật khẩu?</Link>
        </div>
        <ActionButton action={action} htmlType="submit" block>
          Đăng nhập <ArrowRightOutlined />
        </ActionButton>
      </Form>
      <div className="auth-bottom">
        Bạn chưa có tài khoản? <Link to="/register">Đăng ký</Link>
      </div>
    </>
  );
}
const genericRecovery =
  "Nếu thông tin đã đăng ký, mã OTP sẽ được gửi tới kênh bạn chọn";
export function RecoveryPage() {
  const [stage, setStage] = useState<
    "request" | "otp" | "password" | "expired" | "success"
  >("request");
  const [channel, setChannel] = useState<"SMS" | "EMAIL">("SMS");
  const identity = useRef("");
  const proof = useRef<string | null>(null);
  const [expiresAt, setExpires] = useState(0);
  const action = useAction();
  const [form] = Form.useForm();
  const now = useNow();
  useFormErrors(action.error, form, [
    "identifier",
    "otp",
    "newPassword",
    "confirmPassword",
  ]);
  function restart() {
    proof.current = null;
    identity.current = "";
    setStage("request");
    action.setError(null);
    form.resetFields();
  }
  const proofExpired = stage === "password" && now >= expiresAt;
  async function submit(v: Record<string, string>) {
    await action.run(async () => {
      if (stage === "request") {
        proof.current = null;
        const result = await post<Schema<"RecoverInitiateResponse">>(
          "/auth/recover/initiate",
          { identifier: v.identifier, channel },
          false,
          undefined,
          proofCheck("identifier"),
        );
        identity.current = v.identifier;
        setExpires(Date.now() + result.expiresInSeconds * 1000);
        setStage("otp");
        form.resetFields();
      } else if (stage === "otp") {
        try {
          const result = await post<Schema<"RecoverVerifyResponse">>(
            "/auth/recover/verify",
            { identifier: identity.current, channel, otp: v.otp },
            false,
            undefined,
            proofCheck("resetToken"),
          );
          proof.current = result.resetToken;
          setExpires(Date.now() + result.expiresInSeconds * 1000);
          setStage("password");
        } finally {
          form.resetFields(["otp"]);
        }
      } else if (stage === "password") {
        if (!proof.current || proofExpired) {
          proof.current = null;
          setStage("expired");
          return;
        }
        try {
          await post<Schema<"MessageResponse">>(
            "/auth/recover/confirm",
            { resetToken: proof.current, newPassword: v.newPassword },
            false,
          );
          proof.current = null;
          session.getState().clear();
          setStage("success");
        } catch (e) {
          if (e instanceof ApiError && e.code === "RECOVERY_TOKEN_INVALID") {
            proof.current = null;
            setStage("expired");
          }
          throw e;
        } finally {
          form.resetFields(["newPassword", "confirmPassword"]);
        }
      }
    });
  }
  if (stage === "success")
    return (
      <>
        <Result
          status="success"
          title="Đã cập nhật mật khẩu"
          subTitle="Bạn có thể đăng nhập bằng mật khẩu mới."
          extra={
            <Link to="/login">
              <Button type="primary">Đến đăng nhập</Button>
            </Link>
          }
        />
      </>
    );
  if (stage === "expired" || proofExpired)
    return (
      <>
        <h1>Xác minh đã hết hiệu lực</h1>
        <p className="auth-sub">
          Vui lòng xác minh lại để tiếp tục đặt mật khẩu.
        </p>
        <Alert
          type="warning"
          showIcon
          message="Phiên khôi phục đã hết hạn hoặc không còn hợp lệ. Hãy yêu cầu OTP mới."
        />
        <Button className="spaced" type="primary" block onClick={restart}>
          Xác minh lại
        </Button>
        <div className="auth-bottom">
          <Link to="/login">Về đăng nhập</Link>
        </div>
      </>
    );
  return (
    <>
      <h1>
        {stage === "request"
          ? "Khôi phục mật khẩu"
          : stage === "otp"
            ? "Xác minh mã OTP"
            : "Đặt mật khẩu mới"}
      </h1>
      <p className="auth-sub">
        {stage === "request"
          ? "Bước 1/3 · Chọn kênh nhận mã OTP xác minh."
          : stage === "otp"
            ? "Bước 2/3 · Nhập mã OTP gồm 6 chữ số"
            : "Bước 3/3 · Tạo mật khẩu đăng nhập mới"}
      </p>
      <ErrorPanel error={action.error} />
      <Form
        form={form}
        layout="vertical"
        key={stage}
        onFinish={submit}
        scrollToFirstError
        preserve={false}
      >
        {stage === "request" ? (
          <>
            <Segmented
              block
              aria-label="Kênh nhận OTP"
              options={["SMS", "EMAIL"]}
              value={channel}
              onChange={(value) => {
                setChannel(value as "SMS" | "EMAIL");
                form.resetFields();
                proof.current = null;
              }}
            />
            <Form.Item
              className="spaced"
              name="identifier"
              label={channel === "SMS" ? "Số điện thoại" : "Email"}
              rules={channel === "SMS" ? phoneRules : emailRules}
            >
              <Input
                type={channel === "SMS" ? "tel" : "email"}
                placeholder={
                  channel === "SMS" ? "Nhập số điện thoại" : "Nhập email"
                }
              />
            </Form.Item>
          </>
        ) : stage === "otp" ? (
          <>
            <Alert
              className="flow-alert"
              type="info"
              showIcon
              message={genericRecovery}
            />
            <CodeField />
            <Countdown expiresAt={expiresAt} />
            <Note>Sau khi xác minh OTP, bạn sẽ đến bước đặt mật khẩu mới.</Note>
          </>
        ) : (
          <>
            <Alert
              className="flow-alert"
              type="success"
              showIcon
              message="Đã xác minh OTP. Bạn có thể đặt mật khẩu mới."
            />
            <Form.Item
              name="newPassword"
              label="Mật khẩu mới"
              rules={passwordRules}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder="Nhập mật khẩu mới"
              />
            </Form.Item>
            <Form.Item
              name="confirmPassword"
              label="Xác nhận mật khẩu mới"
              dependencies={["newPassword"]}
              rules={[
                ...passwordRules,
                matchRule("newPassword", "Mật khẩu xác nhận"),
              ]}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder="Nhập lại mật khẩu mới"
              />
            </Form.Item>
            <Note>
              Dùng mật khẩu mới để đăng nhập sau khi cập nhật thành công.
            </Note>
          </>
        )}
        <ActionButton action={action} htmlType="submit" block>
          {stage === "request"
            ? "Yêu cầu mã OTP"
            : stage === "otp"
              ? "Xác minh OTP"
              : "Cập nhật mật khẩu"}{" "}
          {stage === "password" ? <CheckOutlined /> : <SafetyOutlined />}
        </ActionButton>
      </Form>
      <div className="auth-bottom">
        {stage === "otp" ? (
          <Button type="link" disabled={action.disabled} onClick={restart}>
            Đổi thông tin / yêu cầu mã mới
          </Button>
        ) : (
          <Link to="/login">
            {stage === "request" ? "Về đăng nhập" : "Hủy và về đăng nhập"}
          </Link>
        )}
      </div>
    </>
  );
}
export function RegisterPage() {
  const [stage, setStage] = useState(0);
  const phone = useRef("");
  const proof = useRef<string | null>(null);
  const [expiresAt, setExpires] = useState(0);
  const [created, setCreated] = useState<Schema<"RegistrationResponse"> | null>(
    null,
  );
  const [form] = Form.useForm();
  const action = useAction();
  const now = useNow();
  useFormErrors(action.error, form, [
    "phone",
    "otp",
    "fullName",
    "email",
    "password",
  ]);
  function restart() {
    proof.current = null;
    setStage(0);
    form.resetFields();
    action.setError(null);
  }
  if (created)
    return (
      <Result
        status="success"
        title="Tạo tài khoản thành công"
        subTitle={`Tài khoản VND · ${created.account.accountNumberMasked}. Vui lòng đăng nhập để tiếp tục.`}
        extra={
          <Link to="/login">
            <Button type="primary">Đến đăng nhập</Button>
          </Link>
        }
      />
    );
  if (stage === 2 && now >= expiresAt)
    return (
      <>
        <h1>Quyền đăng ký đã hết hạn</h1>
        <p className="auth-sub">Hãy yêu cầu OTP và xác minh lại.</p>
        <Button type="primary" onClick={restart}>
          Xác minh lại
        </Button>
      </>
    );
  return (
    <>
      <h1>
        {
          [
            "Tạo tài khoản mô phỏng",
            "Xác minh số điện thoại",
            "Thông tin tài khoản",
          ][stage]
        }
      </h1>
      <p className="auth-sub">
        Bước {stage + 1}/3 ·{" "}
        {
          [
            "Nhập số điện thoại để nhận OTP",
            "Nhập mã OTP gồm 6 chữ số",
            "Hoàn tất thông tin đăng nhập",
          ][stage]
        }
      </p>
      <ErrorPanel error={action.error} />
      {uncertain(action.error) && action.error && stage === 2 && (
        <Alert
          className="flow-alert"
          type="warning"
          showIcon
          message="Chưa xác định kết quả tạo tài khoản. Hãy thử đăng nhập trước khi đăng ký lại."
        />
      )}
      <Form
        key={stage}
        form={form}
        preserve={false}
        layout="vertical"
        scrollToFirstError
        onFinish={(v) =>
          action.run(async () => {
            if (stage === 0) {
              proof.current = null;
              const result = await post<Schema<"SendOtpResponse">>(
                "/auth/register/send-otp",
                { phone: v.phone, purpose: "REGISTRATION" },
                false,
                undefined,
                proofCheck("phone"),
              );
              phone.current = v.phone;
              setExpires(Date.now() + result.expiresInSeconds * 1000);
              setStage(1);
              form.resetFields();
            } else if (stage === 1) {
              try {
                const result = await post<Schema<"RegisterVerifyResponse">>(
                  "/auth/register/verify-otp",
                  { phone: phone.current, otp: v.otp },
                  false,
                  undefined,
                  proofCheck("registrationToken"),
                );
                proof.current = result.registrationToken;
                setExpires(Date.now() + result.expiresInSeconds * 1000);
                setStage(2);
              } finally {
                form.resetFields(["otp"]);
              }
            } else {
              try {
                const result = await post<Schema<"RegistrationResponse">>(
                  "/auth/register",
                  {
                    phone: phone.current,
                    fullName: v.fullName,
                    email: v.email,
                    password: v.password,
                    registrationToken: proof.current,
                  },
                  false,
                  undefined,
                  (x) =>
                    typeof x.customerId === "string" &&
                    !!x.account &&
                    typeof x.account === "object" &&
                    "accountNumberMasked" in x.account,
                );
                proof.current = null;
                setCreated(result);
              } catch (e) {
                if (
                  e instanceof ApiError &&
                  e.code === "REGISTRATION_TOKEN_INVALID"
                ) {
                  proof.current = null;
                  setStage(0);
                }
                throw e;
              } finally {
                form.resetFields(["password"]);
              }
            }
          })
        }
      >
        {stage === 0 ? (
          <>
            <Form.Item name="phone" label="Số điện thoại" rules={phoneRules}>
              <Input
                type="tel"
                autoComplete="tel"
                placeholder="Nhập số điện thoại"
              />
            </Form.Item>
            <Note>OTP sẽ được gửi đến số điện thoại bạn đăng ký.</Note>
          </>
        ) : stage === 1 ? (
          <>
            <Alert
              className="flow-alert"
              type="info"
              showIcon
              message="Mã OTP đã được gửi đến số điện thoại đăng ký."
            />
            <CodeField />
            <Countdown expiresAt={expiresAt} />
          </>
        ) : (
          <>
            <Alert
              className="flow-alert"
              type="success"
              message="Số điện thoại đã được xác minh."
              showIcon
            />
            <Form.Item
              name="fullName"
              label="Họ và tên"
              rules={[{ required: true, whitespace: true, max: 120 }]}
            >
              <Input placeholder="Nhập họ và tên" autoComplete="name" />
            </Form.Item>
            <Form.Item name="email" label="Email" rules={emailRules}>
              <Input
                placeholder="Nhập email"
                type="email"
                autoComplete="email"
              />
            </Form.Item>
            <Form.Item name="password" label="Mật khẩu" rules={passwordRules}>
              <Input.Password
                placeholder="12–128 ký tự"
                autoComplete="new-password"
              />
            </Form.Item>
          </>
        )}
        <ActionButton action={action} htmlType="submit" block>
          {["Gửi mã OTP", "Xác minh OTP", "Tạo tài khoản"][stage]}
        </ActionButton>
      </Form>
      <div className="auth-bottom">
        {stage > 0 && (
          <Button type="link" disabled={action.disabled} onClick={restart}>
            Yêu cầu OTP mới
          </Button>
        )}
        <Link to="/login">Đăng nhập</Link>
      </div>
    </>
  );
}
export function WorkspacePage() {
  const user = session((s) => s.user);
  return (
    <>
      <h1>Chọn khu vực làm việc</h1>
      <p className="auth-sub">
        Các khu vực bạn được server cấp quyền truy cập.
      </p>
      <div className="area-choices">
        {workspaces(user).map((area) => (
          <Link
            className="area-choice"
            key={area.path}
            to={
              area.path === "/customer" && !user?.isPinSet
                ? "/customer/pin/setup"
                : area.path
            }
          >
            <SafetyOutlined />
            <span>
              <strong>{area.label}</strong>
              <small>{area.detail}</small>
            </span>
            <ArrowRightOutlined />
          </Link>
        ))}
      </div>
    </>
  );
}
