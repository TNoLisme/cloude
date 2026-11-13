import { readFile, rm } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const generated = "src/shared/api/generated/openapi.d.ts";
const temporary = "src/shared/api/generated/openapi.check.d.ts";
try {
  execFileSync(
    process.execPath,
    [
      fileURLToPath(
        new URL(
          "../node_modules/openapi-typescript/bin/cli.js",
          import.meta.url,
        ),
      ),
      "../digitalbanking-docs/openapi/digital-banking-v1.yaml",
      "-o",
      temporary,
    ],
    { stdio: "inherit" },
  );
  if (
    (await readFile(generated, "utf8")) !== (await readFile(temporary, "utf8"))
  )
    throw new Error("Generated API types differ. Run pnpm api:generate.");
  console.log("OpenAPI generated types match the contract.");
} finally {
  await rm(temporary, { force: true });
}
