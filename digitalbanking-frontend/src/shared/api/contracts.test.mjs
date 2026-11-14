import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import yaml from "js-yaml";
import { describe, expect, it } from "vitest";

const read = (version) =>
  readFileSync(
    new URL(
      `../../../../digitalbanking-docs/openapi/digital-banking-${version}.yaml`,
      import.meta.url,
    ),
    "utf8",
  );
const v1 = yaml.load(read("v1"));
const v2 = yaml.load(read("v2"));
describe("separate v1/v2 contracts", () => {
  it("preserves the original v1 contract (independent of Git line endings)", () => {
    const hash = createHash("sha256")
      .update(read("v1").replace(/\r\n/g, "\n"))
      .digest("hex");
    expect(hash).toBe(
      "12338cee5a7b0ade0235ba204e8593ae7cbeaa2879cb3ba9681f7774371684e9",
    );
    expect(v1.components.securitySchemes.sessionCookie.name).toBe("DB_SESSION");
    expect(v1.components.schemas.ChallengePurpose.enum).toContain(
      "LOGIN_NEW_DEVICE",
    );
  });
  it("applies only the approved Security/version deltas across all operations", () => {
    const expected = JSON.parse(
      JSON.stringify(v1)
        .replaceAll("/api/v1", "/api/v2")
        .replaceAll("DB_SESSION", "SESSION_TOKEN")
        .replaceAll("LOGIN_NEW_DEVICE", "LOGIN_MFA"),
    );
    expected.info.title = v2.info.title;
    expected.info.version = "2.0.0";
    expected.info.description = v2.info.description;
    expected.components.headers.SetCookie.description =
      v2.components.headers.SetCookie.description;
    expect(v2).toEqual(expected);
    expect(v2.components.securitySchemes.sessionCookie.name).toBe(
      "SESSION_TOKEN",
    );
    expect(v2.components.schemas.ChallengePurpose.enum).toEqual([
      "LOGIN_MFA",
      "TRANSFER_STEP_UP",
      "SENSITIVE_ACTION",
    ]);
    expect(v2.components.headers.SetCookie.description).toContain("Path=/api");
    expect(v2.servers.every((server) => server.url.endsWith("/api/v2"))).toBe(
      true,
    );
    const operationIds = Object.values(v2.paths).flatMap((item) =>
      Object.values(item)
        .filter(
          (value) =>
            value && typeof value === "object" && "operationId" in value,
        )
        .map((value) => value.operationId),
    );
    expect(operationIds).toHaveLength(30);
    expect(new Set(operationIds).size).toBe(30);
  });
});
