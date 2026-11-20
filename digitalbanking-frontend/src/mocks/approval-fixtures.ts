import type { components } from "../shared/api/generated/v2/openapi";

type Schema<K extends keyof components["schemas"]> = components["schemas"][K];
export const approvalItems: Schema<"ApprovalSummary">[] = [
  {
    approvalId: "8001",
    requestType: "LIMIT_CHANGE",
    targetType: "CUSTOMER",
    targetId: "2001",
    status: "PENDING",
    maker: { userId: "4001", displayName: "Nhân viên mẫu A" },
    checker: null,
    createdAt: "2026-11-19T03:00:00Z",
    decidedAt: null,
    appliedAt: null,
    decisionNote: null,
    correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
  },
  {
    approvalId: "8002",
    requestType: "ROLE_CHANGE",
    targetType: "APP_USER",
    targetId: "4002",
    status: "APPLIED",
    maker: { userId: "4001", displayName: "Nhân viên mẫu A" },
    checker: { userId: "4003", displayName: "Nhân viên mẫu B" },
    createdAt: "2026-11-18T03:00:00Z",
    decidedAt: "2026-11-18T04:00:00Z",
    appliedAt: "2026-11-18T04:01:00Z",
    decisionNote: null,
    correlationId: "1f8fad5b-d9cb-469f-a165-70867728950e",
  },
];
export const approvalDetails: Schema<"ApprovalDetail">[] = approvalItems.map(
  (item) => ({
    ...item,
    payload: {},
  }),
);
