import { describe, expect, it } from "vitest";
import {
  formatMoney,
  isMoney,
  minorUnitsToMoney,
  moneyToMinorUnits,
} from "./money";

describe("Money contract and exact arithmetic", () => {
  it.each([
    ["0.00", 0n],
    ["0.01", 1n],
    ["1.05", 105n],
    ["150000.00", 15000000n],
    ["99999999999999999.99", 9999999999999999999n],
  ])("round-trips %s without Number conversion", (value, minor) => {
    expect(moneyToMinorUnits(value)).toBe(minor);
    expect(minorUnitsToMoney(minor)).toBe(value);
  });

  it("adds decimal amounts exactly and serializes a two-digit scale", () => {
    expect(
      minorUnitsToMoney(moneyToMinorUnits("0.10") + moneyToMinorUnits("0.20")),
    ).toBe("0.30");
    expect(
      minorUnitsToMoney(moneyToMinorUnits("99999999999999999.98") + 1n),
    ).toBe("99999999999999999.99");
  });

  it.each([
    "1",
    "1.2",
    "1.230",
    "01.00",
    "-1.00",
    "+1.00",
    ".10",
    "1e2",
    "1,00",
    "1,000.00",
    " 1.00",
    "1.00 ",
    "100000000000000000.00",
    "NaN",
    "Infinity",
    "1.00\n",
    1,
    0,
    null,
    undefined,
    {},
  ])("rejects invalid Money rather than rounding/coercing: %j", (value) => {
    expect(isMoney(value)).toBe(false);
    expect(() => moneyToMinorUnits(value)).toThrow(TypeError);
  });

  it("rejects negative results and precision overflow", () => {
    expect(() => minorUnitsToMoney(-1n)).toThrow(RangeError);
    expect(() => minorUnitsToMoney(10n ** 19n)).toThrow(RangeError);
  });

  it("retains cents and large integer precision when formatting VND", () => {
    expect(formatMoney("150000.05")).toBe("150.000,05 ₫");
    expect(formatMoney("99999999999999999.99")).toBe(
      "99.999.999.999.999.999,99 ₫",
    );
    expect(formatMoney("0.00")).toBe("0,00 ₫");
  });
});
