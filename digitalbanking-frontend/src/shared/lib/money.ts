import type { components } from "../api/generated/openapi";

export type Money = components["schemas"]["Money"];

// digital-banking-v1.yaml: Money (NUMERIC(19,2), nonnegative).
const moneyPattern = /^(0|[1-9][0-9]{0,16})\.[0-9]{2}$/;
const maximumMinorUnits = 10n ** 19n - 1n;
const integerFormatter = new Intl.NumberFormat("vi-VN", {
  maximumFractionDigits: 0,
});

export function isMoney(value: unknown): value is Money {
  return typeof value === "string" && moneyPattern.exec(value)?.[0] === value;
}

export function moneyToMinorUnits(value: unknown): bigint {
  if (!isMoney(value)) throw new TypeError("Invalid Money format");
  const [whole, fraction] = value.split(".");
  return BigInt(whole) * 100n + BigInt(fraction);
}

export function minorUnitsToMoney(value: bigint): Money {
  if (typeof value !== "bigint")
    throw new TypeError("Minor units must be bigint");
  if (value < 0n || value > maximumMinorUnits)
    throw new RangeError("Money exceeds contract precision");
  return `${value / 100n}.${String(value % 100n).padStart(2, "0")}`;
}

export function formatMoney(value: Money): string {
  const minor = moneyToMinorUnits(value);
  const integer = integerFormatter.format(minor / 100n);
  const fraction = String(minor % 100n).padStart(2, "0");
  return `${integer},${fraction} ₫`;
}
