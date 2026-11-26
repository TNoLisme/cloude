import { z } from "zod";
import { ApiError, createApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";
import { isMoney, moneyToMinorUnits } from "../../../shared/lib/money";

const instant = z.iso.datetime({ offset: true });
const reference = z.string().regex(/^TXN[0-9]{8}[A-Z0-9]{8,20}$/);
const masked = z.string().regex(/^\*{4} \*{4} [0-9]{4}$/);
const money = z.string().refine(isMoney);
const accountRef = z.object({
  accountId: z.string().refine(isApiId),
  accountNumberMasked: masked,
  accountNumberLast4: z.string().regex(/^[0-9]{4}$/),
});
const counterparty = z.object({
  displayName: z.string(),
  accountNumberMasked: masked,
});
const common = z.object({
  reference,
  direction: z.enum(["DEBIT", "CREDIT", "INTERNAL"]),
  amount: money,
  currency: z.literal("VND"),
  status: z.enum(["PENDING", "COMPLETED", "FAILED"]),
  counterparty,
  description: z.string().nullable().optional(),
  createdAt: instant,
  completedAt: instant.nullable().optional(),
});
const page = z.object({
  items: z.array(common),
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
const detail = common.extend({
  sourceAccount: accountRef,
  destinationAccount: accountRef,
  correlationId: z.string(),
  failedAt: instant.nullable().optional(),
  failureCode: z.string().nullable().optional(),
  failureMessage: z.string().nullable().optional(),
});

export type V1HistoryFilter = {
  page?: number;
  size?: number;
  accountId?: string;
  status?: "PENDING" | "COMPLETED" | "FAILED";
  direction?: "DEBIT" | "CREDIT" | "INTERNAL";
  from?: string;
  to?: string;
  minAmount?: string;
  maxAmount?: string;
};

export function createV1HistoryReadApi(config: {
  baseUrl: string;
  fetch?: typeof fetch;
  onSessionExpired?: () => void;
}) {
  const base = new URL(config.baseUrl, "http://relative-base.invalid");
  if (
    base.pathname.replace(/\/$/, "") !== "/api/v1" ||
    base.search ||
    base.hash
  )
    throw new TypeError("History reader requires /api/v1");
  const client = createApiClient(config);
  async function read<T>(
    path: string,
    schema: z.ZodType<T>,
    signal?: AbortSignal,
    query?: URLSearchParams,
  ) {
    const response = await client.request(path, { signal, query });
    const parsed = schema.safeParse(response.data);
    if (response.status !== 200 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    return parsed.data;
  }
  return {
    list: (filter: V1HistoryFilter = {}, signal?: AbortSignal) => {
      const pageNumber = filter.page ?? 0;
      const size = filter.size ?? 20;
      if (
        !Number.isInteger(pageNumber) ||
        pageNumber < 0 ||
        !Number.isInteger(size) ||
        size < 1 ||
        size > 100
      )
        throw new TypeError("Invalid page window");
      if (filter.accountId !== undefined && !isApiId(filter.accountId))
        throw new TypeError("Invalid account ID");
      if (filter.direction && !filter.accountId)
        throw new TypeError("Direction requires account ID");
      for (const value of [filter.from, filter.to])
        if (value !== undefined && !instant.safeParse(value).success)
          throw new TypeError("Invalid UTC range");
      if (
        filter.from &&
        filter.to &&
        Date.parse(filter.from) >= Date.parse(filter.to)
      )
        throw new TypeError("Invalid time range");
      for (const value of [filter.minAmount, filter.maxAmount])
        if (value !== undefined && !isMoney(value))
          throw new TypeError("Invalid amount");
      if (
        filter.minAmount &&
        filter.maxAmount &&
        moneyToMinorUnits(filter.minAmount) >
          moneyToMinorUnits(filter.maxAmount)
      )
        throw new TypeError("Invalid amount range");
      const query = new URLSearchParams({
        page: String(pageNumber),
        size: String(size),
      });
      for (const key of [
        "accountId",
        "status",
        "direction",
        "from",
        "to",
        "minAmount",
        "maxAmount",
      ] as const)
        if (filter[key] !== undefined) query.set(key, filter[key]);
      // No customerId or sort: customer self scope and default server order only.
      return read("/transactions", page, signal, query);
    },
    detail: (ref: string, signal?: AbortSignal) => {
      if (!reference.safeParse(ref).success)
        throw new TypeError("Invalid reference");
      return read(`/transactions/${ref}`, detail, signal);
    },
    invalidate: client.invalidate,
    dispose: client.dispose,
  };
}

export type V1TransactionPage = z.infer<typeof page>;
export type V1TransactionDetail = z.infer<typeof detail>;
