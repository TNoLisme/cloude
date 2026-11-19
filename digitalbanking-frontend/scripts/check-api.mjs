import { readFile, rm } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const versions = process.argv[2] ? [process.argv[2]] : ["v1", "v2"];
if (versions.some((version) => !["v1", "v2"].includes(version)))
  throw new Error("Supported contracts: v1, v2");
for (const version of versions) {
  const directory =
    version === "v1"
      ? "src/shared/api/generated"
      : "src/shared/api/generated/v2";
  const generated = `${directory}/openapi.d.ts`;
  const temporary = `${directory}/openapi.check.d.ts`;
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
        `../digitalbanking-docs/openapi/digital-banking-${version}.yaml`,
        "-o",
        temporary,
      ],
      { stdio: "inherit" },
    );
    if (
      (await readFile(generated, "utf8")).replace(/\r\n/g, "\n") !==
      (await readFile(temporary, "utf8")).replace(/\r\n/g, "\n")
    )
      throw new Error("Generated API types differ. Run pnpm api:generate.");
    console.log(`OpenAPI ${version} generated types match the contract.`);
  } finally {
    await rm(temporary, { force: true });
  }
}
