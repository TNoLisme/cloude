package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.notification.api.OtpGateway;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * BE-ID-TEST-03: đo hiệu năng luồng xác thực Identity trên PostgreSQL 16 Testcontainers +
 * Tomcat thật (RANDOM_PORT), synthetic accounts. NFR-003 («đề xuất» p95 ≤ 1 giây) chỉ là ngưỡng
 * so sánh trong báo cáo, KHÔNG được enforce ở đây để tránh flaky CI; test chỉ fail khi có lỗi
 * chức năng (response ngoài contract) trong cửa sổ đo.
 *
 * Kịch bản: baseline login tuần tự, token validation lặp, token validation đồng thời,
 * login đồng thời vừa phải, MFA/OTP verify dưới tải kiểm soát. Refresh-token concurrency
 * KHÔNG áp dụng — hệ thống dùng opaque session, không có JWT/refresh-token subsystem
 * (Modules contract §5.3.1, DEC-ID-011).
 *
 * Báo cáo JSON ghi vào {@code build/reports/perf/identity-auth-perf.json}; KHÔNG chứa
 * token/OTP/credential (chỉ số liệu tổng hợp).
 */
@Tag("performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IdentityAuthPerformanceTest.PerfLoginGatewayConfiguration.class)
class IdentityAuthPerformanceTest {

    /**
     * Container khởi động thủ công trong @DynamicPropertySource: PER_CLASS lifecycle khiến
     * SpringExtension load context trước TestcontainersExtension, nên @Container thường
     * đánh mapped port sau khi context đã hỏi URL («Mapped port can only be obtained after
     * the container is started»). Start tường minh bảo đảm port có sẵn trước khi datasource
     * được cấu hình; cleanup vẫn qua Ryuk.
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("digital_banking")
            .withUsername("postgres")
            .withPassword("test")
            .withUrlParam("currentSchema", "banking");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!postgres.isRunning()) {
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "5");
        // Nâng quota để cửa sổ đo không bị 429 nhiễu; 429 nếu xảy ra vẫn được tính là error.
        registry.add("app.security.rate-limit.login-per-ip-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.login-per-username-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.otp-verify-per-user-per-minute", () -> "10000");
        registry.add("app.security.rate-limit.read-per-user-per-minute", () -> "100000");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "P3rf0rmance!Pass";
    private static final int USERS = 80;
    private static final String USER_PREFIX = "perfuser";

    @LocalServerPort
    private int port;

    @Autowired
    private Argon2PasswordHasher hasher;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PerfLoginGateway gateway;

    private ExecutorService pool;
    private HttpClient http;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private String base;
    private final AtomicInteger userCursor = new AtomicInteger();
    private final List<Map<String, Object>> scenarioReports = new ArrayList<>();

    static final class PerfLoginGatewayConfiguration {
        @Bean
        PerfLoginGateway perfLoginGateway() {
            return new PerfLoginGateway();
        }
    }

    /** Test-only capture, never an authentication bypass. */
    static final class PerfLoginGateway implements OtpGateway {
        private final Map<String, char[]> codes = new ConcurrentHashMap<>();

        @Override
        public OtpGateway.Result sendOtp(String ref, long userId, String channel, char[] otp,
                Instant expiresAt, String correlationId) {
            codes.put(ref, otp.clone());
            return new OtpGateway.Result(Outcome.ACCEPTED, Failure.NONE);
        }

        String take(String ref) {
            char[] otp = codes.remove(ref);
            assertThat(otp).as("captured OTP for %s", ref).isNotNull();
            try {
                return new String(otp);
            } finally {
                java.util.Arrays.fill(otp, '\0');
            }
        }
    }

    /** Ngữ cảnh một kịch bản đo: sample latency, đếm lỗi theo status, cursor request. */
    private static final class ScenarioContext {
        // ArrayList thường bị lost update khi 8 thread add đồng thời (C mất 2 sample) —
        // dùng queue thread-safe và materialize khi tính percentile.
        final java.util.Queue<Long> okNanos = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Map<String, Integer> errorStatusCounts = new TreeMap<>();
        final List<String> errorNotes = new ArrayList<>();
        final AtomicInteger cursor = new AtomicInteger();
        // AtomicInteger: increment từ 8 thread bị lost update (err âm) khi dùng int thường.
        final AtomicInteger totalRequests = new AtomicInteger();

        synchronized void noteError(int status, String detail) {
            errorStatusCounts.merge(String.valueOf(status), 1, Integer::sum);
            if (errorNotes.size() < 20) {
                errorNotes.add(status + (detail == null ? "" : ":" + detail));
            }
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        this.pool = Executors.newFixedThreadPool(8);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.base = "http://127.0.0.1:" + port;

        // Seed 80 user dùng chung một Argon2id hash (tính 1 lần, không băm 80 lần trong seed).
        String sharedHash = hasher.hash(PASSWORD.toCharArray());
        tx.executeWithoutResult(ignored -> {
            for (int i = 1; i <= USERS; i++) {
                String username = USER_PREFIX + i;
                jdbc.update("INSERT INTO banking.app_user (username, password_hash, user_type, status) "
                        + "VALUES (?, ?, 'CUSTOMER', 'ACTIVE')", username, sharedHash);
                jdbc.update("INSERT INTO banking.user_role (user_id, role_id) "
                        + "SELECT user_id, role_id FROM banking.app_user, banking.role "
                        + "WHERE username = ? AND role_code = 'CUSTOMER'", username);
            }
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        pool.shutdown();
        boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(terminated).as("worker pool kết thúc đúng hạn").isTrue();
        writeReport();
    }

    // ------------------------------------------------------------------ helpers

    private String username(int oneBased) {
        return USER_PREFIX + (((oneBased - 1) % USERS) + 1);
    }

    private HttpResult send(String method, String path, Map<String, String> headers, String payload)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isEmpty()
                ? JSON.nullNode() : JSON.readTree(response.body());
        return new HttpResult(response.statusCode(), body);
    }

    private record HttpResult(int status, JsonNode body) {
    }

    /** Chuẩn bị một login 202 và trả challenge ref; setup bắt buộc 202 nên throw. */
    private String beginLogin(int userIndex) throws Exception {
        var primary = send("POST", "/api/v1/auth/login", Map.of(),
                JSON.writeValueAsString(Map.of("username", username(userIndex), "password", PASSWORD)));
        if (primary.status() != 202) {
            throw new IllegalStateException("login 202 expected, got " + primary.status()
                    + " " + primary.body());
        }
        return primary.body().path("challenge").path("challengeId").asText();
    }

    /** beginLogin đo được: status lỗi được note vào context thay vì throw chung chung. */
    private String beginLoginMeasured(ScenarioContext context, int userIndex) throws Exception {
        var primary = send("POST", "/api/v1/auth/login", Map.of(),
                JSON.writeValueAsString(Map.of("username", username(userIndex), "password", PASSWORD)));
        if (primary.status() != 202) {
            context.noteError(primary.status(), null);
            return null;
        }
        return primary.body().path("challenge").path("challengeId").asText();
    }

    private boolean verifyOtp(ScenarioContext context, String ref, String otp) throws Exception {
        var verify = send("POST", "/api/v1/auth/otp/verify", Map.of(),
                JSON.writeValueAsString(Map.of("challengeId", ref, "otp", otp)));
        if (verify.status() != 200) {
            context.noteError(verify.status(), null);
            return false;
        }
        return true;
    }

    /** Một flow đăng nhập hoàn chỉnh (password 202 → OTP verify 200). */
    private boolean completeLogin(ScenarioContext context) {
        try {
            String ref = beginLoginMeasured(context, context.cursor.incrementAndGet());
            if (ref == null) {
                return false;
            }
            return verifyOtp(context, ref, gateway.take(ref));
        } catch (Exception ex) {
            context.noteError(-1, ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Login đầy đủ một user và trả raw session token (chỉ giữ trong bộ nhớ test). */
    private String loginForToken(int userIndex) throws Exception {
        String ref = beginLogin(userIndex);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/otp/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("challengeId", ref, "otp", gateway.take(ref))), StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().allValues("Set-Cookie").stream()
                .filter(v -> v.startsWith("DB_SESSION="))
                .map(v -> v.split("=")[1].split(";")[0])
                .findFirst().orElseThrow();
    }

    private boolean readSession(ScenarioContext context, String token) {
        try {
            var result = send("GET", "/api/v1/auth/session",
                    Map.of("Authorization", "Bearer " + token), null);
            if (result.status() != 200) {
                context.noteError(result.status(), null);
                return false;
            }
            return true;
        } catch (Exception ex) {
            context.noteError(-1, ex.getClass().getSimpleName());
            return false;
        }
    }

    private interface Timed {
        boolean run(ScenarioContext context) throws Exception;
    }

    private void runScenario(String name, int requests, int concurrency, Timed timed) throws Exception {
        ScenarioContext context = new ScenarioContext();
        long startNanos = System.nanoTime();
        if (concurrency <= 1) {
            for (int i = 0; i < requests; i++) {
                long sampleStart = System.nanoTime();
                boolean ok;
                try {
                    ok = timed.run(context);
                } catch (Exception ex) {
                    context.noteError(-1, ex.getClass().getSimpleName());
                    ok = false;
                }
                context.totalRequests.incrementAndGet();
                if (ok) {
                    context.okNanos.add(System.nanoTime() - sampleStart);
                }
            }
        } else {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < concurrency; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < requests / concurrency; i++) {
                        long sampleStart = System.nanoTime();
                        boolean ok;
                        try {
                            ok = timed.run(context);
                        } catch (Exception ex) {
                            context.noteError(-1, ex.getClass().getSimpleName());
                            ok = false;
                        }
                        context.totalRequests.incrementAndGet();
                        if (ok) {
                            context.okNanos.add(System.nanoTime() - sampleStart);
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        List<Long> sorted = new ArrayList<>(context.okNanos);
        java.util.Collections.sort(sorted);
        int errors = context.totalRequests.get() - sorted.size();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("name", name);
        report.put("requests", context.totalRequests.get());
        report.put("concurrency", concurrency);
        report.put("durationMs", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        report.put("throughputPerSecond", Math.round(
                context.totalRequests.get() / (elapsedNanos / 1_000_000_000.0) * 100.0) / 100.0);
        report.put("okSamples", sorted.size());
        report.put("errorSamples", errors);
        report.put("errorStatusCounts", context.errorStatusCounts);
        if (!sorted.isEmpty()) {
            report.put("latencyMs", latency(sorted));
        }
        scenarioReports.add(report);
        assertThat(errors).as("kịch bản %s không được có lỗi chức năng trong cửa sổ đo: %s",
                name, context.errorNotes).isZero();
    }

    private Map<String, Object> latency(List<Long> sorted) {
        Map<String, Object> stats = new LinkedHashMap<>();
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        stats.put("avg", round1(avg / 1_000_000.0));
        stats.put("min", round1(sorted.getFirst() / 1_000_000.0));
        stats.put("p50", round1(percentile(sorted, 50) / 1_000_000.0));
        stats.put("p95", round1(percentile(sorted, 95) / 1_000_000.0));
        stats.put("p99", round1(percentile(sorted, 99) / 1_000_000.0));
        stats.put("max", round1(sorted.getLast() / 1_000_000.0));
        return stats;
    }

    private static long percentile(List<Long> sorted, int p) {
        int n = sorted.size();
        int index = (int) Math.ceil(p / 100.0 * n) - 1;
        return sorted.get(Math.min(n - 1, Math.max(0, index)));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    @Order(1)
    void scenarioA_baselineSingleUserLoginFlow() throws Exception {
        // Warm-up 10 login flow tuần tự (JIT, Hikari pool, HTTP keep-alive, plan cache).
        ScenarioContext warmup = new ScenarioContext();
        for (int i = 0; i < 10; i++) {
            assertThat(completeLogin(warmup)).isTrue();
        }
        runScenario("baseline_single_user_login", 60, 1, this::completeLogin);
    }

    @Test
    @Order(2)
    void scenarioB_repeatedTokenValidationSequential() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tokens.add(loginForToken(i + 1));
        }
        // Warm-up 30 GET /auth/session.
        for (int i = 0; i < 30; i++) {
            var warm = send("GET", "/api/v1/auth/session",
                    Map.of("Authorization", "Bearer " + tokens.get(i % 3)), null);
            assertThat(warm.status()).isEqualTo(200);
        }
        runScenario("token_validation_sequential", 500, 1, context ->
                readSession(context, tokens.get(
                        Math.floorMod(context.cursor.getAndIncrement(), tokens.size()))));
    }

    @Test
    @Order(3)
    void scenarioC_tokenValidationConcurrent() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tokens.add(loginForToken(i + 11));
        }
        runScenario("token_validation_concurrent_8_threads", 800, 8, context ->
                readSession(context, tokens.get(
                        ThreadLocalRandom.current().nextInt(tokens.size()))));
    }

    @Test
    @Order(4)
    void scenarioD_moderateConcurrentLogin() throws Exception {
        runScenario("concurrent_login_8_threads", 96, 8, this::completeLogin);
    }

    @Test
    @Order(5)
    void scenarioE_mfaOtpVerificationControlledLoad() throws Exception {
        // Chuẩn bị 40 challenge LOGIN_NEW_DEVICE SENT (setup tuần tự, đồng thời là warm-up
        // tự nhiên của password path); verify đồng thời, mỗi challenge dùng đúng một lần.
        List<String> refs = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String ref = beginLogin(61 + i);
            refs.add(ref);
            codes.add(gateway.take(ref));
        }
        runScenario("mfa_otp_verify_concurrent", refs.size(), 8, context -> {
            int index = context.cursor.getAndIncrement();
            if (index >= refs.size()) {
                return true; // hết challenge trong cửa sổ đo — sample no-op hợp lệ
            }
            return verifyOtp(context, refs.get(index), codes.get(index));
        });
    }

    // ------------------------------------------------------------------ report

    private void writeReport() throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        environment.put("testJvmMaxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        environment.put("database", postgres.getDockerImageName());
        environment.put("connectionPool",
                "HikariCP maximum-pool-size=20 minimum-idle=5 (test override)");
        environment.put("appRateLimitOverrides",
                "login-per-ip=10000/min, login-per-username=10000/min, otp-verify-per-user=10000/min,"
                        + " read-per-user=100000/min (chỉ test)");
        environment.put("clock", Instant.now().toString());
        environment.put("note",
                "Localhost loopback + Testcontainers; kết quả phụ thuộc môi trường, không phải SLA production");
        environment.put("warmupStrategy",
                "A: 10 login flow; B: 30 GET /auth/session; C/D/E: đã nóng sau A/B (Tomcat/pool/JIT)");
        report.put("task", "BE-ID-TEST-03 identity authentication performance");
        report.put("proposedThresholdRef",
                "NFR-003 (đề xuất): auth/OTP p95 ≤ 1s — so sánh trong báo cáo, không enforce");
        report.put("environment", environment);
        report.put("scenarios", scenarioReports);
        Path directory = Path.of("build/reports/perf");
        Files.createDirectories(directory);
        Path file = directory.resolve("identity-auth-perf.json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        System.out.println("PERF REPORT: " + file.toAbsolutePath());
        System.out.printf(Locale.ROOT, "%-40s %8s %8s %8s %8s %10s%n",
                "scenario", "p50", "p95", "p99", "err", "rps");
        for (Map<String, Object> scenario : scenarioReports) {
            @SuppressWarnings("unchecked")
            Map<String, Object> latencyMap = (Map<String, Object>) scenario.get("latencyMs");
            if (latencyMap == null) {
                latencyMap = Map.of();
            }
            System.out.printf(Locale.ROOT, "%-40s %8s %8s %8s %8s %10s%n",
                    scenario.get("name"), latencyMap.get("p50"), latencyMap.get("p95"),
                    latencyMap.get("p99"), scenario.get("errorSamples"),
                    scenario.get("throughputPerSecond"));
        }
    }
}
