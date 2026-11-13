import { describe, expect, it } from "vitest";
import { hasRole, landing, session, workspaces, type User } from "./session";
import { nextTransferStage, transferFlow } from "./transfer";
import { operatorFlow } from "./operator";
const user: User = {
  userId: "a",
  customerId: "c",
  displayName: "Test",
  phone: "0912345678",
  email: "test@example.test",
  roles: ["CUSTOMER"],
  isPinSet: false,
};
describe("role routing and pending intents", () => {
  it("requires customer PIN but never forces staff PIN", () => {
    expect(landing(user)).toBe("/customer/pin/setup");
    expect(landing({ ...user, roles: ["OPERATOR"] })).toBe("/staff/customers");
    expect(landing({ ...user, roles: ["AUDITOR"] })).toBe("/staff/audit");
    expect(landing({ ...user, roles: ["ADMIN"] })).toBe("/workspace");
    expect(
      workspaces({ ...user, roles: ["ADMIN"] }).some(
        (area) => area.path === "/customer",
      ),
    ).toBe(false);
    expect(hasRole(user, ["OPERATOR", "ADMIN"])).toBe(false);
  });
  it("uses the server transfer state rather than amount to choose OTP/results", () => {
    expect(
      nextTransferStage({
        transferId: "id",
        status: "AWAITING_OTP",
        message: "wait",
        expiresInSeconds: 120,
      }),
    ).toBe("otp");
    expect(
      nextTransferStage({ transferId: "id", status: "FAILED" } as never),
    ).toBe("result");
  });
  it("retains the same business intent without PIN and clears it on user change", () => {
    session.getState().accept("a-token", user);
    transferFlow.getState().update({
      stage: "unknown",
      intent: {
        key: "stable",
        sourceMasked: "masked",
        recipient: {
          accountId: "dest",
          accountNumberMasked: "masked",
          recipientDisplayName: "Test",
          currency: "VND",
        },
        body: {
          sourceAccountId: "src",
          destinationAccountId: "dest",
          amount: "5000001",
          currency: "VND",
        },
      },
    });
    expect(transferFlow.getState().intent?.key).toBe("stable");
    expect(transferFlow.getState().intent?.body).not.toHaveProperty("pin");
    operatorFlow.getState().setSeed({
      accountId: "src",
      masked: "masked",
      key: "seed-key",
      body: { amount: "100", currency: "VND", reference: "test" },
      unknown: true,
    });
    session.getState().accept("b-token", { ...user, userId: "b" });
    expect(transferFlow.getState().intent).toBeNull();
    expect(operatorFlow.getState().seed).toBeNull();
  });
});
