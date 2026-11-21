#!/bin/sh
# Run from digitalbanking-backend with JDK 21 and Docker. DEC-INT-007.
set -eu
./gradlew test --offline \
  --tests '*TransferT2PostgreSqlIntegrationTest' \
  --tests '*TransferT0T1PostgreSqlIntegrationTest' \
  --tests '*TransferRecoveryCandidatePostgresTest' \
  --tests '*TransferLimitPostgreSqlIntegrationTest' \
  --tests '*LedgerPostingPostgresTest' \
  --tests '*TransferRecoveryV24PostgreSqlIntegrationTest' \
  --tests '*PostgreSqlFreshMigrationIntegrationTest' \
  --tests '*AccountV8MigrationPostgreSqlIntegrationTest.freshMigration*' \
  --tests '*AccountV8MigrationPostgreSqlIntegrationTest.upgradeFromV7*' \
  --tests '*ApprovalRequestV6MigrationPostgreSqlIntegrationTest.freshMigration*' \
  --tests '*AdminIdempotencyV7MigrationPostgreSqlIntegrationTest.freshMigration*' \
  --tests '*ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest.upgrade*' \
  --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest.s01AndC01*' \
  --tests '*AdminMakerCheckerPostgreSqlIntegrationTest.replayAndMakerSeparation' \
  --console=plain
