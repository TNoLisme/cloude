import { describe, expect, it } from "vitest";
import { isApiId, parseApiId } from "./id";

describe("ID contract", () => {
  it.each([
    "1",
    "3001",
    "9007199254740993",
    "9223372036854775807",
    "9999999999999999999",
  ])("preserves the exact string %s", (value) => {
    expect(isApiId(value)).toBe(true);
    expect(parseApiId(value)).toBe(value);
  });
  it.each([
    "0",
    "01",
    "-1",
    "1.0",
    "1e3",
    "10000000000000000000",
    " 1",
    "1\n",
    3001,
    9007199254740992,
    null,
    undefined,
    {},
  ])("rejects invalid ID without coercion: %j", (value) => {
    expect(isApiId(value)).toBe(false);
    expect(() => parseApiId(value)).toThrow(TypeError);
  });
});
