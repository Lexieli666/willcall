package dev.willcall.allocation;

import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Segment tree against linear scan, to find where the crossover actually is.
 *
 * <p>The asymptotics are not in doubt: O(log n) beats O(n) eventually. The question a capacity
 * model needs answered is <em>where</em>, and the honest answer for small rows is usually "the
 * simple one, by a lot" — a linear scan over a few hundred booleans is a handful of sequential
 * cache lines with no pointer chasing, while the tree does dependent array reads down a path.
 *
 * <h2>How this is set up so the numbers mean something</h2>
 *
 * <ul>
 *   <li><b>Three row sizes: 200, 2,000, 20,000.</b> A real venue row is a few dozen seats; 20,000
 *       is included to show the asymptotic behaviour clearly, not because a row is ever that long.
 *   <li><b>Three occupancy levels: 10%, 50%, 90%.</b> Occupancy changes both structures' work. A
 *       near-empty row lets the linear scan stop almost immediately; a near-full row makes it walk
 *       the whole thing. Benchmarking only one level would produce a crossover that is an artefact
 *       of that level.
 *   <li><b>The tree is built once in setup, outside the measurement.</b> A flash sale queries the
 *       same row thousands of times between rebuilds, so amortising construction into every query
 *       would describe a workload nobody runs. Construction is measured separately.
 *   <li><b>An update benchmark as well as a search one.</b> The tree's O(log n) update is the cost
 *       it pays for its O(log n) query; comparing only queries would flatter it.
 *   <li><b>The result is consumed by a Blackhole</b> so the JIT cannot delete the work.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ContiguousSeatSearchBenchmark {

  @Param({"200", "2000", "20000"})
  public int seatsPerRow;

  @Param({"10", "50", "90"})
  public int occupancyPercent;

  @Param({"2", "4"})
  public int requestedRun;

  private boolean[] free;
  private RowAvailabilityTree tree;
  private int[] updateIndices;
  private int updateCursor;

  @Setup(Level.Trial)
  public void setUp() {
    // A fixed seed: the benchmark must compare the two implementations on identical input, and
    // must produce the same input on every machine that runs it.
    Random random = new Random(20260920L);

    free = new boolean[seatsPerRow];
    for (int i = 0; i < seatsPerRow; i++) {
      free[i] = random.nextInt(100) >= occupancyPercent;
    }

    tree = new RowAvailabilityTree(free.clone());

    updateIndices = new int[4096];
    for (int i = 0; i < updateIndices.length; i++) {
      updateIndices[i] = random.nextInt(seatsPerRow);
    }
  }

  @Benchmark
  public void linearScanSearch(Blackhole blackhole) {
    OptionalInt result = LinearScanAllocator.findFirstRun(free, requestedRun);
    blackhole.consume(result);
  }

  @Benchmark
  public void segmentTreeSearch(Blackhole blackhole) {
    OptionalInt result = tree.findFirstRun(requestedRun);
    blackhole.consume(result);
  }

  @Benchmark
  public void segmentTreeUpdate(Blackhole blackhole) {
    int index = updateIndices[updateCursor++ & (updateIndices.length - 1)];
    tree.setOccupied(index);
    tree.setFree(index);
    blackhole.consume(index);
  }

  /**
   * The array equivalent of an update, for a fair comparison: flipping one boolean is free, but a
   * linear-scan design still has to answer the next query from scratch.
   */
  @Benchmark
  public void linearScanUpdateAndSearch(Blackhole blackhole) {
    int index = updateIndices[updateCursor++ & (updateIndices.length - 1)];
    boolean previous = free[index];
    free[index] = !previous;
    blackhole.consume(LinearScanAllocator.findFirstRun(free, requestedRun));
    free[index] = previous;
  }

  @Benchmark
  public void segmentTreeConstruction(Blackhole blackhole) {
    blackhole.consume(new RowAvailabilityTree(free.clone()));
  }
}
