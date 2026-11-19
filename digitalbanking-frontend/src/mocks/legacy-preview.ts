// UI-only compatibility fixtures approved by the user on 2026-11-13.
// These shapes are not API response contracts and are never sent to backend.
import { ApiError, type Schema } from "../api/client";
import type { components } from "../shared/api/generated/openapi";
import { transferFlow } from "../stores/transfer";

type Target<K extends keyof components["schemas"]> = components["schemas"][K];
export const previewUser: Schema<"UserSummary"> = {
  userId: "1001",
  customerId: "2001",
  displayName: "Nguyễn Văn A",
  phone: "0900000123",
  email: "preview@example.test",
  isPinSet: true,
  roles: ["CUSTOMER", "OPERATOR", "ADMIN", "AUDITOR"],
};
const time = "2026-11-13T03:00:00Z";
export const previewTransfer: Schema<"Transfer"> = {
  transferId: "11111111-2222-4333-8444-555555555555",
  status: "COMPLETED",
  sourceAccountId: "3001",
  destinationAccountId: "3002",
  amount: "150000",
  currency: "VND",
  memo: "Dữ liệu xem UI",
  createdAt: time,
  completedAt: time,
};
const history: Schema<"TransferListItem">[] = [
  {
    ...previewTransfer,
    direction: "OUTGOING",
    counterpartyAccountMasked: "**** **** 6789",
    counterpartyDisplayName: "Trần Thị B (demo)",
  },
];
const audit: Schema<"AuditEvent">[] = [
  {
    eventId: "22222222-2222-4333-8444-555555555555",
    eventType: "TRANSFER_COMPLETED",
    targetType: "TRANSFER",
    targetId: previewTransfer.transferId,
    actorId: "1001",
    outcome: "SUCCESS",
    occurredAt: time,
    correlationId: "33333333-2222-4333-8444-555555555555",
    summary: "Fixture hiển thị, chưa kiểm chứng audit BE",
  },
];
const risk: Schema<"RiskFlag">[] = [
  {
    flagId: "44444444-2222-4333-8444-555555555555",
    transferId: previewTransfer.transferId,
    ruleId: "LARGE_TRANSFER",
    ruleVersion: "preview",
    reason: "Fixture để xem UI cảnh báo",
    detectedAt: time,
  },
];
async function target<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    credentials: "include",
    signal,
  });
  if (!response.ok) {
    const problem = await response.json();
    throw new ApiError(response.status, problem.code, problem.correlationId);
  }
  return response.json();
}
function legacyAccount(
  a: Target<"AccountDetail"> | Target<"AccountSummary">,
): Schema<"Account"> {
  const balance = a.availableBalance;
  // Legacy renderer accepts whole VND only. Do not truncate a new-contract fraction.
  if (!/^\d+\.00$/.test(balance))
    throw new ApiError(400, "PREVIEW_DECIMAL_UNSUPPORTED");
  return {
    accountId: a.accountId,
    accountNumberMasked: a.accountNumberMasked,
    accountType: "CHECKING",
    balance: balance.slice(0, -3),
    currency: "VND",
    status: a.status === "ACTIVE" ? "ACTIVE" : "BLOCKED",
    openedAt: time,
  };
}
const page = (items: unknown[]) => ({ items, nextCursor: null });
export async function previewRequest(
  path: string,
  options: { method?: string; signal?: AbortSignal },
) {
  const url = new URL(path, "http://preview.local");
  const state = new URLSearchParams(window.location.search).get("previewState");
  // Bootstrap restores a UI persona, not a legacy refresh-token endpoint.
  if (url.pathname === "/auth/refresh")
    return {
      accessToken: "ui-fixture-only",
      tokenType: "Bearer",
      expiresIn: 900,
      user: previewUser,
    };
  if ((options.method || "GET") !== "GET")
    throw new ApiError(409, "PREVIEW_CONTRACT_PENDING");
  if (state === "error") throw new ApiError(503, "SERVICE_UNAVAILABLE");
  if (state === "loading")
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(resolve, 5000);
      options.signal?.addEventListener(
        "abort",
        () => {
          clearTimeout(timeout);
          reject(new DOMException("Aborted", "AbortError"));
        },
        { once: true },
      );
    });
  if (url.pathname === "/accounts") {
    const data = await target<Target<"AccountsResponse">>(
      "/accounts",
      options.signal,
    );
    return page(state === "empty" ? [] : data.items.map(legacyAccount));
  }
  if (url.pathname.startsWith("/accounts/"))
    return legacyAccount(
      await target<Target<"AccountDetail">>(url.pathname, options.signal),
    );
  if (url.pathname === "/customers/me") {
    const data = await target<Target<"CustomerProfile">>(
      "/customers/me",
      options.signal,
    );
    return {
      ...data,
      phone: data.phoneMasked,
      email: data.emailMasked,
      isPinSet: true,
    };
  }
  if (url.pathname === "/transfers")
    return page(
      state === "empty"
        ? []
        : history.filter(
            (t) =>
              (!url.searchParams.get("status") ||
                t.status === url.searchParams.get("status")) &&
              (!url.searchParams.get("direction") ||
                t.direction === url.searchParams.get("direction")),
          ),
    );
  if (url.pathname === `/transfers/${previewTransfer.transferId}`)
    return previewTransfer;
  if (url.pathname === "/audit-events")
    return page(state === "empty" ? [] : audit);
  if (url.pathname === "/operator/risk-flags")
    return page(state === "empty" ? [] : risk);
  if (url.pathname === "/operator/customers") {
    const data = await target<Target<"AccountsResponse">>(
      "/accounts",
      options.signal,
    );
    return {
      ...previewUser,
      fullName: previewUser.displayName,
      createdAt: time,
      accounts: data.items.map(legacyAccount),
    };
  }
  throw new ApiError(409, "PREVIEW_CONTRACT_PENDING");
}
export function preparePreviewState() {
  const state = new URLSearchParams(window.location.search).get("previewState");
  if (state !== "receipt" && state !== "otp") return;
  transferFlow.getState().update({
    intent: {
      key: "fixture-only",
      body: {
        sourceAccountId: "3001",
        destinationAccountId: "3002",
        amount: "150000",
        currency: "VND",
        memo: "Dữ liệu xem UI",
      },
      recipient: {
        accountId: "3002",
        accountNumberMasked: "**** **** 6789",
        recipientDisplayName: "Trần Thị B (demo)",
        currency: "VND",
      },
      sourceMasked: "**** **** 3001",
    },
    result:
      state === "otp"
        ? {
            ...previewTransfer,
            status: "AWAITING_OTP",
            expiresAt: new Date(Date.now() + 120000).toISOString(),
          }
        : previewTransfer,
    stage: state === "otp" ? "otp" : "result",
  });
}
