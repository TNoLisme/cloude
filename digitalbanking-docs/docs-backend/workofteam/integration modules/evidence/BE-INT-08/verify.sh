#!/usr/bin/env sh
set -eu

# Run from digitalbanking-backend with JDK 21 and Docker.
./gradlew --offline test \
  --tests '*TransferT2PostgreSqlIntegrationTest' \
  --tests '*TransferT0T1PostgreSqlIntegrationTest' \
  --tests '*OtpChallengeServiceTest' \
  --tests '*OtpDispatchServiceTest' \
  --tests '*OtpDeliveryPostgreSqlIntegrationTest' \
  --tests '*IdentityOpenApiContractTest' \
  --tests '*SecurityLogAuditAdapterTest' \
  --tests '*NotificationConfigurationTest' \
  --tests '*NotificationTransactionalOutboxIT' \
  --tests '*NotificationPayloadValidatorTest' \
  --tests '*NotificationOutboxMetricsTest' \
  --tests '*PostgreSqlFreshMigrationIntegrationTest' \
  --tests '*AccountV8MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalRequestV6MigrationPostgreSqlIntegrationTest' \
  --tests '*AdminIdempotencyV7MigrationPostgreSqlIntegrationTest' \
  --tests '*ApprovalPrivilegesV18MigrationPostgreSqlIntegrationTest' \
  --tests '*FraudV21MigrationPostgreSqlIntegrationTest' \
  --tests '*SessionCustomerAccountReadPostgreSqlIntegrationTest' \
  --tests '*TransferBoundaryTest' \
  --tests '*ModuleBoundaryArchTest' \
  --console=plain
