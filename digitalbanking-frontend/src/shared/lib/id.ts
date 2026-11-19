import type { components } from "../api/generated/openapi";

export type ApiId = components["schemas"]["ID"];
const idPattern = /^[1-9][0-9]{0,18}$/;

export function isApiId(value: unknown): value is ApiId {
  return typeof value === "string" && idPattern.exec(value)?.[0] === value;
}

export function parseApiId(value: unknown): ApiId {
  if (!isApiId(value)) throw new TypeError("Invalid ID format");
  return value;
}
