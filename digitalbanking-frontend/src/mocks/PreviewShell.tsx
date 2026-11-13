import { useEffect, useState, type ReactNode } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Alert, Button, Select } from "antd";

const screens = [
  ["/workspace", "Khu vực làm việc"],
  ["/login", "Đăng nhập"],
  ["/register", "Đăng ký"],
  ["/recover", "Khôi phục mật khẩu"],
  ["/customer", "Tổng quan"],
  ["/customer/accounts/3001", "Chi tiết tài khoản"],
  ["/customer/profile", "Hồ sơ"],
  ["/customer/pin/setup", "Thiết lập PIN"],
  ["/customer/pin/change", "Đổi PIN"],
  ["/customer/pin/reset", "Quên PIN"],
  ["/customer/transfers/new", "Chuyển tiền"],
  ["/customer/transfers", "Lịch sử"],
  [
    "/customer/transfers/11111111-2222-4333-8444-555555555555",
    "Chi tiết giao dịch",
  ],
  ["/staff/customers", "Tra cứu khách hàng"],
  ["/staff/customers/new", "Tạo khách tại quầy"],
  ["/staff/audit", "Nhật ký kiểm toán"],
  ["/staff/risk", "Cờ rủi ro"],
  ["/forbidden", "Lỗi 403"],
  ["/not-found-preview", "Lỗi 404"],
];
const schema = z.object({
  screen: z.string().refine((v) => screens.some(([path]) => path === v)),
  state: z.enum([
    "default",
    "empty",
    "error",
    "loading",
    "receipt",
    "otp",
    "bootstrap",
  ]),
});
export function PreviewShell({ children }: { children: ReactNode }) {
  const [pathname, setPathname] = useState(location.pathname);
  useEffect(() => {
    const update = () => setPathname(location.pathname);
    window.addEventListener("preview-route-change", update);
    return () => window.removeEventListener("preview-route-change", update);
  }, []);
  const currentState =
    new URLSearchParams(window.location.search).get("previewState") ||
    "default";
  const { control, handleSubmit } = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      screen: screens.some(([path]) => path === location.pathname)
        ? location.pathname
        : "/workspace",
      state: schema.shape.state.safeParse(currentState).success
        ? (currentState as z.infer<typeof schema>["state"])
        : "default",
    },
  });
  const locked =
    /^\/(login|register|recover)$/.test(pathname) ||
    pathname.includes("/pin/") ||
    pathname === "/customer/transfers/new" ||
    pathname === "/staff/customers/new";
  return (
    <>
      <aside className="preview-toolbar" aria-label="Điều khiển bản xem UI">
        <strong>Preview UI · dữ liệu tổng hợp</strong>
        <form
          onSubmit={handleSubmit((v) => {
            const path = ["receipt", "otp"].includes(v.state)
              ? "/customer/transfers/new"
              : v.screen;
            location.assign(`${path}?previewState=${v.state}`);
          })}
        >
          <Controller
            name="screen"
            control={control}
            render={({ field }) => (
              <Select
                {...field}
                aria-label="Màn hình"
                options={screens.map(([value, label]) => ({ value, label }))}
                style={{ width: 240 }}
              />
            )}
          />
          <Controller
            name="state"
            control={control}
            render={({ field }) => (
              <Select
                {...field}
                aria-label="Trạng thái UI"
                style={{ width: 180 }}
                options={[
                  { value: "default", label: "Có dữ liệu" },
                  { value: "empty", label: "Danh sách rỗng" },
                  { value: "error", label: "Lỗi đọc dữ liệu" },
                  { value: "loading", label: "Tải chậm 5 giây" },
                  { value: "receipt", label: "Biên nhận cũ" },
                  { value: "otp", label: "OTP cũ (chỉ xem)" },
                  { value: "bootstrap", label: "Khôi phục phiên" },
                ]}
              />
            )}
          />
          <Button htmlType="submit" type="primary">
            Mở màn hình
          </Button>
        </form>
        <span>
          Chờ chốt contract: các thao tác ghi bị khóa. Vai trò và dữ liệu là
          fixture để xem bố cục, chưa chứng minh quyền hay API thật.
        </span>
      </aside>
      {locked && (
        <Alert
          type="warning"
          showIcon
          message="Chờ chốt contract — chỉ xem giao diện; form và thao tác nghiệp vụ đang khóa."
        />
      )}
      <fieldset className="preview-content" disabled={locked}>
        {children}
      </fieldset>
    </>
  );
}
