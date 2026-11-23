import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import { expect, it } from "vitest";

it("maps the three customer reads to the delivered v1 operation IDs", () => {
  const contract = yaml.load(
    readFileSync(
      new URL(
        "../../../../../digitalbanking-docs/openapi/digital-banking-v1.yaml",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  expect(contract.info.version).toBe("1.1.0");
  expect(contract.paths["/customers/me"].get.operationId).toBe("getMyProfile");
  expect(contract.paths["/accounts"].get.operationId).toBe("listAccounts");
  expect(contract.paths["/accounts/{accountId}"].get.operationId).toBe(
    "getAccount",
  );
});
