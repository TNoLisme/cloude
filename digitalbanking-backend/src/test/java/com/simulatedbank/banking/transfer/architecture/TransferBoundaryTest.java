package com.simulatedbank.banking.transfer.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.account.api.BoundaryPublicFixture;
import com.simulatedbank.banking.account.domain.BoundaryPrivateFixture;
import com.simulatedbank.banking.transfer.boundaryfixture.BadConsumerFixture;
import com.simulatedbank.banking.transfer.boundaryfixture.GoodConsumerFixture;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Enforces Transfer's side of the provider-public-API contract, including a negative fixture. */
class TransferBoundaryTest {
    private static final Set<String> PROVIDERS = Set.of(
            "identity", "customer", "account", "ledger", "limit", "audit", "fraud", "notification", "admin");
    private static final String BANKING = "com.simulatedbank.banking.";
    private static final ArchRule RULE = noClasses().that().resideInAnyPackage("..banking.transfer..", "..transfercandidate..")
            .should().dependOnClassesThat(new DescribedPredicate<JavaClass>("provider internals") {
                @Override public boolean test(JavaClass target) {
                    String name = target.getPackageName();
                    if (!name.startsWith(BANKING)) return false;
                    String suffix = name.substring(BANKING.length());
                    int split = suffix.indexOf('.');
                    String module = split < 0 ? suffix : suffix.substring(0, split);
                    if (!PROVIDERS.contains(module)) return false;
                    String subpackage = split < 0 ? "" : suffix.substring(split + 1);
                    return !subpackage.equals("api") && !subpackage.startsWith("api.");
                }
            }).because("Transfer may use another business module only through its public api package");

    @Test
    void productionTransferDependsOnlyOnProviderPublicApis() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.simulatedbank");
        assertThat(classes.stream().anyMatch(c -> c.getPackageName().contains("banking.transfer"))).isTrue();
        RULE.check(classes);
    }

    @Test
    void ruleRejectsPrivateProviderTypesButAllowsPublicApi() {
        var importer = new ClassFileImporter();
        var bad = importer.importClasses(BadConsumerFixture.class, BoundaryPrivateFixture.class);
        var good = importer.importClasses(GoodConsumerFixture.class, BoundaryPublicFixture.class);
        assertThat(RULE.evaluate(bad).hasViolation()).isTrue();
        assertThat(RULE.evaluate(good).hasViolation()).isFalse();
    }
}
