package dev.willcall.platform.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;

/**
 * Supplies the {@link JdbcTemplate} so that PostgreSQL's timeout SQL states become the exceptions
 * the rest of the application already knows how to answer.
 *
 * <p>Defining this bean makes Spring Boot's own {@code JdbcTemplate} back off; the auto-configured
 * {@code NamedParameterJdbcTemplate} then wraps this one, so every query in the application gets
 * the translation without any of them knowing about it.
 */
@Configuration
public class JdbcConfiguration {

  @Bean
  public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setExceptionTranslator(new TimeoutAwareTranslator(dataSource));
    return template;
  }

  /**
   * Maps {@code lock_timeout} and {@code statement_timeout} cancellations onto Spring's own
   * exception types.
   *
   * <p>Without this, a statement cancelled by {@code lock_timeout} arrives as an {@code
   * UncategorizedSQLException} carrying SQL state {@code 55P03}. Spring's default translators have
   * no entry for it — the driver throws a plain {@code PSQLException}, so the subclass translator
   * has nothing to match on, and the error-code translator has no PostgreSQL mapping for it either.
   * It therefore fell through to the catch-all handler and became a 500.
   *
   * <p>That was measured, not reasoned about: the game day on 2026-09-20 was re-run after {@code
   * lock_timeout} was introduced, and 98 requests that should have been shed as 503 were 500s
   * instead. Setting a timeout without deciding what its expiry means is half a change.
   *
   * <p>{@code 55P03} is {@code lock_not_available} and {@code 57014} is {@code query_canceled}.
   * Both mean the statement was stopped before it did anything and rolled back, so a retry is safe
   * — which is exactly what {@link CannotAcquireLockException} and {@link QueryTimeoutException}
   * are understood to mean, and what {@code ApiExceptionHandler#handleOverloaded} answers 503 with
   * {@code Retry-After} for.
   */
  static final class TimeoutAwareTranslator extends SQLErrorCodeSQLExceptionTranslator {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String QUERY_CANCELED = "57014";

    TimeoutAwareTranslator(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected DataAccessException customTranslate(String task, String sql, SQLException e) {
      String state = e.getSQLState();
      if (LOCK_NOT_AVAILABLE.equals(state)) {
        return new CannotAcquireLockException(message(task, sql), e);
      }
      if (QUERY_CANCELED.equals(state)) {
        return new QueryTimeoutException(message(task, sql), e);
      }
      return null;
    }

    private static String message(String task, String sql) {
      // The SQL is in the message because a lock timeout is only actionable if you know which
      // statement could not get its lock.
      return task + (sql == null ? "" : "; SQL [" + sql + "]");
    }
  }
}
