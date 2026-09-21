package dev.willcall.support;

import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one definition of "clean" that every integration test starts from.
 *
 * <p>There were two, in two base classes that do not share an ancestor, and they disagreed. Both
 * truncated the tables; neither touched Redis; one had fallen behind on which tables existed. The
 * Redis gap cost five test failures that appeared only once the suite was long enough — a hold
 * answered {@code 429} because an earlier test had spent that buyer's token bucket, and an
 * admission test reading {@code buyer-00000004} first because a previous test's queue was still in
 * the sorted set. Those tests had been passing because the suite was short, which is not the same
 * as passing because the code is right.
 *
 * <p>A shared method rather than a shared superclass: one base class needs a web environment and
 * the other must not have one, so they cannot inherit from each other. What they can share is this.
 */
public final class TestStateReset {

  private TestStateReset() {}

  /**
   * Empties both stores.
   *
   * <p>Redis is flushed rather than selectively deleted. A list of key patterns to clear is another
   * thing that falls behind the code — the waiting room's keys were added after this helper's
   * ancestor was written, and nothing would have failed to say so.
   */
  public static void clean(JdbcTemplate jdbc, RedisConnectionFactory redis) {
    truncateWithOneRetry(jdbc);

    try (RedisConnection connection = redis.getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  /**
   * Empties PostgreSQL, retrying once if the truncate deadlocks.
   *
   * <p>{@code TRUNCATE} takes {@code ACCESS EXCLUSIVE} on twelve tables at once, and it takes them
   * in the order written. A transaction still finishing from the previous test can hold one of them
   * and be waiting for another, and the two deadlock — which is what happened once in a
   * 10,000-sequence property run, on the setup statement rather than on anything the product does.
   *
   * <p>A retry rather than a lock ordering, and the distinction matters: the product's lock order
   * is an invariant with a test of its own (ADR 0005), and this is a test fixture emptying tables
   * between runs. Making the fixture look like a participant in that ordering would imply the
   * product has to care about it, which it does not. One retry, and a failure if the second attempt
   * deadlocks too, because a reproducible deadlock here would mean something is holding locks long
   * after its test finished and that is worth failing over.
   */
  private static void truncateWithOneRetry(JdbcTemplate jdbc) {
    try {
      truncate(jdbc);
    } catch (DeadlockLoserDataAccessException first) {
      truncate(jdbc);
    }
  }

  private static void truncate(JdbcTemplate jdbc) {
    jdbc.execute(
        """
        truncate table outbox, idempotency_records, order_lines, orders,
                       admissions, holds, hold_groups, seats, seat_rows, sections,
                       price_tiers, events, venues
        restart identity cascade
        """);
  }
}
