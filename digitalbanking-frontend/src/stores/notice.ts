import { create } from "zustand";
export const notice = create<{ text: string; set: (text: string) => void }>(
  (set) => ({ text: "", set: (text) => set({ text }) }),
);
