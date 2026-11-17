package com.simulatedbank.banking.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.simulatedbank.banking.limit.application.boundaryfixture.InvalidApplicationDependency;
import com.simulatedbank.banking.limit.domain.boundaryfixture.InvalidDomainDependency;
import com.simulatedbank.banking.limit.adapter.in.boundaryfixture.InvalidInboundDependency;
import com.simulatedbank.banking.admin.application.boundaryfixture.InvalidCrossModuleDependency;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    static final ArchRule limitAndAdminApplicationUsePorts = noClasses()
            .that().resideInAnyPackage("..banking.limit.application..", "..banking.admin.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..banking.limit.adapter.out..", "..banking.admin.adapter.out..");

    @ArchTest
    static final ArchRule limitAndAdminDomainAreFrameworkFree = noClasses()
            .that().resideInAnyPackage("..banking.limit.domain..", "..banking.admin.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule limitAndAdminDoNotExposePersistence = noClasses()
            .that().resideInAnyPackage("..banking.limit.api..", "..banking.limit.adapter.in..", "..banking.admin.adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("..banking.limit.adapter.out..", "..banking.admin.adapter.out..", "jakarta.persistence..", "org.springframework.data.jpa..");

    private static final Set<String> MODULES = Set.of("identity", "customer", "account", "transfer", "ledger", "limit", "audit", "fraud", "notification", "admin");
    @ArchTest
    static final ArchRule limitAndAdminUseOtherModuleApis = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
            .that().resideInAnyPackage("..banking.limit..", "..banking.admin..")
            .should(new ArchCondition<>("use other business modules only through their api") {
                @Override public void check(JavaClass item, ConditionEvents events) {
                    String prefix = "com.simulatedbank.banking.";
                    String own = item.getPackageName().substring(prefix.length()).split("\\.")[0];
                    item.getDirectDependenciesFromSelf().forEach(dependency -> {
                        String target = dependency.getTargetClass().getPackageName();
                        if (!target.startsWith(prefix)) return;
                        String relative = target.substring(prefix.length());
                        String module = relative.split("\\.")[0];
                        if (MODULES.contains(module) && !module.equals(own)
                                && !relative.equals(module + ".api") && !relative.startsWith(module + ".api.")) {
                            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                        }
                    });
                }
            });

    @Test
    void portRuleRejectsAnActualApplicationToAdapterDependency() {
        var classes = new ClassFileImporter().importClasses(InvalidApplicationDependency.class, com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter.class);
        assertThatThrownBy(() -> limitAndAdminApplicationUsePorts.check(classes)).isInstanceOf(AssertionError.class);
    }

    @Test
    void remainingRulesRejectRealViolatingFixtures() {
        var importer = new ClassFileImporter();
        var domain = importer.importClasses(InvalidDomainDependency.class, jakarta.persistence.EntityManager.class);
        var inbound = importer.importClasses(InvalidInboundDependency.class, com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter.class);
        var crossModule = importer.importClasses(InvalidCrossModuleDependency.class, com.simulatedbank.banking.limit.adapter.out.LimitJpaAdapter.class);
        assertThatThrownBy(() -> limitAndAdminDomainAreFrameworkFree.check(domain)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> limitAndAdminDoNotExposePersistence.check(inbound)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> limitAndAdminUseOtherModuleApis.check(crossModule)).isInstanceOf(AssertionError.class);
    }
}
