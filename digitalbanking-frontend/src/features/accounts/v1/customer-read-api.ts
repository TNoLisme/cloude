import { z } from "zod";
import { ApiError, createApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";
import { isMoney } from "../../../shared/lib/money";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
const account = z.object({
  accountId: id,
  accountNumberMasked: z.string().regex(/^\*{4} \*{4} [0-9]{4}$/),
  accountNumberLast4: z.string().regex(/^[0-9]{4}$/),
  currency: z.literal("VND"),
  status: z.string(),
  availableBalance: z.string().refine(isMoney),
  ledgerBalance: z.string().refine(isMoney),
  asOf: instant,
});
const profile = z.object({
  customerId: id,
  userId: id,
  fullName: z.string(),
  phoneMasked: z.string().nullable().optional(),
  emailMasked: z.string().nullable().optional(),
  status: z.string(),
  createdAt: instant,
  updatedAt: instant,
});
const accounts = z.object({ items: z.array(account), asOf: instant });
const detail = account.extend({
  owner: z.object({ customerId: id, status: z.string() }),
  createdAt: instant,
  updatedAt: instant,
});

// Read-only contract adapter. The caller supplies transport; no UI or session
// is wired to the backend until GAP-05 is resolved.
export function createV1CustomerReadApi(config: {
  baseUrl: string;
  fetch?: typeof fetch;
}) {
  const url = new URL(config.baseUrl, "http://relative-base.invalid");
  if (url.pathname.replace(/\/$/, "") !== "/api/v1" || url.search || url.hash)
    throw new TypeError("Customer reader requires the explicit /api/v1 base");
  const client = createApiClient(config);
  async function read<T>(
    path: string,
    schema: z.ZodType<T>,
    signal?: AbortSignal,
  ) {
    const response = await client.request(path, { signal });
    const parsed = schema.safeParse(response.data);
    if (response.status !== 200 || !parsed.success)
      throw new ApiError("INVALID_RESPONSE", response);
    return parsed.data;
  }
  return {
    profile: (signal?: AbortSignal) => read("/customers/me", profile, signal),
    accounts: (signal?: AbortSignal) => read("/accounts", accounts, signal),
    detail: (accountId: string, signal?: AbortSignal) => {
      if (!isApiId(accountId)) throw new TypeError("Invalid account ID");
      return read(`/accounts/${accountId}`, detail, signal);
    },
    dispose: client.dispose,
  };
}
