package com.simulatedbank.banking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.simulatedbank.banking",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryArchTest {

    @ArchTest
    static final ArchRule commonMustNotDependOnBusinessModules =
            noClasses()
                    .that().resideInAPackage("..banking.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.identity..",
                            "..banking.customer..",
                            "..banking.account..",
                            "..banking.transfer..",
                            "..banking.ledger..",
                            "..banking.limit..",
                            "..banking.audit..",
                            "..banking.fraud..",
                            "..banking.notification..",
                            "..banking.admin.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-CON-08 / BE-CON-18: common module must not depend on any business module");

    @ArchTest
    static final ArchRule commonMustNotOwnPersistenceOrTransactionBoundaries =
            noClasses()
                    .that().resideInAPackage("..banking.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..",
                            "org.springframework.data.repository..",
                            "org.springframework.data.jpa..",
                            "org.springframework.transaction..",
                            "jakarta.persistence..",
                            "javax.sql.."
                    )
                    .because("06 §6.2–6.3: common owns no persistence or business transaction boundary");

    @ArchTest
    static final ArchRule domainMustRemainFrameworkFree =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..adapter..",
                            "org.springframework.web..",
                            "jakarta.servlet.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-CON-35: domain layer must not depend on adapters or web/servlet frameworks");

    @ArchTest
    static final ArchRule inboundAdapterMustNotAccessOutboundAdapterDirectly =
            noClasses()
                    .that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .allowEmptyShould(true)
                    .because("BE-CON-21: inbound adapters must go through application services, not outbound adapters");

    @ArchTest
    static final ArchRule modulesMustBeFreeOfCycles =
            slices()
                    .matching("com.simulatedbank.banking.(*)..")
                    .should().beFreeOfCycles()
                    .allowEmptyShould(true)
                    .because("BE-CON-11 / BE-CON-19: modules must not have circular dependencies");
}
