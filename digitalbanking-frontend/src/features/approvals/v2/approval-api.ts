import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
const actor = z.object({ userId: id, displayName: z.string() });
const summary = z.object({
  approvalId: id,
  requestType: z.enum(["LIMIT_CHANGE", "ROLE_CHANGE"]),
  targetType: z.enum(["CUSTOMER", "ACCOUNT", "APP_USER"]),
  targetId: z.string(),
  status: z.enum(["PENDING", "APPROVED", "REJECTED", "APPLIED", "CANCELLED"]),
  maker: actor,
  checker: actor.nullable().optional(),
  createdAt: instant,
  decidedAt: instant.nullable().optional(),
  appliedAt: instant.nullable().optional(),
  decisionNote: z.string().nullable().optional(),
  correlationId: z.string(),
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
const detail = summary.extend({
  payload: z.record(z.string(), z.unknown()),
  result: z.record(z.string(), z.unknown()).optional(),
});
async function read<T>(
  client: ApiClient,
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
  query?: URLSearchParams,
) {
  const response = await client.request(path, { signal, query });
  const parsed = schema.safeParse(response.data);
  if (response.status !== 200 || !parsed.success)
    throw new ApiError("INVALID_RESPONSE", response);
  return parsed.data;
}
export const approvalApi = {
  list: (
    client: ApiClient,
    filters: {
      page: number;
      size?: number;
      requestType?: "LIMIT_CHANGE" | "ROLE_CHANGE";
      status?: z.infer<typeof summary>["status"];
    },
    signal: AbortSignal,
  ) => {
    if (!Number.isInteger(filters.page) || filters.page < 0)
      throw new TypeError("Invalid page");
    const query = new URLSearchParams({
      page: String(filters.page),
      size: String(filters.size ?? 20),
    });
    if (filters.requestType) query.set("requestType", filters.requestType);
    if (filters.status) query.set("status", filters.status);
    return read(client, "/admin/approvals", page, signal, query);
  },
  detail: (client: ApiClient, approvalId: string, signal: AbortSignal) => {
    if (!isApiId(approvalId)) throw new TypeError("Invalid approval ID");
    return read(client, `/admin/approvals/${approvalId}`, detail, signal);
  },
};
