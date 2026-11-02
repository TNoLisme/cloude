import { create } from "zustand";
import { session } from "./session";
import type { Schema } from "../api/client";
export type TransferResult =
  | Schema<"Transfer">
  | Schema<"TransferChallengeResponse">;
export type TransferIntent = {
  key: string;
  body: Omit<Schema<"CreateTransferRequest">, "pin">;
  recipient: Schema<"RecipientConfirmation">;
  sourceMasked: string;
};
type State = {
  intent: TransferIntent | null;
  result: TransferResult | null;
  stage: "review" | "unknown" | "otp" | "result";
  update: (state: Partial<Omit<State, "update" | "clear">>) => void;
  clear: () => void;
};
export const transferFlow = create<State>((set) => ({
  intent: null,
  result: null,
  stage: "review",
  update: (value) => set(value),
  clear: () => set({ intent: null, result: null, stage: "review" }),
}));
session.subscribe((next, prev) => {
  if (next.generation !== prev.generation) transferFlow.getState().clear();
});
export function nextTransferStage(result: TransferResult) {
  return result.status === "AWAITING_OTP"
    ? ("otp" as const)
    : ("result" as const);
}
