import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { Alert, Button, Spin } from "antd";
import { useLocation, useNavigate } from "react-router-dom";
import {
  AuthError,
  V2ChallengeForm,
  V2LoginForm,
  type V2Session,
} from "./AuthScreens";
import type { AuthResponse } from "../../../shared/auth/contracts";

export const v2PreviewPath = (path: string) => `${path}?authPreview=v2`;
export function V2AuthFlow({
  session,
  renderAuthenticated,
  renderFrame,
  customerDashboard = false,
}: {
  session: V2Session;
  renderAuthenticated: (
    auth: AuthResponse,
    logout: () => Promise<void>,
    busy: boolean,
  ) => ReactNode;
  renderFrame?: (content: ReactNode) => ReactNode;
  customerDashboard?: boolean;
}) {
  const state = useSyncExternalStore(
    session.subscribe,
    session.getSnapshot,
    session.getSnapshot,
  );
  const [booting, setBooting] = useState(true),
    [bootError, setBootError] = useState<unknown>(null);
  const [notice, setNotice] = useState("");
  const [ending, setEnding] = useState(false);
  const logoutLock = useRef(false);
  const location = useLocation(),
    navigate = useNavigate();
  const previousPhase = useRef(state.phase);
  const isCustomer =
    customerDashboard &&
    state.auth?.user.userType === "CUSTOMER" &&
    state.auth.user.roles.includes("CUSTOMER");
  const mounted = useRef(true);
  const bootTicket = useRef(0);
  const bootstrap = useCallback(async () => {
    const ticket = ++bootTicket.current;
    setBooting(true);
    setBootError(null);
    try {
      await session.bootstrap();
    } catch (error) {
      if (
        mounted.current &&
        ticket === bootTicket.current &&
        !(error instanceof DOMException && error.name === "AbortError")
      )
        setBootError(error);
    } finally {
      if (mounted.current && ticket === bootTicket.current) setBooting(false);
    }
  }, [session]);
  const cancelBoot = useCallback(() => {
    mounted.current = false;
    bootTicket.current++;
    session.client.invalidate();
  }, [session]);
  useEffect(() => {
    mounted.current = true;
    void bootstrap();
    return cancelBoot;
  }, [bootstrap, cancelBoot]);
  useEffect(() => {
    if (
      previousPhase.current === "authenticated" &&
      state.phase === "anonymous" &&
      !logoutLock.current
    )
      setNotice(
        "Phiên đã hết hiệu lực hoặc thay đổi ở tab khác. Vui lòng đăng nhập lại.",
      );
    previousPhase.current = state.phase;
    if (booting || bootError) return;
    const target =
      state.phase === "authenticated"
        ? isCustomer
          ? "/customer"
          : "/workspace"
        : state.phase === "challenge"
          ? "/auth/challenge"
          : "/login";
    const customerPath =
      location.pathname === "/customer" ||
      location.pathname === "/customer/profile" ||
      /^\/customer\/accounts\/[^/]+$/.test(location.pathname) ||
      location.pathname === "/customer/history" ||
      location.pathname === "/customer/limits" ||
      /^\/customer\/history\/[^/]+$/.test(location.pathname);
    if (location.pathname !== target && !(isCustomer && customerPath)) {
      if (location.pathname === "/auth/challenge" && target === "/login")
        setNotice(
          "Yêu cầu xác minh không còn hiệu lực trên trang này. Vui lòng đăng nhập lại.",
        );
      void navigate(v2PreviewPath(target), { replace: true });
    }
  }, [
    state.phase,
    isCustomer,
    booting,
    bootError,
    location.pathname,
    navigate,
  ]);
  useEffect(() => {
    document.querySelector<HTMLElement>("h1")?.focus();
  }, [state.phase, booting, location.pathname]);

  async function logout() {
    if (logoutLock.current) return;
    logoutLock.current = true;
    setEnding(true);
    try {
      const result = await session.logout();
      if (mounted.current)
        setNotice(
          result.serverConfirmed
            ? "Đã đăng xuất."
            : "Đã kết thúc phiên trên giao diện; chưa xác nhận thu hồi phiên trên máy chủ. Hãy kiểm tra lại phiên trước khi thử đăng xuất lần nữa.",
        );
    } catch (error) {
      if (
        mounted.current &&
        !(error instanceof DOMException && error.name === "AbortError")
      )
        setNotice(
          "Chưa xác nhận được kết quả đăng xuất. Vui lòng kiểm tra lại phiên.",
        );
    } finally {
      if (mounted.current) setEnding(false);
      logoutLock.current = false;
    }
  }
  if (booting || bootError) {
    const content = (
      <>
        <h1 tabIndex={-1}>Đang khôi phục phiên</h1>
        {booting ? (
          <div role="status" aria-label="Đang kiểm tra phiên" aria-busy="true">
            <Spin />
            <p>Vui lòng chờ trong giây lát.</p>
          </div>
        ) : (
          <>
            <AuthError error={bootError} />
            <Button
              type="primary"
              block
              className="spaced"
              onClick={() => void bootstrap()}
            >
              Thử khôi phục lại
            </Button>
            <Button
              block
              className="spaced"
              onClick={() => {
                session.restartLogin();
                setBootError(null);
                void navigate(v2PreviewPath("/login"), { replace: true });
              }}
            >
              Đến đăng nhập
            </Button>
          </>
        )}
      </>
    );
    return renderFrame ? renderFrame(content) : content;
  }
  const content = (
    <>
      {notice && (
        <Alert
          className="flow-alert"
          role="status"
          type="info"
          showIcon
          message={notice}
          closable
          onClose={() => setNotice("")}
        />
      )}
      {state.auth ? (
        renderAuthenticated(state.auth, logout, ending)
      ) : state.challenge ? (
        <V2ChallengeForm session={session} response={state.challenge} />
      ) : (
        <>
          <V2LoginForm session={session} />
          <Button
            className="spaced"
            type="link"
            onClick={() => void bootstrap()}
          >
            Kiểm tra lại phiên
          </Button>
        </>
      )}
    </>
  );
  return isCustomer || !renderFrame ? content : renderFrame(content);
}
