import type { components } from "../shared/api/generated/v2/openapi";

type Flag = components["schemas"]["FraudFlag"];
export const fraudFlags: Flag[] = [
  {
    flagId: "9001",
    transferReference: "TXN20261120ABCDEF12",
    customerId: "2001",
    rule: { code: "LARGE_VALUE", version: 1, type: "LARGE_VALUE" },
    severity: "HIGH",
    status: "OPEN",
    matchedValue: null,
    decision: null,
    notes: null,
    reviewedBy: null,
    reviewedAt: null,
    correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
    createdAt: "2026-11-20T03:00:00Z",
  },
  {
    flagId: "9002",
    transferReference: "TXN20261119ABCDEF34",
    customerId: "2002",
    rule: { code: "HIGH_FREQUENCY", version: 1, type: "HIGH_FREQUENCY" },
    severity: "MEDIUM",
    status: "CLOSED",
    matchedValue: null,
    decision: "CLOSE",
    notes: null,
    reviewedBy: { userId: "4001", displayName: "Nhân viên mẫu A" },
    reviewedAt: "2026-11-19T04:00:00Z",
    correlationId: "1f8fad5b-d9cb-469f-a165-70867728950e",
    createdAt: "2026-11-19T03:00:00Z",
  },
];
