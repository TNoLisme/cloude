package com.simulatedbank.banking.support;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Test-only SQL setup and independent database oracle; shares the caller's Spring transaction. */
public final class SqlFixture {
    private final JdbcTemplate sql;

    public SqlFixture(DataSource dataSource) { this.sql = new JdbcTemplate(dataSource); }
    public void execute(String statement) { sql.execute(statement); }
    public int update(String statement, Object... args) { return sql.update(statement, args); }
    public <T> T queryForObject(String statement, Class<T> type, Object... args) {
        return sql.queryForObject(statement, type, args);
    }
    public <T> T queryForObject(String statement, RowMapper<T> mapper, Object... args) {
        return sql.queryForObject(statement, mapper, args);
    }
    public Map<String, Object> queryForMap(String statement, Object... args) { return sql.queryForMap(statement, args); }
    public List<Map<String, Object>> queryForList(String statement, Object... args) { return sql.queryForList(statement, args); }
    public <T> List<T> queryForList(String statement, Class<T> type, Object... args) {
        return sql.queryForList(statement, type, args);
    }
    public <T> List<T> query(String statement, RowMapper<T> mapper, Object... args) {
        return sql.query(statement, mapper, args);
    }
}
