import type { components } from "../shared/api/generated/v2/openapi";
import { account, profile } from "./fixtures";

// The v2 draft retains CustomerSummary; v1.1.0 AccountOwner omits fullName.
export const v2Account: components["schemas"]["AccountDetail"] = {
  ...account,
  owner: { ...account.owner, fullName: profile.fullName },
};
