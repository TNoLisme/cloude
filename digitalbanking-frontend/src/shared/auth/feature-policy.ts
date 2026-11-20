import type { AuthResponse } from "./contracts";

type User = AuthResponse["user"];

// FE display policy for the read-only single-bank MVP slice in 07 §3.4–3.6.
// A 403 from the server remains authoritative when assignments change.
export function canOpenCustomerDashboard(user: User): boolean {
  return user.userType === "CUSTOMER" && user.roles.includes("CUSTOMER");
}

export function canReadStaffCustomerProfile(user: User): boolean {
  return (
    user.userType === "STAFF" &&
    !user.roles.includes("CUSTOMER") &&
    (user.roles.includes("OPERATIONS") || user.roles.includes("ADMIN"))
  );
}

export function canReadStaffCustomerAccounts(user: User): boolean {
  return (
    user.userType === "STAFF" &&
    !user.roles.includes("CUSTOMER") &&
    (user.roles.includes("OPERATIONS") ||
      user.roles.includes("ADMIN") ||
      user.roles.includes("AUDITOR"))
  );
}
