import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isMoney } from "../../../shared/lib/money";

const reference = z.string().regex(/^TXN[0-9]{8}[A-Z0-9]{8,20}$/);
const id = z.string().regex(/^[1-9][0-9]{0,18}$/);
const instant = z.iso.datetime({ offset: true });
const masked = z.string().regex(/^\*{4} \*{4} [0-9]{4}$/);
const item = z.object({
  reference,
  direction: z.string(),
  amount: z.string().refine(isMoney),
  currency: z.literal("VND"),
  status: z.string(),
  counterparty: z.object({
    displayName: z.string(),
    accountNumberMasked: masked,
  }),
  description: z.string().nullable().optional(),
  createdAt: instant,
  completedAt: instant.nullable().optional(),
});
const page = z.object({
  items: z.array(item),
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
const detail = item.extend({
  sourceAccount: z.object({
    accountId: id,
    accountNumberMasked: masked,
    accountNumberLast4: z.string().regex(/^[0-9]{4}$/),
  }),
  destinationAccount: z.object({
    accountId: id,
    accountNumberMasked: masked,
    accountNumberLast4: z.string().regex(/^[0-9]{4}$/),
  }),
  correlationId: z.string(),
  failedAt: instant.nullable().optional(),
  failureCode: z.string().nullable().optional(),
  failureMessage: z.string().nullable().optional(),
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
export const historyApi = {
  list: (
    client: ApiClient,
    filters: {
      page: number;
      size?: number;
      status?: string;
      accountId?: string;
      direction?: string;
      from?: string;
      to?: string;
      minAmount?: string;
      maxAmount?: string;
    },
    signal: AbortSignal,
  ) => {
    const query = new URLSearchParams({
      page: String(filters.page),
      size: String(filters.size ?? 20),
    });
    for (const key of [
      "status",
      "accountId",
      "direction",
      "from",
      "to",
      "minAmount",
      "maxAmount",
    ] as const)
      if (filters[key]) query.set(key, filters[key]);
    return read(client, "/transactions", page, signal, query);
  },
  detail: (client: ApiClient, ref: string, signal: AbortSignal) => {
    if (!reference.safeParse(ref).success)
      throw new TypeError("Invalid reference");
    return read(
      client,
      `/transactions/${encodeURIComponent(ref)}`,
      detail,
      signal,
    );
  },
};
