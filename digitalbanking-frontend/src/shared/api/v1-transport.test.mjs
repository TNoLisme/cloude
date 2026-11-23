import { readFileSync } from "node:fs";
import yaml from "js-yaml";
import { expect, it, vi } from "vitest";
import { createApiClient } from "./client";

const contract = yaml.load(
  readFileSync(
    new URL(
      "../../../../digitalbanking-docs/openapi/digital-banking-v1.yaml",
      import.meta.url,
    ),
    "utf8",
  ),
);
const example = (name) => contract.components.examples[name].value;

it("preserves the v1 422 replay body and both correlation IDs without retry", async () => {
  const body = example("TransferFailedExample");
  const fetcher = vi.fn().mockResolvedValue(
    Response.json(body, {
      status: 422,
      headers: {
        "X-Correlation-ID": "current-request",
        "Idempotent-Replayed": "true",
      },
    }),
  );
  const client = createApiClient({ baseUrl: "/api/v1", fetch: fetcher });
  await expect(client.request("/transfers")).rejects.toMatchObject({
    code: "TRANSFER_FAILED",
    meta: {
      status: 422,
      correlationId: "current-request",
      idempotentReplayed: true,
    },
    problem: {
      correlationId: "0f8fad5b-d9cb-469f-a165-70867728950e",
      transferStatus: "FAILED",
    },
  });
  expect(fetcher).toHaveBeenCalledOnce();
  client.dispose();
});

it("preserves the v1 428 challenge and 409 Retry-After without performing a retry", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json(example("StepUpRequiredExample"), { status: 428 }),
    )
    .mockResolvedValueOnce(
      Response.json(
        {
          ...example("ConflictExample"),
          code: "IDEMPOTENCY_IN_PROGRESS",
        },
        { status: 409, headers: { "Retry-After": "3" } },
      ),
    );
  const client = createApiClient({ baseUrl: "/api/v1", fetch: fetcher });
  await expect(client.request("/transfers")).rejects.toMatchObject({
    code: "STEP_UP_REQUIRED",
    meta: { status: 428 },
    problem: { challenge: { purpose: "TRANSFER_STEP_UP" } },
  });
  await expect(client.request("/transfers")).rejects.toMatchObject({
    code: "IDEMPOTENCY_IN_PROGRESS",
    meta: { status: 409, retryAfter: "3" },
  });
  expect(fetcher).toHaveBeenCalledTimes(2);
  client.dispose();
});
