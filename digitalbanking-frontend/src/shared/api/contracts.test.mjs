import { readFileSync } from "node:fs";
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
  it("uses the backend v1.1.0 session and masked account owner contract", () => {
    expect(v1.info.version).toBe("1.1.0");
    expect(v1.components.securitySchemes.sessionCookie.name).toBe("DB_SESSION");
    expect(v1.components.schemas.ChallengePurpose.enum).toContain(
      "LOGIN_NEW_DEVICE",
    );
    expect(v1.components.schemas.AccountOwner.properties).toHaveProperty(
      "customerId",
    );
    expect(v1.components.schemas.AccountOwner.properties).not.toHaveProperty(
      "fullName",
    );
  });
  it("keeps the historical v2 draft separate from the newer v1 contract", () => {
    expect(v2.info.version).toBe("2.0.0");
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
