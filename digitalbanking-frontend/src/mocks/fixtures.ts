import type { components } from "../shared/api/generated/openapi";

type Schema<K extends keyof components["schemas"]> = components["schemas"][K];
export const fixtureTime = "2026-11-13T03:00:00Z";
export const profile: Schema<"CustomerProfile"> = {
  customerId: "2001",
  userId: "1001",
  fullName: "Nguyễn Văn A",
  status: "ACTIVE",
  phoneMasked: "*******123",
  emailMasked: "n***@example.test",
  createdAt: fixtureTime,
  updatedAt: fixtureTime,
};
export const account: Schema<"AccountDetail"> = {
  accountId: "3001",
  accountNumberMasked: "**** **** 3001",
  accountNumberLast4: "3001",
  currency: "VND",
  status: "ACTIVE",
  availableBalance: "25000000.00",
  ledgerBalance: "25000000.00",
  asOf: fixtureTime,
  owner: { customerId: "2001", fullName: profile.fullName, status: "ACTIVE" },
  createdAt: fixtureTime,
  updatedAt: fixtureTime,
};
export const accounts: Schema<"AccountsResponse"> = {
  items: [account],
  asOf: fixtureTime,
};
export function authFixture(): Schema<"AuthResponse"> {
  return {
    user: {
      userId: "1001",
      userType: "CUSTOMER",
      customerId: "2001",
      displayName: profile.fullName,
      roles: ["CUSTOMER"],
      privileges: [],
    },
    session: {
      idleExpiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      absoluteExpiresAt: new Date(Date.now() + 8 * 60 * 60_000).toISOString(),
      csrfToken: "mock-only-csrf",
      deviceTrusted: true,
    },
    correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
  };
}
