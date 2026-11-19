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
import { afterEach, expect, it, vi } from "vitest";
import { TransferPage } from "./TransferPage";
import { transferFlow, type TransferIntent } from "../../stores/transfer";
import { session } from "../../stores/session";

vi.mock("../accounts/AccountPages", () => ({
  useAccounts: () => ({ isPending: false, data: [], error: null }),
}));
afterEach(() => {
  cleanup();
  session.getState().clear();
  vi.unstubAllGlobals();
});
it("blocks a new transfer after an unknown response and manually reconciles with the original key and payload", async () => {
  session.getState().accept("test-token", {
    userId: "user",
    customerId: "customer",
    displayName: "QA",
    phone: "0912345678",
    email: "qa@example.test",
    roles: ["CUSTOMER"],
    isPinSet: true,
  });
  const intent: TransferIntent = {
    key: "fixed-test-idempotency-key",
    body: {
      sourceAccountId: "source",
      destinationAccountId: "destination",
      amount: "5000000",
      currency: "VND",
      memo: "QA",
    },
    recipient: {
      accountId: "destination",
      currency: "VND",
      recipientDisplayName: "QA recipient",
      accountNumberMasked: "••••1234",
    },
    sourceMasked: "••••5678",
  };
  transferFlow.getState().update({ intent, stage: "review", result: null });
  let rejectRequest!: (reason: unknown) => void;
  const fetch = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectRequest = reject;
        }),
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          transferId: "transfer",
          status: "COMPLETED",
          sourceAccountId: "source",
          destinationAccountId: "destination",
          amount: "5000000",
          currency: "VND",
          createdAt: "2026-10-08T10:00:00Z",
        }),
        { status: 201 },
      ),
    );
  vi.stubGlobal("fetch", fetch);
  render(
    <MemoryRouter>
      <TransferPage />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("PIN giao dịch"), {
    target: { value: "000001" },
  });
  const confirm = screen.getByRole("button", { name: "Xác nhận chuyển" });
  fireEvent.click(confirm);
  fireEvent.click(confirm);
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  rejectRequest(new TypeError("Disconnected after dispatch"));
  await screen.findByText(
    "Chưa xác định kết quả — kiểm tra trạng thái giao dịch",
  );
  expect(screen.queryByRole("button", { name: "Tiếp tục" })).toBeNull();
  expect(transferFlow.getState().intent).toEqual(intent);
  expect(JSON.stringify(transferFlow.getState())).not.toContain("000001");
  fireEvent.change(screen.getByLabelText("Nhập lại PIN giao dịch"), {
    target: { value: "000001" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Đối soát bằng cùng yêu cầu" }),
  );
  await waitFor(() =>
    expect(transferFlow.getState().result?.status).toBe("COMPLETED"),
  );
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[0][1].headers["Idempotency-Key"]).toBe(intent.key);
  expect(fetch.mock.calls[1][1].headers["Idempotency-Key"]).toBe(intent.key);
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body);
});
