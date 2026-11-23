// @vitest-environment jsdom
import "../../../test/setup-dom";
import { afterEach, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { account, profile } from "../../../mocks/fixtures";
import { v2Account } from "../../../mocks/v2-account-fixture";
import {
  CustomerAccountCard,
  CustomerAccountDetailPanel,
  CustomerProfilePanel,
} from "./CustomerPanels";

afterEach(cleanup);
it("renders masked customer profile and handles missing optional fields", () => {
  render(
    <CustomerProfilePanel
      profile={{ ...profile, phoneMasked: null, emailMasked: null }}
    />,
  );
  expect(screen.getByText(profile.fullName)).toBeTruthy();
  expect(screen.getAllByText("Chưa có thông tin")).toHaveLength(2);
  expect(screen.queryByText(/PIN giao dịch/)).toBeNull();
});
it("shows masked account and preserves full decimal precision", () => {
  render(
    <CustomerAccountCard
      account={{ ...account, availableBalance: "99999999999999999.99" }}
    />,
  );
  expect(screen.getByText("**** **** 3001")).toBeTruthy();
  expect(screen.getByText("99.999.999.999.999.999,99 ₫")).toBeTruthy();
  expect(screen.queryByRole("link")).toBeNull();
  expect(screen.queryByText("Đang hoạt động")).toBeNull();
});
it("shows account detail using only the masked number and server owner", () => {
  render(<CustomerAccountDetailPanel account={v2Account} />);
  expect(screen.getByText(v2Account.owner.fullName)).toBeTruthy();
  expect(screen.getByText(account.accountNumberMasked)).toBeTruthy();
  expect(screen.getByText("25.000.000,00 ₫")).toBeTruthy();
});
it("shows a safe fallback for an unknown account status", () => {
  render(
    <CustomerAccountDetailPanel
      account={{ ...v2Account, status: "constructor" as typeof account.status }}
    />,
  );
  expect(screen.getByText("Trạng thái chưa hỗ trợ")).toBeTruthy();
});
