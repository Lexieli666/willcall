package dev.willcall.platform.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the invariant checks from inside the application.
 *
 * <p>The authoritative version is {@code scripts/verify-invariants.sh}, which talks to the database
 * directly and therefore cannot be fooled by an application bug. This endpoint exists for the
 * deployed stack, where the database sits in a private subnet and the only thing that can reach it
 * is a running task. The SQL is the same; if the two ever diverge, the shell script wins, and the
 * drift test in the integration suite fails.
 */
@RestController
@RequestMapping("/api/admin")
public class InvariantController {

  /** Each check is a name and a query that must return no rows. */
  public static final Map<String, String> CHECKS =
      Map.ofEntries(
          Map.entry(
              "confirmed_plus_held_within_capacity",
              """
              select e.id::text
              from events e
              join lateral (
                select count(*) filter (where s.status = 'SOLD') as confirmed,
                       count(*) filter (where s.status = 'HELD') as held
                from seats s where s.event_id = e.id
              ) counts on true
              where counts.confirmed + counts.held > e.capacity
              """),
          Map.entry(
              "one_active_hold_per_seat",
              """
              select seat_id::text from holds where status = 'ACTIVE'
              group by seat_id having count(*) > 1
              """),
          Map.entry(
              "no_seat_both_sold_and_held",
              """
              select s.id::text from seats s
              join holds h on h.seat_id = s.id and h.status = 'ACTIVE'
              where s.status = 'SOLD'
              """),
          Map.entry(
              "sold_seat_has_one_confirmed_line",
              """
              select s.id::text from seats s
              left join order_lines ol on ol.seat_id = s.id
              left join orders o on o.id = ol.order_id and o.status = 'CONFIRMED'
              where s.status = 'SOLD'
              group by s.id having count(o.id) <> 1
              """),
          Map.entry(
              "held_seat_has_active_hold",
              """
              select s.id::text from seats s
              where s.status = 'HELD'
                and not exists (select 1 from holds h where h.seat_id = s.id and h.status = 'ACTIVE')
              """),
          Map.entry(
              "active_hold_points_at_held_seat",
              """
              select h.id::text from holds h join seats s on s.id = h.seat_id
              where h.status = 'ACTIVE' and s.status <> 'HELD'
              """),
          Map.entry(
              "capacity_matches_sellable_seats",
              """
              select e.id::text from events e
              where e.capacity <> (select count(*) from seats s where s.event_id = e.id and s.status <> 'BLOCKED')
              """));

  private final JdbcTemplate jdbc;

  public InvariantController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record CheckResult(String name, boolean passed, List<String> offendingRows) {}

  public record InvariantReport(boolean passed, List<CheckResult> checks) {}

  @PostMapping("/verify-invariants")
  public ResponseEntity<InvariantReport> verify() {
    List<CheckResult> results = new ArrayList<>();
    boolean allPassed = true;

    for (Map.Entry<String, String> check : new LinkedHashMap<>(CHECKS).entrySet()) {
      List<String> rows = jdbc.queryForList(check.getValue(), String.class);
      boolean passed = rows.isEmpty();
      allPassed &= passed;
      results.add(new CheckResult(check.getKey(), passed, rows.stream().limit(20).toList()));
    }

    InvariantReport report = new InvariantReport(allPassed, results);
    return allPassed ? ResponseEntity.ok(report) : ResponseEntity.status(500).body(report);
  }
}
