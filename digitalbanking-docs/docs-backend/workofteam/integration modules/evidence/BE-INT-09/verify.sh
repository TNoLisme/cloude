#!/usr/bin/env sh
set -eu

# Run from digitalbanking-backend with JDK 21 and Docker. DEC-INT-007: explicit affected classes only.
# --no-daemon avoids reusing a daemon created before an in-place JDK update.
./gradlew --offline --no-daemon test \
  --tests '*AdminMakerCheckerPostgreSqlIntegrationTest' \
  --tests '*AdminAc028AcceptancePostgreSqlIntegrationTest' \
  --tests '*ApprovalDecisionHttpPostgreSqlIntegrationTest' \
  --tests '*LimitApprovalHttpPostgreSqlIntegrationTest' \
  --tests '*ApprovalStepUpPostgreSqlIntegrationTest' \
  --tests '*ApprovalProposalPostgreSqlIntegrationTest' \
  --tests '*ApprovalDecisionIdempotencyPostgreSqlIntegrationTest' \
  --tests '*ApprovalRequestPostgreSqlIntegrationTest' \
  --tests '*RoleChangePostgreSqlIntegrationTest' \
  --tests '*LimitConfigMutationPostgreSqlIntegrationTest' \
  --tests '*AuditModulePostgreSqlIntegrationTest' \
  --tests '*AdminOpenApiContractTest' \
  --tests '*IdentityRoleLockV23PostgreSqlIntegrationTest' \
  --tests '*PostgreSqlFreshMigrationIntegrationTest' \
  --tests '*AccountV8MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalRequestV6MigrationPostgreSqlIntegrationTest' \
  --tests '*AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest' \
  --tests '*ModuleBoundaryArchTest' \
  --tests '*AuditBoundaryArchTest' \
  --console=plain --max-workers=1
