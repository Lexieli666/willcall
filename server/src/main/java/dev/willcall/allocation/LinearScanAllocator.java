package dev.willcall.allocation;

import java.util.OptionalInt;

/**
 * The obvious implementation: walk the row, count consecutive free seats, stop at the first run
 * long enough.
 *
 * <p>It is here for three reasons, in order of importance:
 *
 * <ol>
 *   <li><b>It is the oracle.</b> The segment tree's property test compares against this over
 *       thousands of random patterns. A clever data structure whose only check is itself is not
 *       checked.
 *   <li><b>It is the baseline.</b> The JMH benchmark publishes the crossover, and a crossover is
 *       meaningless without something to cross over.
 *   <li><b>It may well be the right answer.</b> A linear scan over a few hundred seats is a handful
 *       of cache lines and no pointer chasing. The tree wins asymptotically; the measurement says
 *       where.
 * </ol>
 */
public final class LinearScanAllocator {

  private LinearScanAllocator() {}

  /** The leftmost start index of a run of at least {@code length} free seats. */
  public static OptionalInt findFirstRun(boolean[] free, int length) {
    if (length <= 0) throw new IllegalArgumentException("length must be positive, got " + length);
    int run = 0;
    for (int i = 0; i < free.length; i++) {
      run = free[i] ? run + 1 : 0;
      if (run >= length) return OptionalInt.of(i - length + 1);
    }
    return OptionalInt.empty();
  }

  /** The longest run of free seats anywhere in the row. */
  public static int maxFreeRun(boolean[] free) {
    int best = 0;
    int run = 0;
    for (boolean seatIsFree : free) {
      run = seatIsFree ? run + 1 : 0;
      if (run > best) best = run;
    }
    return best;
  }
}
