# Customer limits contract

`GET /api/v1/limits?accountId=<positive ID>` requires customer session and `LIMIT_READ_SELF`. No accountId returns only customer scope. With accountId resolve via AccountQuery and verify owner before selecting both scopes. Unknown account 404, foreign account 403, missing provider 503, invalid input 400. Response follows 07 §7.2.13 and §8.5.1, ID/money strings, explicit nullable fields, business date and instant. No writes or enforcement locks. Identity/account provider fakes only in tests; production is fail-closed.
