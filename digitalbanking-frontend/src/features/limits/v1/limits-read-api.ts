import { z } from "zod";
import { ApiError, createApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";
import { isMoney } from "../../../shared/lib/money";

const id = z.string().refine(isApiId);
const money = z.string().refine(isMoney);
const instant = z.iso.datetime({ offset: true });
const responseSchema = z.object({
  customerId: id,
  currency: z.literal("VND"),
  usageDate: z.iso.date(),
  effectivePerTransactionLimit: money.nullable().optional(),
  effectiveRemainingDaily: money.nullable().optional(),
  items: z.array(
    z.object({
      limitId: id,
      scope: z.enum(["CUSTOMER", "ACCOUNT"]),
      accountId: id.nullable().optional(),
      perTransactionLimit: money,
      dailyLimit: money,
      currency: z.literal("VND"),
      usedToday: money,
      remainingDaily: money,
      effectiveFrom: instant,
      effectiveTo: instant.nullable().optional(),
      version: z.number().int().min(1),
    }),
  ),
  asOf: instant,
});

export function createV1LimitsReadApi(config: {
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
    throw new TypeError("Limits reader requires /api/v1");
  const client = createApiClient(config);
  return {
    get: async (accountId?: string, signal?: AbortSignal) => {
      if (accountId !== undefined && !isApiId(accountId))
        throw new TypeError("Invalid account ID");
      const query = accountId ? new URLSearchParams({ accountId }) : undefined;
      const response = await client.request("/limits", { query, signal });
      const parsed = responseSchema.safeParse(response.data);
      if (response.status !== 200 || !parsed.success)
        throw new ApiError("INVALID_RESPONSE", response);
      return parsed.data;
    },
    invalidate: client.invalidate,
    dispose: client.dispose,
  };
}

export type V1LimitsResponse = z.infer<typeof responseSchema>;
