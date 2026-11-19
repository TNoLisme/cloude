import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";
import { isMoney } from "../../../shared/lib/money";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
const account = z.object({
  accountId: id,
  accountNumberMasked: z.string().regex(/^\*{4} \*{4} [0-9]{4}$/),
  accountNumberLast4: z.string().regex(/^[0-9]{4}$/),
  currency: z.literal("VND"),
  // Keep an unknown wire status for the UI fallback; never infer actions from it.
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
  owner: z.object({
    customerId: id,
    fullName: z.string(),
    status: z.string(),
  }),
  createdAt: instant,
  updatedAt: instant,
});
async function read<T>(
  client: ApiClient,
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
) {
  const response = await client.request(path, { signal });
  const parsed = schema.safeParse(response.data);
  if (response.status !== 200 || !parsed.success)
    throw new ApiError("INVALID_RESPONSE", response);
  return parsed.data;
}
export const customerApi = {
  profile: (client: ApiClient, signal: AbortSignal) =>
    read(client, "/customers/me", profile, signal),
  accounts: (client: ApiClient, signal: AbortSignal) =>
    read(client, "/accounts", accounts, signal),
  detail: (client: ApiClient, accountId: string, signal: AbortSignal) => {
    if (!isApiId(accountId)) throw new TypeError("Invalid account ID");
    return read(client, `/accounts/${accountId}`, detail, signal);
  },
};
