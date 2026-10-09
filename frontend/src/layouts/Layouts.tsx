import { useEffect, useRef, useState } from "react";
import { Avatar, Button, Drawer } from "antd";
import {
  HomeOutlined,
  SwapOutlined,
  HistoryOutlined,
  UserOutlined,
  SearchOutlined,
  PlusOutlined,
  AuditOutlined,
  WarningOutlined,
  LogoutOutlined,
  MenuOutlined,
  SafetyOutlined,
} from "@ant-design/icons";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { Brand, Simulation } from "../components/shared";
import { hasRole, session } from "../stores/session";
import { logout } from "../api/client";
import { notice } from "../stores/notice";
import styles from "./AppLayout.module.css";
export function AuthLayout() {
  return (
    <main className={styles.auth}>
      <Brand />
      <section className={styles.authCard}>
        <Outlet />
      </section>
      <Simulation />
    </main>
  );
}
const customerLinks = [
  { path: "/customer", label: "Tổng quan", icon: <HomeOutlined />, end: true },
  {
    path: "/customer/transfers/new",
    label: "Chuyển tiền",
    icon: <SwapOutlined />,
  },
  {
    path: "/customer/transfers",
    label: "Lịch sử",
    icon: <HistoryOutlined />,
    end: true,
  },
  { path: "/customer/profile", label: "Hồ sơ", icon: <UserOutlined /> },
];
export function BankingLayout({ staff = false }: { staff?: boolean }) {
  const user = session((s) => s.user);
  const location = useLocation();
  const navigate = useNavigate();
  const [drawer, setDrawer] = useState(false);
  const [ending, setEnding] = useState(false);
  const logoutLock = useRef(false);
  useEffect(() => {
    setDrawer(false);
    document.querySelector<HTMLElement>("h1")?.focus({ preventScroll: true });
  }, [location.pathname]);
  const staffLinks = [
    ...(hasRole(user, ["OPERATOR", "ADMIN"])
      ? [
          {
            path: "/staff/customers",
            label: "Tra cứu khách",
            icon: <SearchOutlined />,
            end: true,
          },
          {
            path: "/staff/customers/new",
            label: "Tạo khách tại quầy",
            icon: <PlusOutlined />,
          },
        ]
      : []),
    ...(hasRole(user, ["AUDITOR", "ADMIN"])
      ? [
          {
            path: "/staff/audit",
            label: "Nhật ký kiểm toán",
            icon: <AuditOutlined />,
          },
        ]
      : []),
    ...(hasRole(user, ["OPERATOR", "AUDITOR", "ADMIN"])
      ? [{ path: "/staff/risk", label: "Cờ rủi ro", icon: <WarningOutlined /> }]
      : []),
  ];
  const links = staff ? staffLinks : customerLinks;
  async function endSession() {
    if (logoutLock.current) return;
    logoutLock.current = true;
    setEnding(true);
    try {
      await logout();
    } catch {
      notice
        .getState()
        .set(
          "Đã kết thúc phiên trên giao diện; chưa xác nhận thu hồi phiên trên máy chủ.",
        );
    } finally {
      logoutLock.current = false;
      setEnding(false);
      navigate("/login", { replace: true });
    }
  }
  const navigation = (
    <nav
      className={styles.nav}
      aria-label={staff ? "Khu vực nhân viên" : "Ngân hàng của bạn"}
    >
      {links.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          end={item.end}
          title={item.label}
          aria-label={item.label}
        >
          {item.icon}
          <span className={styles.navLabel}>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  );
  return (
    <div
      className={`${styles.shell} ${staff ? styles.staff : styles.customer}`}
    >
      <a className="skip-link" href="#main-content">
        Đến nội dung chính
      </a>
      <aside className={styles.sidebar}>
        <Brand compact />
        {navigation}
        <div className={styles.sidebarFooter}>
          <SafetyOutlined /> Môi trường mô phỏng
          <p>Chỉ sử dụng tiền VND giả lập.</p>
        </div>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <div className="actions">
            <Button
              className={styles.menuButton}
              aria-label="Mở menu nhân viên"
              icon={<MenuOutlined />}
              onClick={() => setDrawer(true)}
            />
            <span className="breadcrumb">
              {staff ? "Nhân viên" : "Digital Banking"}
            </span>
          </div>
          <div className={styles.topActions}>
            <Simulation />
            <div className={styles.person}>
              <Avatar style={{ background: "#eaf2fc", color: "#2868b2" }}>
                {user?.displayName.slice(0, 1)}
              </Avatar>
              <span>
                <strong>{user?.displayName}</strong>
                <small>{staff ? "Khu vực nhân viên" : "Khách hàng"}</small>
              </span>
            </div>
            <Button
              type="text"
              aria-label="Chọn khu vực"
              title="Chọn khu vực"
              icon={<SwapOutlined />}
              onClick={() => navigate("/workspace")}
            />
            <Button
              type="text"
              aria-label="Đăng xuất"
              title="Đăng xuất"
              loading={ending}
              icon={<LogoutOutlined />}
              onClick={() => void endSession()}
            />
          </div>
        </header>
        <main id="main-content" className={styles.main}>
          <div className={styles.mobileSimulation}>
            <Simulation />
          </div>
          <Outlet />
        </main>
      </div>
      {!staff && (
        <nav className={styles.bottomNav} aria-label="Điều hướng di động">
          {customerLinks.map((item) => (
            <NavLink key={item.path} to={item.path} end={item.end}>
              {item.icon}
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
      )}
      <Drawer
        title="Khu vực nhân viên"
        open={drawer}
        onClose={() => setDrawer(false)}
        placement="left"
        width={280}
      >
        <div className="drawer-nav">{navigation}</div>
      </Drawer>
    </div>
  );
}
