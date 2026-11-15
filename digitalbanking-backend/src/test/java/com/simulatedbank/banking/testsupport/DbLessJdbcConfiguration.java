package com.simulatedbank.banking.testsupport;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only JDBC support for DB-less Spring Boot contexts.
 *
 * <p>Common-layer tests deliberately exclude {@code DataSourceAutoConfiguration},
 * so no {@link JdbcTemplate} bean exists and every JDBC-backed application bean
 * (audit {@code AuditRecorder}, notification and fraud repositories) fails
 * context startup with {@code NoSuchBeanDefinitionException}. This
 * auto-configuration contributes a {@link JdbcTemplate} whose underlying
 * {@link DataSource} refuses every connection attempt: production beans keep
 * their real wiring, and any accidental database access fails loudly instead of
 * silently succeeding.</p>
 *
 * <p>Registered through {@code META-INF/spring/...AutoConfiguration.imports} on
 * the <em>test</em> classpath only, so packaged production artifacts never
 * contain it. It backs off whenever a real {@link JdbcTemplate} already exists
 * (Testcontainers-based suites, fixtures), which leaves every database-backed
 * test untouched.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(JdbcTemplateAutoConfiguration.class)
@ConditionalOnClass(JdbcTemplate.class)
public class DbLessJdbcConfiguration {

    @Bean
    @ConditionalOnMissingBean(JdbcOperations.class)
    JdbcTemplate dbLessJdbcTemplate() {
        return new JdbcTemplate(new UnreachableDataSource());
    }

    static final class UnreachableDataSource implements DataSource {
        private static final String MESSAGE = "DB-less test context: no database is configured";

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException(MESSAGE);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException(MESSAGE);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(UnreachableDataSource.class.getName());
        }
    }
}
