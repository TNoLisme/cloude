// @vitest-environment jsdom
import "../../test/setup-dom";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { RecoveryPage, RegisterPage } from "./AuthPages";
function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
const user = {
  type: async (field: HTMLElement, value: string) =>
    fireEvent.change(field, { target: { value } }),
  clear: async (field: HTMLElement) =>
    fireEvent.change(field, { target: { value: "" } }),
  click: async (button: HTMLElement) => fireEvent.click(button),
};
it("recovery never opens password form before server verification; confirmation mismatch never sends reset", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      response({
        identifier: "0912345678",
        channel: "SMS",
        message: "generic",
        expiresInSeconds: 120,
      }),
    )
    .mockResolvedValueOnce(response({ code: "OTP_INVALID" }, 400))
    .mockResolvedValueOnce(
      response({ resetToken: "test-reset-proof", expiresInSeconds: 300 }),
    )
    .mockResolvedValueOnce(response({ message: "done" }));
  vi.stubGlobal("fetch", fetch);
  render(
    <MemoryRouter>
      <RecoveryPage />
    </MemoryRouter>,
  );
  await user.type(screen.getByLabelText("Số điện thoại"), "0912345678");
  await user.click(screen.getByRole("button", { name: /Yêu cầu mã OTP/ }));
  await screen.findByRole("heading", { name: "Xác minh mã OTP" });
  expect(screen.queryByLabelText("Mật khẩu mới")).toBeNull();
  await user.type(screen.getByLabelText("Mã OTP"), "000001");
  expect(fetch).toHaveBeenCalledTimes(1);
  await user.click(screen.getByRole("button", { name: /Xác minh OTP/ }));
  await screen.findAllByText(/OTP không đúng/);
  expect(screen.queryByLabelText("Mật khẩu mới")).toBeNull();
  await user.type(screen.getByLabelText("Mã OTP"), "000002");
  await user.click(screen.getByRole("button", { name: /Xác minh OTP/ }));
  await screen.findByRole("heading", { name: "Đặt mật khẩu mới" });
  await user.type(
    screen.getByLabelText("Mật khẩu mới", { exact: true }),
    "test-password-long",
  );
  await user.type(
    screen.getByLabelText("Xác nhận mật khẩu mới"),
    "different-password-long",
  );
  await user.click(screen.getByRole("button", { name: /Cập nhật mật khẩu/ }));
  await screen.findByText("Mật khẩu xác nhận chưa trùng khớp.");
  expect(fetch).toHaveBeenCalledTimes(3);
  await user.clear(screen.getByLabelText("Xác nhận mật khẩu mới"));
  await user.type(
    screen.getByLabelText("Xác nhận mật khẩu mới"),
    "test-password-long",
  );
  await user.click(screen.getByRole("button", { name: /Cập nhật mật khẩu/ }));
  await screen.findByText("Đã cập nhật mật khẩu");
  expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({
    resetToken: "test-reset-proof",
    newPassword: "test-password-long",
  });
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
it("registration verifies OTP through the new API before displaying profile and sends proof without OTP", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      response({ phone: "0912345678", message: "sent", expiresInSeconds: 120 }),
    )
    .mockResolvedValueOnce(
      response({
        registrationToken: "test-registration-proof",
        expiresInSeconds: 300,
      }),
    )
    .mockResolvedValueOnce(
      response(
        {
          customerId: "customer",
          account: { accountNumberMasked: "•••• 1234", balance: "0" },
        },
        201,
      ),
    );
  vi.stubGlobal("fetch", fetch);
  render(
    <MemoryRouter>
      <RegisterPage />
    </MemoryRouter>,
  );
  await user.type(screen.getByLabelText("Số điện thoại"), "0912345678");
  await user.click(screen.getByRole("button", { name: "Gửi mã OTP" }));
  await screen.findByRole("heading", { name: "Xác minh số điện thoại" });
  expect(screen.queryByLabelText("Họ và tên")).toBeNull();
  await user.type(screen.getByLabelText("Mã OTP"), "000001");
  expect(fetch).toHaveBeenCalledTimes(1);
  await user.click(screen.getByRole("button", { name: "Xác minh OTP" }));
  await screen.findByRole("heading", { name: "Thông tin tài khoản" });
  expect(fetch.mock.calls[1][0]).toBe("/api/v1/auth/register/verify-otp");
  await user.type(screen.getByLabelText("Họ và tên"), "Khách kiểm thử");
  await user.type(screen.getByLabelText("Email"), "test@example.test");
  await user.type(screen.getByLabelText("Mật khẩu"), "test-password-long");
  await user.click(screen.getByRole("button", { name: "Tạo tài khoản" }));
  await waitFor(() =>
    expect(screen.getByText("Tạo tài khoản thành công")).toBeTruthy(),
  );
  const body = JSON.parse(fetch.mock.calls[2][1].body);
  expect(body.registrationToken).toBe("test-registration-proof");
  expect(body).not.toHaveProperty("otp");
  expect(
    fetch.mock.calls.some((call) => call[0] === "/api/v1/auth/login"),
  ).toBe(false);
});
