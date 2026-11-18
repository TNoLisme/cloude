package com.simulatedbank.banking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.simulatedbank.banking.account.api.AccountBalance;
import com.simulatedbank.banking.account.api.AccountQuery;
import com.simulatedbank.banking.customer.api.CustomerQuery;
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

    @ArchTest
    static final ArchRule customerApiMustNotExposeEntitiesOrInternals =
            noClasses()
                    .that().resideInAPackage("..banking.customer.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "..banking.customer.adapter..",
                            "..banking.customer.application..",
                            "..banking.customer.domain.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-CUS-IMP-04 / BE-CON-14: public customer.api stays entity-free and self-contained");

    @ArchTest
    static final ArchRule customerInternalsMustNotBeConsumedByOtherModules =
            noClasses()
                    .that().resideOutsideOfPackage("..banking.customer..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.customer.adapter..",
                            "..banking.customer.application..",
                            "..banking.customer.domain.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-CUS-IMP-04 / BE-CON-12/13: consumers reach Customer only through customer.api");

    @ArchTest
    static final ArchRule customerMustNotReachCrossModuleInternals =
            noClasses()
                    .that().resideInAPackage("..banking.customer..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.identity.adapter..",
                            "..banking.identity.application..",
                            "..banking.identity.domain..",
                            "..banking.account.adapter..",
                            "..banking.account.application..",
                            "..banking.account.domain..",
                            "..banking.admin.adapter..",
                            "..banking.admin.application..",
                            "..banking.admin.domain..",
                            "..banking.audit.adapter..",
                            "..banking.audit.application..",
                            "..banking.audit.domain.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-CUS-IMP-07: Customer integrates only through common SPI or provider api");

    @ArchTest
    static final ArchRule customerQueryIsImplementedOnlyByItsApplicationLayer =
            classes()
                    .that().implement(CustomerQuery.class)
                    .should().resideInAPackage("..banking.customer.application..")
                    .allowEmptyShould(false)
                    .because("BE-CUS-IMP-04: exactly one application-owned implementation of the public contract");

    @ArchTest
    static final ArchRule accountApiMustDependOnJdkOrCommonOnly =
            noClasses()
                    .that().resideInAPackage("..banking.account.api..")
                    .should().dependOnClassesThat().resideOutsideOfPackages(
                            "java..",
                            "..banking.account.api..",
                            "..banking.common.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ACC-01 / BE-CON-43: account.api is an entity-free internal contract, "
                            + "only JDK types or common are allowed");

    @ArchTest
    static final ArchRule accountInternalsMustNotBeConsumedByOtherModules =
            noClasses()
                    .that().resideOutsideOfPackage("..banking.account..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.account.adapter..",
                            "..banking.account.application..",
                            "..banking.account.domain.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ACC-01 / BE-CON-12/13: consumers reach Account only through account.api");

    @ArchTest
    static final ArchRule accountPortsAreImplementedOnlyByItsApplicationLayer =
            classes()
                    .that().implement(AccountQuery.class)
                    .or().implement(AccountBalance.class)
                    .should().resideInAPackage("..banking.account.application..")
                    .allowEmptyShould(true)
                    .because("BE-ACC-01 / BE-CON-43: exactly one application-owned implementation of each port");

    @ArchTest
    static final ArchRule accountMustNotReachSiblingOrOrchestratorInternals =
            noClasses()
                    .that().resideInAPackage("..banking.account..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.transfer..",
                            "..banking.admin..",
                            "..banking.ledger..",
                            "..banking.limit..",
                            "..banking.fraud..",
                            "..banking.notification..",
                            "..banking.customer.adapter..",
                            "..banking.customer.application..",
                            "..banking.customer.domain..",
                            "..banking.identity.adapter..",
                            "..banking.identity.application..",
                            "..banking.identity.domain.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ACC-01 / BE-CON-09/10: account integrates only through provider api or common SPI");

    @ArchTest
    static final ArchRule customerApiMustNotCarryTextualPiiFields =
            fields()
                    .that().areDeclaredInClassesThat().resideInAPackage("..banking.customer.api..")
                    .should().notHaveRawType(String.class)
                    .allowEmptyShould(true)
                    .because("BE-CUS-IMP-04: CustomerQuery snapshot carries identifiers/status only, never textual PII");

}
