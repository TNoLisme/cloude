import { create } from "zustand";
import { session } from "./session";
import type { Schema } from "../api/client";
export type SeedIntent = {
  accountId: string;
  masked: string;
  key: string;
  body: Schema<"SeedBalanceRequest">;
  unknown: boolean;
};
export const operatorFlow = create<{
  seed: SeedIntent | null;
  setSeed: (seed: SeedIntent | null) => void;
}>((set) => ({ seed: null, setSeed: (seed) => set({ seed }) }));
session.subscribe((next, prev) => {
  if (next.generation !== prev.generation)
    operatorFlow.getState().setSeed(null);
});
