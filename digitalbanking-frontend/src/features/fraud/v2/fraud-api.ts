import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
export const fraudStatuses = [
  "OPEN",
  "UNDER_REVIEW",
  "ALLOWED",
  "BLOCKED",
  "CLOSED",
] as const;
export const fraudSeverities = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const flag = z.object({
  flagId: id,
  transferReference: z.string().regex(/^TXN[0-9]{8}[A-Z0-9]{8,20}$/),
  customerId: id,
  rule: z.object({
    code: z.string(),
    version: z.number().int(),
    type: z.enum(["LARGE_VALUE", "HIGH_FREQUENCY", "UNUSUAL_PATTERN"]),
  }),
  severity: z.enum(fraudSeverities),
  status: z.enum(fraudStatuses),
  matchedValue: z.record(z.string(), z.unknown()).nullable().optional(),
  decision: z
    .enum(["ALLOW", "BLOCK", "ESCALATE", "CLOSE"])
    .nullable()
    .optional(),
  notes: z.string().nullable().optional(),
  reviewedBy: z
    .object({ userId: id, displayName: z.string() })
    .nullable()
    .optional(),
  reviewedAt: instant.nullable().optional(),
  correlationId: z.string(),
  createdAt: instant,
});
const page = z.object({
  items: z.array(flag),
  page: z.object({
    number: z.number().int().nonnegative(),
    size: z.number().int().min(1).max(100),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    first: z.boolean(),
    last: z.boolean(),
  }),
  asOf: instant,
});
export type FraudFlag = z.infer<typeof flag>;
export type FraudFilters = {
  page: number;
  size?: number;
  status?: FraudFlag["status"];
  severity?: FraudFlag["severity"];
  ruleCode?: string;
  reference?: string;
  customerId?: string;
  from?: string;
  to?: string;
};
export const fraudApi = {
  list: async (
    client: ApiClient,
    filters: FraudFilters,
    signal: AbortSignal,
  ) => {
    if (!Number.isInteger(filters.page) || filters.page < 0)
      throw new TypeError("Invalid page");
    const size = filters.size ?? 20;
    if (!Number.isInteger(size) || size < 1 || size > 100)
      throw new TypeError("Invalid size");
    if (filters.customerId && !isApiId(filters.customerId))
      throw new TypeError("Invalid customer ID");
    const query = new URLSearchParams({
      page: String(filters.page),
      size: String(size),
    });
    for (const key of [
      "status",
      "severity",
      "ruleCode",
      "reference",
      "customerId",
      "from",
      "to",
    ] as const) {
      if (filters[key]) query.set(key, filters[key]);
    }
    const response = await client.request("/fraud-flags", { signal, query });
    const parsed = page.safeParse(response.data);
    if (response.status !== 200 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    return parsed.data;
  },
};
