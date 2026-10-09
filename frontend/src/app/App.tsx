import { lazy, Suspense, useEffect, useState } from "react";
import { Alert, Button, Result } from "antd";
import {
  BrowserRouter,
  Navigate,
  Outlet,
  Route,
  Routes,
  Link,
} from "react-router-dom";
import { ApiError, refresh } from "../api/client";
import { hasRole, landing, session, type Role } from "../stores/session";
import { notice } from "../stores/notice";
import {
  ActionButton,
  Brand,
  ErrorPanel,
  Loading,
  Simulation,
  useAction,
} from "../components/shared";
import { AuthLayout, BankingLayout } from "../layouts/Layouts";
import {
  LoginPage,
  RecoveryPage,
  RegisterPage,
  WorkspacePage,
} from "../features/auth/AuthPages";
import {
  AccountPage,
  DashboardPage,
  PinPage,
  ProfilePage,
} from "../features/accounts/AccountPages";
import { TransferPage } from "../features/transfers/TransferPage";
import {
  HistoryPage,
  TransferDetailPage,
} from "../features/transfers/HistoryPages";
const OperatorPage = lazy(() =>
  import("../features/staff/OperatorPages").then((module) => ({
    default: module.OperatorPage,
  })),
);
const CreateCustomerPage = lazy(() =>
  import("../features/staff/OperatorPages").then((module) => ({
    default: module.CreateCustomerPage,
  })),
);
const ReadOnlyPage = lazy(() =>
  import("../features/staff/ReadOnlyPages").then((module) => ({
    default: module.ReadOnlyPage,
  })),
);

function Guard({ roles, pin = false }: { roles?: Role[]; pin?: boolean }) {
  const user = session((s) => s.user);
  if (!user) return <Navigate to="/login" replace />;
  if (roles && !hasRole(user, roles))
    return <Navigate to="/forbidden" replace />;
  if (pin && !user.isPinSet)
    return <Navigate to="/customer/pin/setup" replace />;
  return <Outlet />;
}
function Home() {
  const user = session((s) => s.user);
  return <Navigate to={user ? landing(user) : "/login"} replace />;
}
export function App() {
  const [boot, setBoot] = useState(true);
  const [bootError, setBootError] = useState<unknown>(null);
  const action = useAction();
  const message = notice((s) => s.text);
  async function bootstrap() {
    setBoot(true);
    setBootError(null);
    try {
      await refresh();
      setBoot(false);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setBoot(false);
      else {
        setBootError(e);
        setBoot(false);
      }
    }
  }
  useEffect(() => {
    void bootstrap();
  }, []);
  if (boot || bootError)
    return (
      <main className="bootstrap">
        <Brand />
        <section className="bootstrap-card">
          <h1>Đang khôi phục phiên</h1>
          {boot ? (
            <Loading />
          ) : (
            <>
              <ErrorPanel error={bootError} />
              <ActionButton
                action={action}
                onClick={() => void action.run(bootstrap)}
              >
                Thử khôi phục lại
              </ActionButton>
              <Button
                className="spaced"
                onClick={() => {
                  session.getState().clear();
                  setBootError(null);
                }}
              >
                Đến đăng nhập
              </Button>
            </>
          )}
        </section>
        <Simulation />
      </main>
    );
  return (
    <BrowserRouter>
      {message && (
        <Alert
          type="warning"
          showIcon
          message={message}
          closable
          onClose={() => notice.getState().set("")}
        />
      )}
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route element={<AuthLayout />}>
            <Route path="login" element={<LoginPage />} />
            <Route path="register" element={<RegisterPage />} />
            <Route path="recover" element={<RecoveryPage />} />
            <Route element={<Guard />}>
              <Route path="workspace" element={<WorkspacePage />} />
            </Route>
            <Route
              path="forbidden"
              element={
                <Result
                  status="403"
                  title="Bạn không có quyền truy cập"
                  subTitle="Khu vực này yêu cầu quyền phù hợp."
                  extra={
                    <Link to="/">
                      <Button type="primary">Về khu vực của bạn</Button>
                    </Link>
                  }
                />
              }
            />
            <Route
              path="*"
              element={
                <Result
                  status="404"
                  title="Không tìm thấy nội dung"
                  extra={<Link to="/">Về trang chính</Link>}
                />
              }
            />
          </Route>
          <Route element={<Guard roles={["CUSTOMER"]} />}>
            <Route path="customer" element={<BankingLayout />}>
              <Route index element={<DashboardPage />} />
              <Route path="accounts/:accountId" element={<AccountPage />} />
              <Route path="profile" element={<ProfilePage />} />
              <Route
                path="pin/setup"
                element={<PinPage key="setup" mode="setup" />}
              />
              <Route
                path="pin/change"
                element={<PinPage key="change" mode="change" />}
              />
              <Route
                path="pin/reset"
                element={<PinPage key="reset" mode="reset" />}
              />
              <Route path="transfers" element={<HistoryPage />} />
              <Route
                path="transfers/:transferId"
                element={<TransferDetailPage />}
              />
              <Route element={<Guard roles={["CUSTOMER"]} pin />}>
                <Route path="transfers/new" element={<TransferPage />} />
              </Route>
            </Route>
          </Route>
          <Route element={<Guard roles={["OPERATOR", "AUDITOR", "ADMIN"]} />}>
            <Route path="staff" element={<BankingLayout staff />}>
              <Route element={<Guard roles={["OPERATOR", "ADMIN"]} />}>
                <Route path="customers" element={<OperatorPage />} />
                <Route path="customers/new" element={<CreateCustomerPage />} />
              </Route>
              <Route element={<Guard roles={["AUDITOR", "ADMIN"]} />}>
                <Route
                  path="audit"
                  element={<ReadOnlyPage key="audit" mode="audit" />}
                />
              </Route>
              <Route
                path="risk"
                element={<ReadOnlyPage key="risk" mode="risk" />}
              />
            </Route>
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
