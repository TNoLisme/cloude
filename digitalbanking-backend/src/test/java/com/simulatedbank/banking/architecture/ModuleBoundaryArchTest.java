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
                    .allowEmptyShould(true)
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

    @ArchTest
    static final ArchRule auditApplicationMustNotDependOnOutboundAdapters =
            noClasses()
                    .that().resideInAPackage("..banking.audit.application..")
                    .should().dependOnClassesThat().resideInAPackage("..banking.audit.adapter.out..")
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: audit application must not depend on concrete outbound adapters");

    @ArchTest
    static final ArchRule auditDomainMustRemainFrameworkFree =
            noClasses()
                    .that().resideInAPackage("..banking.audit.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "javax.sql.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2 / BE-CON-35: audit domain must remain free of frameworks/persistence");

    @ArchTest
    static final ArchRule fraudApplicationMustNotDependOnOutboundAdapters =
            noClasses()
                    .that().resideInAPackage("..banking.fraud.application..")
                    .should().dependOnClassesThat().resideInAPackage("..banking.fraud.adapter.out..")
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: fraud application must not depend on concrete outbound adapters");

    @ArchTest
    static final ArchRule notificationApplicationMustNotDependOnOutboundAdapters =
            noClasses()
                    .that().resideInAPackage("..banking.notification.application..")
                    .should().dependOnClassesThat().resideInAPackage("..banking.notification.adapter.out..")
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: notification application must not depend on concrete outbound adapters");

    @ArchTest
    static final ArchRule otherModulesMustNotImportAuditInternals =
            noClasses()
                    .that().resideOutsideOfPackage("..banking.audit..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.audit.application..",
                            "..banking.audit.domain..",
                            "..banking.audit.adapter.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: external modules must not depend on audit internals directly; consumers must use audit.api once approved");
}
