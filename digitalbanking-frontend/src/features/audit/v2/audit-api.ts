import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
export const outcomes = ["SUCCESS", "FAILURE", "REJECTED"] as const;
const summary = z.object({
  auditId: id,
  occurredAt: instant,
  recordedAt: instant,
  actor: z.object({
    type: z.enum(["USER", "SYSTEM"]),
    userId: id.nullable().optional(),
    role: z.string(),
  }),
  action: z.string(),
  entity: z.object({ type: z.string(), id: z.string() }),
  correlationId: z.string(),
  outcome: z.enum(outcomes),
  recordHash: z.string(),
  prevHash: z.string().nullable().optional(),
  hashVersion: z.number().int().min(1),
});
const detail = summary.extend({
  requestId: z.string().nullable().optional(),
  // beforeState/afterState are open objects in YAML and are deliberately not projected.
});
const page = z.object({
  items: z.array(summary),
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
export type AuditFilters = {
  page: number;
  size?: number;
  reference?: string;
  actorUserId?: string;
  action?: string;
  outcome?: (typeof outcomes)[number];
  from?: string;
  to?: string;
};
export const auditApi = {
  search: async (
    client: ApiClient,
    filters: AuditFilters,
    signal: AbortSignal,
  ) => {
    const size = filters.size ?? 20;
    if (
      !Number.isInteger(filters.page) ||
      filters.page < 0 ||
      !Number.isInteger(size) ||
      size < 1 ||
      size > 100
    )
      throw new TypeError("Invalid pagination");
    if (filters.actorUserId && !isApiId(filters.actorUserId))
      throw new TypeError("Invalid actor ID");
    if (filters.action && filters.action.length > 64)
      throw new TypeError("Invalid action");
    if (
      (filters.from && !instant.safeParse(filters.from).success) ||
      (filters.to && !instant.safeParse(filters.to).success)
    )
      throw new TypeError("Invalid instant");
    if (
      filters.from &&
      filters.to &&
      Date.parse(filters.from) >= Date.parse(filters.to)
    )
      throw new TypeError("Invalid time range");
    const query = new URLSearchParams({
      page: String(filters.page),
      size: String(size),
    });
    for (const key of [
      "reference",
      "actorUserId",
      "action",
      "outcome",
      "from",
      "to",
    ] as const) {
      if (filters[key]) query.set(key, filters[key]);
    }
    const response = await client.request("/audit", { query, signal });
    const parsed = page.safeParse(response.data);
    if (response.status !== 200 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    return parsed.data;
  },
  detail: async (client: ApiClient, auditId: string, signal: AbortSignal) => {
    if (!isApiId(auditId)) throw new TypeError("Invalid audit ID");
    const response = await client.request(`/audit/${auditId}`, { signal });
    const parsed = detail.safeParse(response.data);
    if (response.status !== 200 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    return parsed.data;
  },
};
