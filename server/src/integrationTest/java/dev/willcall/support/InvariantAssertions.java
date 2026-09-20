package dev.willcall.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.platform.admin.InvariantController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the invariant checks against the database the test just used.
 *
 * <p>The SQL comes from {@link InvariantController#CHECKS} rather than being copied here, so there
 * is exactly one definition of the invariant in Java and the test cannot drift away from what the
 * deployed service checks. {@code ScriptAndEndpointAgreeTest} covers the third copy, the shell
 * script.
 */
public final class InvariantAssertions {

  private InvariantAssertions() {}

  public static void assertInvariantsHold(JdbcTemplate jdbc) {
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> check : InvariantController.CHECKS.entrySet()) {
      List<String> offending = jdbc.queryForList(check.getValue(), String.class);
      if (!offending.isEmpty()) {
        failures.add(
            check.getKey()
                + " violated by "
                + offending.size()
                + " row(s): "
                + offending.stream().limit(5).toList());
      }
    }
    assertThat(failures).as("database invariants").isEmpty();
  }
}
