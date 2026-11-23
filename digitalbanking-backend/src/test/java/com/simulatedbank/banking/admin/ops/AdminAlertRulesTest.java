package com.simulatedbank.banking.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.simulatedbank.banking.admin.application.ops.AdminApprovalMetrics;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * BE-ADM-OPS-01: the Prometheus rule file must stay valid and reference only bounded, registered metric families.
 * Audit failure/event counters are the only documented exception because the Admin audit adapter owns them.
 */
class AdminAlertRulesTest {

    private static final Pattern METRIC = Pattern.compile("\\b(admin_[a-z0-9_]+)\\b");
    private static final Set<String> DOCUMENTED_AUDIT_METRICS =
            Set.of("admin_audit_failures", "admin_audit_events");
    private static final Set<String> SEVERITIES = Set.of("critical", "warning");
    private static final String RUNBOOK_PREFIX = "docs-backend/modules/admin/runbook.md#";

    private static List<Map<String, Object>> rules;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadRules() throws Exception {
        Path file = rulesFile();
        try (InputStream input = Files.newInputStream(file)) {
            Map<String, Object> root = new Yaml().load(input);
            assertThat(root).isNotNull();
            List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
            assertThat(groups).as("rule groups").isNotEmpty();
            rules = new ArrayList<>();
            for (Map<String, Object> group : groups) {
                assertThat(group.get("name")).as("group name").isInstanceOf(String.class);
                List<Map<String, Object>> groupRules = (List<Map<String, Object>>) group.get("rules");
                assertThat(groupRules).as("rules in group").isNotEmpty();
                rules.addAll(groupRules);
            }
        }
    }

    @Test
    @DisplayName("every expression references only registered or documented metric families")
    void everyReferencedMetricFamilyIsRegistered() {
        Set<String> allowed = new HashSet<>(AdminApprovalMetrics.metricNames().stream()
                .map(name -> name.replace('.', '_'))
                .collect(Collectors.toSet()));
        allowed.addAll(DOCUMENTED_AUDIT_METRICS);

        for (Map<String, Object> rule : rules) {
            String expr = (String) rule.get("expr");
            Set<String> referenced = referencedFamilies(expr);
            assertThat(referenced).as("metrics in %s", rule.get("alert")).isNotEmpty();
            assertThat(allowed).as("unknown metric in %s: %s", rule.get("alert"), referenced)
                    .containsAll(referenced);
        }
    }

    @Test
    @DisplayName("every rule carries a valid severity and a runbook annotation")
    @SuppressWarnings("unchecked")
    void everyRuleHasSeverityAndRunbook() {
        for (Map<String, Object> rule : rules) {
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            assertThat(labels).as("labels of %s", rule.get("alert")).isNotNull();
            assertThat(SEVERITIES).as("severity of %s", rule.get("alert"))
                    .contains((String) labels.get("severity"));

            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(annotations).as("annotations of %s", rule.get("alert")).isNotNull();
            assertThat(annotations.get("summary")).as("summary of %s", rule.get("alert"))
                    .isInstanceOf(String.class);
            Object runbook = annotations.get("runbook");
            assertThat(runbook).as("runbook of %s", rule.get("alert"))
                    .isInstanceOf(String.class);
            assertThat((String) runbook).startsWith(RUNBOOK_PREFIX);
            assertThat(((String) runbook).length()).isGreaterThan(RUNBOOK_PREFIX.length());
        }
    }

    @Test
    @DisplayName("the required alert scenarios are covered")
    void requiredAlertScenariosAreCovered() {
        String allExpressions = rules.stream()
                .map(rule -> (String) rule.get("expr"))
                .collect(Collectors.joining("\n"));

        assertThat(allExpressions).contains(
                "admin_provider_executions_total{outcome=\"FAILURE\"}",
                "admin_approval_retry_attempts_total",
                "admin_approval_version_conflicts_total",
                "admin_approval_retry_exhausted_total",
                "admin_approval_stuck",
                "admin_audit_failures_total",
                "admin_approval_recovery_total{outcome=\"ERROR\"}");
    }

    @Test
    @DisplayName("alert names are unique and non-empty")
    void alertNamesAreUnique() {
        List<String> names = rules.stream().map(rule -> (String) rule.get("alert")).toList();
        assertThat(names).doesNotContainNull();
        assertThat(new HashSet<>(names)).hasSameSizeAs(names);
    }

    private static Set<String> referencedFamilies(String expression) {
        Set<String> families = new HashSet<>();
        Matcher matcher = METRIC.matcher(expression);
        while (matcher.find()) {
            families.add(baseFamily(matcher.group(1)));
        }
        return families;
    }

    private static String baseFamily(String metric) {
        String name = metric;
        if (name.endsWith("_total")) {
            name = name.substring(0, name.length() - "_total".length());
        } else if (name.endsWith("_count")) {
            name = name.substring(0, name.length() - "_count".length());
        } else if (name.endsWith("_sum")) {
            name = name.substring(0, name.length() - "_sum".length());
        } else if (name.endsWith("_bucket")) {
            name = name.substring(0, name.length() - "_bucket".length());
        }
        if (name.endsWith("_seconds")) {
            name = name.substring(0, name.length() - "_seconds".length());
        }
        return name;
    }

    private static Path rulesFile() {
        for (Path candidate : List.of(
                Path.of("ops", "prometheus", "admin-alerts.yml"),
                Path.of("digitalbanking-backend", "ops", "prometheus", "admin-alerts.yml"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("ops/prometheus/admin-alerts.yml not found from "
                + Path.of("").toAbsolutePath());
    }
}
