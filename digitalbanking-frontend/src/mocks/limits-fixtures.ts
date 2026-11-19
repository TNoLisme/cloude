import type { components } from "../shared/api/generated/v2/openapi";

export const limitsFixture: components["schemas"]["LimitsResponse"] = {
  customerId: "2001",
  currency: "VND",
  usageDate: "2026-11-19",
  effectivePerTransactionLimit: "5000000.00",
  effectiveRemainingDaily: "17500000.00",
  items: [
    {
      limitId: "9001",
      scope: "CUSTOMER",
      accountId: null,
      perTransactionLimit: "5000000.00",
      dailyLimit: "20000000.00",
      currency: "VND",
      usedToday: "2500000.00",
      remainingDaily: "17500000.00",
      effectiveFrom: "2026-11-01T00:00:00Z",
      effectiveTo: null,
      version: 3,
    },
  ],
  asOf: "2026-11-19T03:00:00Z",
};
