import { z } from "zod";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { isApiId } from "../../../shared/lib/id";
import { isMoney } from "../../../shared/lib/money";

const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
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
const accounts = z.object({ items: z.array(account), asOf: instant });

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

export const staffCustomerApi = {
  profile: (client: ApiClient, customerId: string, signal: AbortSignal) => {
    if (!isApiId(customerId)) throw new TypeError("Invalid customer ID");
    return read(client, `/admin/customers/${customerId}`, profile, signal);
  },
  accounts: (client: ApiClient, customerId: string, signal: AbortSignal) => {
    if (!isApiId(customerId)) throw new TypeError("Invalid customer ID");
    return read(
      client,
      `/admin/customers/${customerId}/accounts`,
      accounts,
      signal,
    );
  },
};
