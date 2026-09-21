package dev.willcall.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Turns off every timer that would otherwise act behind a test's back.
 *
 * <p>Both integration base classes disabled the sweeper and the outbox relay, and neither disabled
 * the waiting room's admitter, which ticks every 250 ms. A test that enqueued 400 buyers, admitted
 * 38 of them deliberately and then asserted a queue length of 362 found 299: the admitter had
 * quietly taken 63 more while the test was running. It passed when the class ran alone, because the
 * timing worked out, and failed in the full suite.
 *
 * <p>A test that shares the system with a background job is not testing the job — it is racing it.
 * Each of these schedulers has its own test that turns it back on deliberately.
 *
 * <p>Shared rather than copied because the copies had already drifted: this is the third list the
 * two base classes were each maintaining separately.
 */
public final class TestBackgroundJobs {

  private TestBackgroundJobs() {}

  public static void disable(DynamicPropertyRegistry registry) {
    registry.add("willcall.sweeper.enabled", () -> false);
    registry.add("willcall.outbox.enabled", () -> false);
    // Admits from the queue on a timer. The waiting-room tests drive admission themselves.
    registry.add("willcall.waitingroom.admitter-enabled", () -> false);
    // Runs the invariant checks on a timer and takes an advisory lock to do it. Useful in
    // production, pure noise inside a test that is about to truncate everything anyway.
    registry.add("willcall.invariants.monitor-enabled", () -> false);
  }
}
