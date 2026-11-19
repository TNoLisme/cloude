import type { components } from "../shared/api/generated/v2/openapi";
type Schema<K extends keyof components["schemas"]> = components["schemas"][K];
const at = "2026-11-13T03:00:00Z";
export const historyItems: Schema<"TransactionItem">[] = [
  {
    reference: "TXN20261113A1B2C3D4",
    direction: "DEBIT",
    amount: "125000.00",
    currency: "VND",
    status: "COMPLETED",
    counterparty: {
      displayName: "Nguyễn Thị B",
      accountNumberMasked: "**** **** 4821",
    },
    description: "Chuyển khoản thử",
    createdAt: at,
    completedAt: at,
  },
  {
    reference: "TXN20261113E5F6G7H8",
    direction: "CREDIT",
    amount: "900000.00",
    currency: "VND",
    status: "PENDING",
    counterparty: {
      displayName: "Trần Văn C",
      accountNumberMasked: "**** **** 1672",
    },
    description: null,
    createdAt: at,
    completedAt: null,
  },
];
export const historyDetail = (
  item: Schema<"TransactionItem">,
): Schema<"TransactionDetail"> => ({
  ...item,
  sourceAccount:
    item.direction === "CREDIT"
      ? {
          accountId: "3003",
          accountNumberMasked: item.counterparty.accountNumberMasked,
          accountNumberLast4: item.counterparty.accountNumberMasked.slice(-4),
        }
      : {
          accountId: "3001",
          accountNumberMasked: "**** **** 3001",
          accountNumberLast4: "3001",
        },
  destinationAccount:
    item.direction === "CREDIT"
      ? {
          accountId: "3001",
          accountNumberMasked: "**** **** 3001",
          accountNumberLast4: "3001",
        }
      : {
          accountId: "3003",
          accountNumberMasked: item.counterparty.accountNumberMasked,
          accountNumberLast4: item.counterparty.accountNumberMasked.slice(-4),
        },
  correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
});
