import { describe, expect, it } from "vitest";
import type { AuthResponse } from "./contracts";
import {
  canOpenCustomerDashboard,
  canReadStaffCustomerAccounts,
  canReadStaffCustomerProfile,
} from "./feature-policy";

type User = AuthResponse["user"];
const user = (userType: User["userType"], roles: User["roles"]): User => ({
  userId: "1",
  customerId: userType === "CUSTOMER" ? "2" : null,
  displayName: "Fixture",
  userType,
  roles,
  privileges: [],
});

describe("single-bank read display policy", () => {
  it("opens the customer dashboard only for a customer session", () => {
    expect(canOpenCustomerDashboard(user("CUSTOMER", ["CUSTOMER"]))).toBe(true);
    expect(canOpenCustomerDashboard(user("STAFF", ["CUSTOMER"]))).toBe(false);
    expect(canOpenCustomerDashboard(user("CUSTOMER", []))).toBe(false);
  });

  it.each([
    ["OPERATIONS", true, true],
    ["ADMIN", true, true],
    ["AUDITOR", false, true],
  ] as const)(
    "maps %s to profile=%s and accounts=%s",
    (role, profile, accounts) => {
      const principal = user("STAFF", [role]);
      expect(canReadStaffCustomerProfile(principal)).toBe(profile);
      expect(canReadStaffCustomerAccounts(principal)).toBe(accounts);
    },
  );

  it("fails closed for a customer or mixed customer/staff role snapshot", () => {
    for (const principal of [
      user("CUSTOMER", ["CUSTOMER", "ADMIN"]),
      user("STAFF", ["CUSTOMER", "ADMIN"]),
      user("STAFF", []),
    ]) {
      expect(canReadStaffCustomerProfile(principal)).toBe(false);
      expect(canReadStaffCustomerAccounts(principal)).toBe(false);
    }
  });
});
