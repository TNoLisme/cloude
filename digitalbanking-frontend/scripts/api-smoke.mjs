import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

// Run only against the separately created disposable QA backend, never retained data.
const base = process.env.UI_QA_API_BASE;
const guard = process.env.OTP_MAILBOX_GUARD_TOKEN;
const password = process.env.UI_QA_PASSWORD;
if (!base || !guard || !password || process.env.UI_QA_DISPOSABLE !== "yes")
  throw new Error(
    "Explicit disposable QA environment and credentials are required.",
  );
const results = [];
async function check(name, work) {
  await work();
  results.push(name);
  console.log(`PASS ${name}`);
}
async function call(
  path,
  body,
  token,
  headers = {},
  method = body === undefined ? "GET" : "POST",
) {
  const response = await fetch(base + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const data = response.status === 204 ? null : await response.json();
  return { status: response.status, data, headers: response.headers };
}
function ok(response, statuses = [200]) {
  assert.ok(
    statuses.includes(response.status),
    `Unexpected status ${response.status}, code ${response.data?.code || "none"}`,
  );
  return response.data;
}
async function mailbox(identifier) {
  return ok(
    await call(
      `/__local/otp-mailbox?identifier=${encodeURIComponent(identifier)}`,
      undefined,
      undefined,
      { "X-Local-Mailbox-Token": guard },
    ),
  ).code;
}
const operator = ok(
  await call("/auth/login", { phone: "0900000001", password }),
);
const auditor = ok(
  await call("/auth/login", { phone: "0900000002", password }),
);
const source = ok(await call("/auth/login", { phone: "0900000003", password }));
const destination = ok(
  await call("/auth/login", { phone: "0900000004", password }),
);
const sourceAccount = ok(await call("/accounts", undefined, source.accessToken))
  .items[0];
const destinationAccount = ok(
  await call("/accounts", undefined, destination.accessToken),
).items[0];
function assertMaskedAccount(account) {
  assert.deepEqual(Object.keys(account).sort(), [
    "accountId",
    "accountNumberMasked",
    "accountType",
    "balance",
    "currency",
    "openedAt",
    "status",
  ]);
  assert.ok(
    typeof account.accountId === "string" &&
      /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(account.accountId),
    "Account id must be a UUID",
  );
  assert.ok(
    typeof account.accountNumberMasked === "string" &&
      /^••••\d{4}$/.test(account.accountNumberMasked),
    "Lookup must return only the masked account number",
  );
  assert.equal(account.accountType, "CHECKING");
  assert.ok(["ACTIVE", "BLOCKED", "CLOSED"].includes(account.status));
  assert.ok(
    typeof account.balance === "string" &&
      /^(0|[1-9][0-9]{0,14})$/.test(account.balance),
    "Balance must remain an integer money string",
  );
  assert.equal(account.currency, "VND");
  assert.ok(
    typeof account.openedAt === "string" &&
      account.openedAt.endsWith("Z") &&
      Number.isFinite(Date.parse(account.openedAt)),
    "Opened at must remain a UTC timestamp",
  );
}
await check(
  "API authorization: customer cannot use operator/audit, auditor cannot seed, ownership checked",
  async () => {
    assert.equal(
      (
        await call(
          "/operator/customers?phone=0900000003",
          undefined,
          source.accessToken,
        )
      ).status,
      403,
    );
    assert.equal(
      (await call("/audit-events", undefined, source.accessToken)).status,
      403,
    );
    assert.equal(
      (
        await call(
          "/operator/customers?phone=0900000003",
          undefined,
          auditor.accessToken,
        )
      ).status,
      403,
    );
    assert.equal(
      (
        await call(
          `/accounts/${sourceAccount.accountId}`,
          undefined,
          destination.accessToken,
        )
      ).status,
      403,
    );
    assert.equal(
      (
        await call(
          `/operator/accounts/${sourceAccount.accountId}/seed-balance`,
          { amount: "10", currency: "VND", reference: "QA" },
          auditor.accessToken,
          { "Idempotency-Key": randomUUID() },
        )
      ).status,
      403,
    );
  },
);
await check(
  "Operator masked lookup by phone/email, seed and same-key replay",
  async () => {
    const lookup = ok(
      await call(
        "/operator/customers?phone=0900000003",
        undefined,
        operator.accessToken,
      ),
    );
    const byEmail = ok(
      await call(
        `/operator/customers?email=${encodeURIComponent(lookup.email)}`,
        undefined,
        operator.accessToken,
      ),
    );
    for (const customer of [lookup, byEmail]) {
      assert.ok(Array.isArray(customer.accounts));
      customer.accounts.forEach(assertMaskedAccount);
      assert.ok(
        !JSON.stringify(customer).includes('"accountNumber":'),
        "Lookup response must not expose raw account numbers",
      );
      assert.equal(typeof customer.isPinSet, "boolean");
      assert.equal(customer.customerId, lookup.customerId);
      assert.equal(customer.accounts[0].accountId, sourceAccount.accountId);
      assert.equal(
        customer.accounts[0].accountNumberMasked,
        sourceAccount.accountNumberMasked,
      );
    }
    const key = randomUUID();
    const body = {
      amount: "50000000",
      currency: "VND",
      reference: "Disposable UI QA",
    };
    const first = await call(
      `/operator/accounts/${sourceAccount.accountId}/seed-balance`,
      body,
      operator.accessToken,
      { "Idempotency-Key": key },
    );
    ok(first, [201]);
    const replay = await call(
      `/operator/accounts/${sourceAccount.accountId}/seed-balance`,
      body,
      operator.accessToken,
      { "Idempotency-Key": key },
    );
    ok(replay);
    assert.equal(replay.headers.get("Idempotency-Replayed"), "true");
    assert.equal(replay.data.balanceAfter, first.data.balanceAfter);
  },
);
async function create(amount) {
  return await call(
    "/transfers",
    {
      sourceAccountId: sourceAccount.accountId,
      destinationAccountId: destinationAccount.accountId,
      amount,
      currency: "VND",
      pin: "000001",
      memo: "Disposable UI QA",
    },
    source.accessToken,
    { "Idempotency-Key": randomUUID() },
  );
}
await check(
  "Transfer exactly 5M completes; 5,000,001 awaits server OTP; wrong OTP does not debit",
  async () => {
    assert.equal(ok(await create("5000000"), [201]).status, "COMPLETED");
    const challenge = ok(await create("5000001"));
    assert.equal(challenge.status, "AWAITING_OTP");
    const before = ok(await call("/accounts", undefined, source.accessToken))
      .items[0].balance;
    const wrong =
      (await mailbox("0900000003")) === "999999" ? "999998" : "999999";
    const invalid = await call(
      `/transfers/${challenge.transferId}/confirm-otp`,
      { otp: wrong },
      source.accessToken,
    );
    assert.equal(invalid.status, 400);
    assert.equal(invalid.data.code, "OTP_INVALID");
    assert.equal(
      ok(await call("/accounts", undefined, source.accessToken)).items[0]
        .balance,
      before,
    );
    const confirmed = await call(
      `/transfers/${challenge.transferId}/confirm-otp`,
      { otp: await mailbox("0900000003") },
      source.accessToken,
    );
    assert.equal(ok(confirmed, [201]).status, "COMPLETED");
    const replay = await call(
      `/transfers/${challenge.transferId}/confirm-otp`,
      { otp: "999999" },
      source.accessToken,
    );
    assert.equal(ok(replay).status, "COMPLETED");
    assert.equal(replay.headers.get("Idempotency-Replayed"), "true");
    assert.equal(
      BigInt(before) -
        BigInt(
          ok(await call("/accounts", undefined, source.accessToken)).items[0]
            .balance,
        ),
      5000001n,
    );
  },
);
await check(
  "Operator block prevents transfers and unblock restores eligibility",
  async () => {
    assert.equal(
      ok(
        await call(
          `/operator/accounts/${destinationAccount.accountId}/block`,
          { reason: "Disposable UI QA block" },
          operator.accessToken,
        ),
      ).status,
      "BLOCKED",
    );
    assert.equal((await create("2000")).data.code, "ACCOUNT_NOT_ELIGIBLE");
    assert.equal(
      ok(
        await call(
          `/operator/accounts/${destinationAccount.accountId}/unblock`,
          { reason: "Disposable UI QA unblock" },
          operator.accessToken,
        ),
      ).status,
      "ACTIVE",
    );
  },
);
await check(
  "History is cursor based; recipient only sees completed; audit/risk use permitted roles",
  async () => {
    const history = ok(
      await call("/transfers?limit=1", undefined, source.accessToken),
    );
    assert.equal(history.items.length, 1);
    assert.ok(history.nextCursor);
    assert.ok(
      ok(
        await call("/transfers", undefined, destination.accessToken),
      ).items.every((row) => row.status === "COMPLETED"),
    );
    assert.ok(
      ok(await call("/audit-events", undefined, auditor.accessToken)).items
        .length,
    );
    assert.ok(
      ok(
        await call("/operator/risk-flags", undefined, operator.accessToken),
      ).items.some((row) => row.ruleId === "LARGE_TRANSFER"),
    );
  },
);
const phone = `09${String(Date.now()).slice(-8)}`;
const email = `${randomUUID()}@example.test`;
let registered;
await check(
  "Separate registration verification: no token without OTP; token is phone bound and one use",
  async () => {
    ok(
      await call("/auth/register/send-otp", { phone, purpose: "REGISTRATION" }),
    );
    const code = await mailbox(phone);
    const wrong = code === "999999" ? "999998" : "999999";
    assert.equal(
      (await call("/auth/register/verify-otp", { phone, otp: wrong })).data
        .code,
      "OTP_INVALID",
    );
    const proof = ok(
      await call("/auth/register/verify-otp", { phone, otp: code }),
    );
    assert.equal(proof.expiresInSeconds, 300);
    const body = {
      phone,
      email,
      fullName: "Khách QA",
      password,
      registrationToken: proof.registrationToken,
    };
    assert.equal(
      (await call("/auth/register", { ...body, phone: "0900000099" })).data
        .code,
      "REGISTRATION_TOKEN_INVALID",
    );
    registered = ok(await call("/auth/register", body), [201]);
    assert.equal(registered.account.balance, "0");
    assert.equal(
      (await call("/auth/register", body)).data.code,
      "REGISTRATION_TOKEN_INVALID",
    );
  },
);
await check(
  "New customer login, PIN setup, operator seed, transfer and history",
  async () => {
    const customer = ok(await call("/auth/login", { phone, password }));
    assert.equal(customer.user.isPinSet, false);
    ok(
      await call(
        "/customers/me/pin/setup",
        { pin: "000003", confirmPin: "000003" },
        customer.accessToken,
      ),
    );
    assert.equal(
      ok(await call("/customers/me", undefined, customer.accessToken)).isPinSet,
      true,
    );
    const account = ok(await call("/accounts", undefined, customer.accessToken))
      .items[0];
    ok(
      await call(
        `/operator/accounts/${account.accountId}/seed-balance`,
        {
          amount: "2000",
          currency: "VND",
          reference: "Disposable new customer QA",
        },
        operator.accessToken,
        { "Idempotency-Key": randomUUID() },
      ),
      [201],
    );
    const transfer = ok(
      await call(
        "/transfers",
        {
          sourceAccountId: account.accountId,
          destinationAccountId: sourceAccount.accountId,
          amount: "2000",
          currency: "VND",
          pin: "000003",
        },
        customer.accessToken,
        { "Idempotency-Key": randomUUID() },
      ),
      [201],
    );
    assert.equal(transfer.status, "COMPLETED");
    assert.equal(
      ok(await call("/accounts", undefined, customer.accessToken)).items[0]
        .balance,
      "0",
    );
    assert.ok(
      ok(await call("/transfers", undefined, customer.accessToken)).items.some(
        (row) => row.transferId === transfer.transferId,
      ),
    );
  },
);
await check(
  "Requesting new registration OTP invalidates the previous verified proof",
  async () => {
    const nextPhone = `08${String(Date.now()).slice(-8)}`;
    ok(
      await call("/auth/register/send-otp", {
        phone: nextPhone,
        purpose: "REGISTRATION",
      }),
    );
    const proof = ok(
      await call("/auth/register/verify-otp", {
        phone: nextPhone,
        otp: await mailbox(nextPhone),
      }),
    );
    ok(
      await call("/auth/register/send-otp", {
        phone: nextPhone,
        purpose: "REGISTRATION",
      }),
    );
    assert.equal(
      (
        await call("/auth/register", {
          phone: nextPhone,
          email: `${randomUUID()}@example.test`,
          fullName: "Proof retirement QA",
          password,
          registrationToken: proof.registrationToken,
        })
      ).data.code,
      "REGISTRATION_TOKEN_INVALID",
    );
  },
);
await check(
  "Recovery anti-enumeration and separate verification/reset, one-use reset token",
  async () => {
    const missing = ok(
      await call("/auth/recover/initiate", {
        identifier: "missing-qa@example.test",
        channel: "EMAIL",
      }),
    );
    const known = ok(
      await call("/auth/recover/initiate", {
        identifier: email,
        channel: "EMAIL",
      }),
    );
    assert.equal(known.message, missing.message);
    const proof = ok(
      await call("/auth/recover/verify", {
        identifier: email,
        channel: "EMAIL",
        otp: await mailbox(email),
      }),
    );
    assert.equal(proof.expiresInSeconds, 300);
    ok(
      await call("/auth/recover/confirm", {
        resetToken: proof.resetToken,
        newPassword: password + "-reset",
      }),
    );
    assert.equal(
      (
        await call("/auth/recover/confirm", {
          resetToken: proof.resetToken,
          newPassword: password,
        })
      ).data.code,
      "RECOVERY_TOKEN_INVALID",
    );
  },
);
await check(
  "Operator creates a customer with a real customer OTP and a masked zero-balance account",
  async () => {
    const counterPhone = `07${String(Date.now()).slice(-8)}`;
    ok(
      await call(
        "/operator/customers/send-otp",
        { phone: counterPhone },
        operator.accessToken,
      ),
    );
    const customer = ok(
      await call(
        "/operator/customers",
        {
          phone: counterPhone,
          email: `${randomUUID()}@example.test`,
          fullName: "Counter QA",
          initialPassword: password,
          otp: await mailbox(counterPhone),
        },
        operator.accessToken,
      ),
      [201],
    );
    assert.equal(customer.account.balance, "0");
    assert.ok(customer.account.accountNumberMasked.startsWith("••••"));
    assert.equal(customer.account.accountNumber, undefined);
  },
);
await check(
  "PIN change and OTP reset use the real API and preserve the demo fixture PIN",
  async () => {
    ok(
      await call(
        "/customers/me/pin/change",
        { currentPin: "000002", newPin: "000004", confirmNewPin: "000004" },
        destination.accessToken,
      ),
    );
    ok(
      await call(
        "/customers/me/pin/forgot/initiate",
        undefined,
        destination.accessToken,
        {},
        "POST",
      ),
    );
    ok(
      await call(
        "/customers/me/pin/forgot/confirm",
        {
          otp: await mailbox("0900000004"),
          newPin: "000002",
          confirmNewPin: "000002",
        },
        destination.accessToken,
      ),
    );
    assert.equal(
      ok(await call("/customers/me", undefined, destination.accessToken))
        .isPinSet,
      true,
    );
  },
);
await check(
  "SMS recovery verifies and confirms using the existing backend contract",
  async () => {
    const identifier = "0900000002";
    ok(await call("/auth/recover/initiate", { identifier, channel: "SMS" }));
    const proof = ok(
      await call("/auth/recover/verify", {
        identifier,
        channel: "SMS",
        otp: await mailbox(identifier),
      }),
    );
    assert.equal(proof.expiresInSeconds, 300);
    ok(
      await call("/auth/recover/confirm", {
        resetToken: proof.resetToken,
        newPassword: password,
      }),
    );
  },
);
console.log(
  `API smoke complete: ${results.length} scenarios passed against disposable PostgreSQL.`,
);
