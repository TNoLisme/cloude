package com.simulatedbank.banking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.simulatedbank.banking",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AuditBoundaryArchTest {

    @ArchTest
    static final ArchRule applicationMustNotDependOnOutboundAdapters =
            noClasses()
                    .that().resideInAPackage("..banking.audit.application..")
                    .should().dependOnClassesThat().resideInAPackage("..banking.audit.adapter.out..")
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: audit application must use outbound ports, not concrete adapters");

    @ArchTest
    static final ArchRule domainMustRemainFrameworkFree =
            noClasses()
                    .that().resideInAPackage("..banking.audit.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "jakarta.servlet..",
                            "javax.sql..",
                            "..banking.audit.adapter.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2 / BE-CON-35: audit domain must remain framework and adapter free");

    @ArchTest
    static final ArchRule externalModulesMustNotImportAuditInternals =
            noClasses()
                    .that().resideOutsideOfPackage("..banking.audit..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..banking.audit.application..",
                            "..banking.audit.domain..",
                            "..banking.audit.adapter.."
                    )
                    .allowEmptyShould(true)
                    .because("BE-ARCH-00 §4.2: external modules must use approved audit.api contracts");
}
