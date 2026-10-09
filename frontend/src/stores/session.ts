import { create } from "zustand";
import type { components } from "../api/generated/openapi";

export type User = components["schemas"]["UserSummary"];
export type Role = components["schemas"]["Role"];
type Session = {
  token: string | null;
  user: User | null;
  generation: number;
  accept: (token: string, user: User) => void;
  clear: () => void;
  pinConfigured: () => void;
};
export const session = create<Session>((set) => ({
  token: null,
  user: null,
  generation: 0,
  accept: (token, user) =>
    set((s) => ({
      token,
      user,
      generation: s.generation + (s.user?.userId === user.userId ? 0 : 1),
    })),
  clear: () =>
    set((s) => ({ token: null, user: null, generation: s.generation + 1 })),
  pinConfigured: () =>
    set((s) => ({ user: s.user ? { ...s.user, isPinSet: true } : null })),
}));
export function hasRole(user: User | null, roles: readonly Role[]) {
  return !!user && roles.some((r) => user.roles.includes(r));
}
export function workspaces(user: User | null) {
  return [
    hasRole(user, ["CUSTOMER"]) && {
      path: "/customer",
      label: "Khách hàng",
      detail: "Tài khoản và giao dịch cá nhân",
    },
    hasRole(user, ["OPERATOR", "ADMIN"]) && {
      path: "/staff/customers",
      label: "Operator",
      detail: "Tra cứu và hỗ trợ khách tại quầy",
    },
    hasRole(user, ["AUDITOR", "ADMIN"]) && {
      path: "/staff/audit",
      label: "Auditor",
      detail: "Đọc nhật ký kiểm toán",
    },
  ].filter((x): x is { path: string; label: string; detail: string } => !!x);
}
export function landing(user: User | null) {
  const areas = workspaces(user);
  return areas.length === 1
    ? areas[0].path === "/customer" && !user?.isPinSet
      ? "/customer/pin/setup"
      : areas[0].path
    : areas.length
      ? "/workspace"
      : "/forbidden";
}
