// @vitest-environment jsdom
import "../../../test/setup-dom";
import { StrictMode } from "react";
import {
  afterAll,
  afterEach,
  beforeAll,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ConfigProvider, Button } from "antd";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { createV2Session } from "../../../shared/auth/session";
import {
  currentV2FixtureOtp,
  resetV2MockSession,
  selectV2MockScenario,
  v2AuthFixture,
  v2AuthHandlers,
} from "../../../mocks/v2-auth";
import { V2AuthFlow } from "./AuthFlow";
import { V2SessionSummary } from "./AuthScreens";

const server = setupServer(...v2AuthHandlers);
const instances: ReturnType<typeof createV2Session>[] = [];
const base = "http://localhost/api/v2";
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  cleanup();
  instances.splice(0).forEach((s) => s.dispose());
  resetV2MockSession();
  server.resetHandlers();
});
function mount(path = "/login?authPreview=v2", strict = false) {
  const session = createV2Session({ baseUrl: base, onSessionCleared: vi.fn() });
  instances.push(session);
  // Test-only slot isolates flow assertions; the approved common summary is tested above.
  const app = (
    <ConfigProvider theme={{ token: { motion: false } }}>
      <MemoryRouter initialEntries={[path]}>
        <V2AuthFlow
          session={session}
          renderAuthenticated={(_auth, logout, busy) => (
            <>
              <h1>Phiên kiểm thử</h1>
              <Button disabled={busy} onClick={() => void logout()}>
                Kết thúc phiên kiểm thử
              </Button>
            </>
          )}
        />
      </MemoryRouter>
    </ConfigProvider>
  );
  const result = render(strict ? <StrictMode>{app}</StrictMode> : app);
  return { session, ...result };
}
async function login(username = "fixture-customer") {
  await screen.findByLabelText("Tên đăng nhập");
  fireEvent.change(screen.getByLabelText("Tên đăng nhập"), {
    target: { value: username },
  });
  fireEvent.change(screen.getByLabelText("Mật khẩu"), {
    target: { value: " fixture-only " },
  });
  fireEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));
}
async function otp(value: string) {
  fireEvent.change(screen.getByLabelText("Mã OTP"), { target: { value } });
  fireEvent.click(screen.getByRole("button", { name: "Xác minh OTP" }));
}

describe("auth v2 UI wiring to real session primitives and MSW", () => {
  it.each(["CUSTOMER", "OPERATIONS", "ADMIN", "AUDITOR"] as const)(
    "renders the approved metadata-only summary for %s without inferring business navigation",
    (role) => {
      const auth = v2AuthFixture();
      auth.user.roles = [role];
      render(
        <V2SessionSummary auth={auth} logout={async () => {}} ending={false} />,
      );
      expect(
        screen.getByRole("heading", { name: "Phiên hiện tại" }),
      ).toBeTruthy();
      expect(screen.getByRole("button", { name: "Đăng xuất" })).toBeTruthy();
      expect(screen.queryByText(auth.session.csrfToken)).toBeNull();
      expect(screen.queryByRole("link")).toBeNull();
      expect(screen.queryByRole("button", { name: "Chuyển tiền" })).toBeNull();
    },
  );
  it("shows bootstrap loading before anonymous login and validates required fields in Vietnamese", async () => {
    mount();
    expect(
      screen.getByRole("heading", { name: "Đang khôi phục phiên" }),
    ).toBeTruthy();
    await screen.findByLabelText("Tên đăng nhập");
    fireEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));
    expect(
      await screen.findByText("Vui lòng nhập tên đăng nhập."),
    ).toBeTruthy();
    expect(screen.getByText("Vui lòng nhập mật khẩu.")).toBeTruthy();
  });
  it("logs in with username, renders an injected test-only authenticated slot and logs out", async () => {
    mount();
    await login();
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
    fireEvent.click(
      screen.getByRole("button", { name: "Kết thúc phiên kiểm thử" }),
    );
    await screen.findByLabelText("Tên đăng nhập");
    expect(screen.getByText("Đã đăng xuất.")).toBeTruthy();
  });
  it("keeps username and clears password after invalid credentials", async () => {
    mount();
    await login("fixture-invalid");
    await screen.findByText("Tên đăng nhập hoặc mật khẩu không hợp lệ.");
    expect(
      (screen.getByLabelText("Tên đăng nhập") as HTMLInputElement).value,
    ).toBe("fixture-invalid");
    expect((screen.getByLabelText("Mật khẩu") as HTMLInputElement).value).toBe(
      "",
    );
  });
  it("keeps a network bootstrap error distinct from anonymous and supports explicit retry", async () => {
    selectV2MockScenario("bootstrap-error");
    mount();
    await screen.findByRole("button", { name: "Thử khôi phục lại" });
    expect(screen.queryByLabelText("Tên đăng nhập")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Thử khôi phục lại" }));
    await screen.findByLabelText("Tên đăng nhập");
  });
  it("can intentionally return to login from bootstrap failure", async () => {
    selectV2MockScenario("bootstrap-error");
    mount();
    fireEvent.click(
      await screen.findByRole("button", { name: "Đến đăng nhập" }),
    );
    await screen.findByLabelText("Tên đăng nhập");
  });
  it("prevents double login while a response is delayed", async () => {
    let calls = 0;
    let release!: () => void;
    const responseReady = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post(`${base}/auth/login`, async () => {
        calls++;
        await responseReady;
        return HttpResponse.json(v2AuthFixture());
      }),
    );
    mount();
    await login();
    try {
      await waitFor(() => expect(calls).toBe(1));
      fireEvent.click(screen.getByRole("button", { name: /Đăng nhập/ }));
      expect(calls).toBe(1);
    } finally {
      release();
    }
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
    expect(calls).toBe(1);
  });
  it("handles 202/incorrect OTP/resend and keeps attempts governed by server metadata", async () => {
    mount();
    await login("fixture-mfa");
    await screen.findByRole("heading", { name: "Xác minh đăng nhập" });
    expect(screen.getByText(/Số lần thử còn lại.*5/)).toBeTruthy();
    await otp("000000");
    await screen.findAllByText(
      "Mã OTP không đúng. Vui lòng kiểm tra và nhập lại.",
      {},
      { timeout: 2000 },
    );
    expect(
      screen.queryByRole("heading", { name: "Phiên kiểm thử" }),
    ).toBeNull();
    expect(screen.queryByText(/Số lần thử còn lại/)).toBeNull();
    expect((screen.getByLabelText("Mã OTP") as HTMLInputElement).value).toBe(
      "",
    );
    fireEvent.click(screen.getByRole("button", { name: "Gửi lại mã OTP" }));
    await screen.findByText(/Số lần thử còn lại.*4/);
    await otp("123456");
    await waitFor(() =>
      expect((screen.getByLabelText("Mã OTP") as HTMLInputElement).value).toBe(
        "",
      ),
    );
    await otp(currentV2FixtureOtp());
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
  });
  it("shows an expired challenge and only offers a restart", async () => {
    selectV2MockScenario("expired-otp");
    mount();
    await login("fixture-mfa");
    await screen.findByText("Mã OTP đã hết hạn. Vui lòng đăng nhập lại.");
    expect(
      screen
        .getByRole("button", { name: "Xác minh OTP" })
        .hasAttribute("disabled"),
    ).toBe(true);
    expect(
      screen
        .getByRole("button", { name: "Gửi lại mã OTP" })
        .hasAttribute("disabled"),
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Đăng nhập lại" }));
    await screen.findByLabelText("Tên đăng nhập");
  });
  it("a direct challenge URL without memory context returns to login", async () => {
    mount("/auth/challenge?authPreview=v2");
    await screen.findByLabelText("Tên đăng nhập");
    expect(
      screen.getByText(
        "Yêu cầu xác minh không còn hiệu lực trên trang này. Vui lòng đăng nhập lại.",
      ),
    ).toBeTruthy();
  });
  it("reports logout failure without claiming server revocation", async () => {
    mount();
    await login();
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
    selectV2MockScenario("logout-error");
    fireEvent.click(
      screen.getByRole("button", { name: "Kết thúc phiên kiểm thử" }),
    );
    await screen.findByLabelText("Tên đăng nhập");
    expect(
      screen.getByText(/chưa xác nhận thu hồi phiên trên máy chủ/),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Kiểm tra lại phiên" }));
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
  });
  it("is safe under StrictMode bootstrap effect replay", async () => {
    mount("/login?authPreview=v2", true);
    await screen.findByLabelText("Tên đăng nhập");
    await login();
    await screen.findByRole("heading", { name: "Phiên kiểm thử" });
  });
});
