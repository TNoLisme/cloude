import { useEffect, useState } from "react";
import { Button, Select, Spin } from "antd";
import { BrowserRouter } from "react-router-dom";
import { AuthSplitLayout } from "../features/auth/v2/AuthSplitLayout";
import styles from "../features/auth/v2/AuthSplitLayout.module.css";
import { queryClient } from "../app/query";
import { transferFlow } from "../stores/transfer";
import { operatorFlow } from "../stores/operator";
import { session as legacySession } from "../stores/session";
import { createV2Session } from "../shared/auth/session";
import { browserSessionSync } from "../shared/auth/session-sync";
import { V2AuthFlow } from "../features/auth/v2/AuthFlow";
import { V2SessionSummary } from "../features/auth/v2/AuthScreens";
import {
  currentV2FixtureOtp,
  enableV2FixturePersistence,
  resetV2MockSession,
  selectV2MockScenario,
  seedAuthenticatedV2Fixture,
} from "./v2-auth";

export function prepareV2AuthPreview() {
  enableV2FixturePersistence();
  const selected = new URLSearchParams(location.search).get("authScenario");
  if (selected) resetV2MockSession();
  if (selected === "authenticated") seedAuthenticatedV2Fixture();
  if (
    selected === "bootstrap-error" ||
    selected === "bootstrap-slow" ||
    selected === "logout-error" ||
    selected === "expired-otp"
  )
    selectV2MockScenario(selected);
}

export function AuthPreview() {
  const [controller, setController] = useState<ReturnType<
    typeof createV2Session
  > | null>(null);
  const [scenario, setScenario] = useState(
    new URLSearchParams(location.search).get("authScenario") ?? "anonymous",
  );
  const [fixtureCode, setFixtureCode] = useState("");
  const allowed =
    (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true") ||
    import.meta.env.MODE === "test";
  useEffect(() => {
    if (!allowed) return;
    const session = createV2Session({
      baseUrl: "/api/v2",
      sync: browserSessionSync(),
      onSessionCleared() {
        void queryClient.cancelQueries();
        queryClient.clear();
        transferFlow.getState().clear();
        operatorFlow.getState().setSeed(null);
        legacySession.getState().clear();
      },
    });
    setController(session);
    return () => session.dispose();
  }, [allowed]);
  if (!allowed) return null;
  return (
    <>
      <details className={styles.previewTools}>
        <summary>Điều khiển bản xem thử</summary>
        <aside className="preview-toolbar" aria-label="Điều khiển auth mock v2">
          <strong>Auth v2 · dữ liệu tổng hợp</strong>
          <Select
            aria-label="Kịch bản auth"
            value={scenario}
            onChange={setScenario}
            style={{ minWidth: 200 }}
            options={[
              { value: "anonymous", label: "Chưa đăng nhập" },
              {
                value: "authenticated",
                label: "Khôi phục fixture đã đăng nhập",
              },
              { value: "bootstrap-slow", label: "Khôi phục chậm" },
              { value: "bootstrap-error", label: "Lỗi khôi phục phiên" },
              { value: "logout-error", label: "Lỗi logout máy chủ" },
              { value: "expired-otp", label: "OTP đã hết hạn" },
            ]}
          />
          <Button
            onClick={() =>
              location.assign(
                `/login?authPreview=v2&authScenario=${encodeURIComponent(scenario)}`,
              )
            }
          >
            Mở kịch bản
          </Button>
          <Button onClick={() => setFixtureCode(currentV2FixtureOtp())}>
            Xem mã OTP fixture
          </Button>
          {fixtureCode && <span>Mã fixture vừa lấy: {fixtureCode}</span>}
          <a href="/login">Xem lại các màn cũ</a>
          <span>
            Login: fixture-customer hoặc fixture-mfa; mật khẩu mẫu:
            fixture-only. Chỉ dùng dữ liệu thử. Cờ khôi phục fixture không chứng
            minh cookie/session BE.
          </span>
        </aside>
      </details>
      <AuthSplitLayout>
        {controller ? (
          <BrowserRouter>
            <V2AuthFlow
              session={controller}
              renderAuthenticated={(auth, logout, ending) => (
                <V2SessionSummary auth={auth} logout={logout} ending={ending} />
              )}
            />
          </BrowserRouter>
        ) : (
          <>
            <h1>Đang khôi phục phiên</h1>
            <Spin />
          </>
        )}
      </AuthSplitLayout>
    </>
  );
}
