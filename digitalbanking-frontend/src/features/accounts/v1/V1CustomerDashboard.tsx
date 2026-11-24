import { Alert, Button, Descriptions, Spin, Tag } from "antd";
import { useQuery } from "@tanstack/react-query";
import { useLocation, useMatch, useNavigate } from "react-router-dom";
import { Brand, Heading, Panel, date } from "../../../components/shared";
import { Icon, Illustration } from "../../../components/icons";
import { formatMoney } from "../../../shared/lib/money";
import { ApiError } from "../../../shared/api/client";
import type { AuthResponse } from "../../../shared/auth/contracts";
import {
  CustomerAccountCard,
  CustomerProfilePanel,
} from "../v2/CustomerPanels";
import type {
  createV1CustomerReadApi,
  V1AccountDetail,
} from "./customer-read-api";
import styles from "../../../layouts/AppLayout.module.css";
import homeStyles from "../../../mocks/CustomerHome.module.css";

type Reader = ReturnType<typeof createV1CustomerReadApi>;

function ReadProblem({ error, retry }: { error: unknown; retry: () => void }) {
  const status = error instanceof ApiError ? error.meta.status : 0;
  const message =
    status === 403
      ? "Bạn không có quyền xem dữ liệu này."
      : status === 404
        ? "Không tìm thấy dữ liệu được yêu cầu."
        : status === 401
          ? "Phiên đã hết hiệu lực. Vui lòng đăng nhập lại."
          : "Không thể tải dữ liệu. Vui lòng thử lại.";
  return (
    <Alert
      type="error"
      showIcon
      role="alert"
      message={message}
      action={<Button onClick={retry}>Thử lại</Button>}
    />
  );
}

function AccountDetail({ account }: { account: V1AccountDetail }) {
  const status =
    account.status === "ACTIVE"
      ? "Đang hoạt động"
      : account.status === "LOCKED"
        ? "Đã khóa"
        : account.status === "CLOSED"
          ? "Đã đóng"
          : "Trạng thái chưa hỗ trợ";
  return (
    <Panel narrow title="Tài khoản VND">
      <p className="balance-value">{formatMoney(account.availableBalance)}</p>
      <Descriptions
        column={1}
        colon={false}
        items={[
          {
            key: "owner",
            label: "Mã khách hàng",
            children: account.owner.customerId,
          },
          {
            key: "number",
            label: "Số tài khoản",
            children: account.accountNumberMasked,
          },
          { key: "currency", label: "Loại tiền", children: account.currency },
          { key: "status", label: "Trạng thái", children: <Tag>{status}</Tag> },
          {
            key: "created",
            label: "Ngày mở",
            children: date(account.createdAt),
          },
          { key: "asof", label: "Cập nhật", children: date(account.asOf) },
        ]}
      />
    </Panel>
  );
}

export function V1CustomerDashboard({
  auth,
  reader,
  logout,
  ending,
}: {
  auth: AuthResponse;
  reader: Reader;
  logout: () => Promise<void>;
  ending: boolean;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const detailMatch = useMatch("/customer/accounts/:accountId");
  const accountId = detailMatch?.params.accountId ?? "";
  const view = detailMatch
    ? "detail"
    : location.pathname === "/customer/profile"
      ? "profile"
      : "accounts";
  const scope = ["v1", auth.user.userId, auth.user.customerId, "customer"];
  const profile = useQuery({
    queryKey: [...scope, "profile"],
    queryFn: ({ signal }) => reader.profile(signal),
    enabled: view === "profile",
    retry: false,
    staleTime: 0,
  });
  const accounts = useQuery({
    queryKey: [...scope, "accounts"],
    queryFn: ({ signal }) => reader.accounts(signal),
    enabled: view === "accounts",
    retry: false,
    staleTime: 0,
  });
  const detail = useQuery({
    queryKey: [...scope, "detail", accountId],
    queryFn: ({ signal }) => reader.detail(accountId, signal),
    enabled: view === "detail",
    retry: false,
    staleTime: 0,
  });
  const current =
    view === "profile" ? profile : view === "detail" ? detail : accounts;
  const go = (path: string) => void navigate(path);

  return (
    <div className={`${styles.shell} ${styles.customer} ${homeStyles.shell}`}>
      <aside className={`${styles.sidebar} ${homeStyles.sidebar}`}>
        <nav className={homeStyles.nav} aria-label="Điều hướng khách hàng">
          <button
            type="button"
            className={`${homeStyles.navItem} ${view === "accounts" ? homeStyles.active : ""}`}
            onClick={() => go("/customer")}
            aria-current={view === "accounts" ? "page" : undefined}
          >
            <Icon name="home" /> <span>Trang chủ</span>
          </button>
          <button
            type="button"
            className={`${homeStyles.navItem} ${view === "detail" ? homeStyles.active : ""}`}
            onClick={() => {
              const id = accountId || accounts.data?.items[0]?.accountId;
              if (id) go(`/customer/accounts/${id}`);
              else go("/customer");
            }}
            aria-current={view === "detail" ? "page" : undefined}
          >
            <Icon name="account" /> <span>Thông tin tài khoản</span>{" "}
            <Icon
              name="chevronRight"
              size="sm"
              className={homeStyles.chevron}
            />
          </button>
          <button
            type="button"
            className={`${homeStyles.navItem} ${view === "profile" ? homeStyles.active : ""}`}
            onClick={() => go("/customer/profile")}
            aria-current={view === "profile" ? "page" : undefined}
          >
            <Icon name="profile" /> <span>Hồ sơ của tôi</span>{" "}
            <Icon
              name="chevronRight"
              size="sm"
              className={homeStyles.chevron}
            />
          </button>
        </nav>
      </aside>
      <div className={styles.workspace}>
        <header className={`${styles.topbar} ${homeStyles.topbar}`}>
          <Brand compact />
          <div className={homeStyles.welcome}>
            <span>Chào mừng trở lại</span>
            <strong>{auth.user.displayName}</strong>
          </div>
          <div className={styles.topActions}>
            <Button
              disabled={ending}
              loading={ending}
              onClick={() => void logout()}
            >
              Đăng xuất
            </Button>
          </div>
        </header>
        <main className={`${styles.main} ${homeStyles.main}`}>
          <div className={styles.customerPreviewNav}>
            <Button onClick={() => go("/customer")}>Trang chủ</Button>
            <Button onClick={() => go("/customer/profile")}>Hồ sơ</Button>
          </div>
          <Heading
            title={
              view === "profile"
                ? "Hồ sơ"
                : view === "detail"
                  ? "Chi tiết tài khoản"
                  : "Tổng quan"
            }
            sub={
              view === "profile"
                ? "Thông tin cá nhân chỉ đọc."
                : "Tài khoản của tôi"
            }
          />
          {view === "detail" && (
            <Button onClick={() => go("/customer")}>Quay lại danh sách</Button>
          )}
          {current.isPending && (
            <div role="status">
              <Spin /> Đang tải dữ liệu
            </div>
          )}
          {current.isError && (
            <ReadProblem
              error={current.error}
              retry={() => void current.refetch()}
            />
          )}
          {view === "profile" && !profile.isError && profile.data && (
            <CustomerProfilePanel profile={profile.data} />
          )}
          {view === "accounts" &&
            !accounts.isError &&
            accounts.data &&
            (accounts.data.items.length === 0 ? (
              <div className={homeStyles.emptyAccounts}>
                <Illustration name="emptyAccounts" />
                <p>Chưa có tài khoản.</p>
              </div>
            ) : (
              <div className={homeStyles.accountGrid}>
                {accounts.data.items.map((account) => (
                  <div
                    className={homeStyles.accountRow}
                    key={account.accountId}
                  >
                    <CustomerAccountCard
                      account={account}
                      onDetails={() =>
                        go(`/customer/accounts/${account.accountId}`)
                      }
                    />
                    <Panel title="Thông tin tài khoản">
                      <Descriptions
                        column={1}
                        colon={false}
                        items={[
                          {
                            key: "number",
                            label: "Số tài khoản",
                            children: account.accountNumberMasked,
                          },
                          {
                            key: "currency",
                            label: "Loại tiền",
                            children: account.currency,
                          },
                          {
                            key: "snapshot",
                            label: "Cập nhật",
                            children: date(account.asOf),
                          },
                        ]}
                      />
                    </Panel>
                  </div>
                ))}
              </div>
            ))}
          {view === "detail" && !detail.isError && detail.data && (
            <AccountDetail account={detail.data} />
          )}
        </main>
      </div>
    </div>
  );
}
