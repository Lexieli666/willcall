package dev.willcall.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The segment tree against the obvious implementation.
 *
 * <p>The property tests are the real content. Example tests pin the cases that are easy to get
 * wrong by inspection — an empty row, a full row, a run that straddles the midpoint — and the
 * properties cover the rest by comparing against a linear scan over thousands of random patterns.
 */
class RowAvailabilityTreeTest {

  // ------------------------------------------------------------------ examples

  @Test
  @DisplayName("an empty row offers one run of its full length")
  void emptyRow() {
    RowAvailabilityTree tree = new RowAvailabilityTree(10);

    assertThat(tree.maxFreeRun()).isEqualTo(10);
    assertThat(tree.findFirstRun(10)).hasValue(0);
    assertThat(tree.findFirstRun(11)).isEmpty();
  }

  @Test
  @DisplayName("a full row offers nothing")
  void fullRow() {
    RowAvailabilityTree tree = RowAvailabilityTree.parse("XXXXX");

    assertThat(tree.maxFreeRun()).isZero();
    assertThat(tree.findFirstRun(1)).isEmpty();
  }

  @Test
  @DisplayName("the answer is the leftmost run that fits, not the biggest one")
  void leftmostNotLargest() {
    //                                            0123456789
    RowAvailabilityTree tree = RowAvailabilityTree.parse("..X.....X.");

    assertThat(tree.findFirstRun(2)).hasValue(0);
    assertThat(tree.findFirstRun(3)).hasValue(3);
    assertThat(tree.findFirstRun(5)).hasValue(3);
    assertThat(tree.findFirstRun(6)).isEmpty();
  }

  @Test
  @DisplayName("a run that straddles the midpoint is found, which is the whole point of the tree")
  void straddlingRun() {
    // Eight seats: the tree splits 0-3 and 4-7. The only run of four spans the boundary, so a
    // structure that only looked at each child's own maximum would miss it.
    RowAvailabilityTree tree = RowAvailabilityTree.parse("XX....XX");

    assertThat(tree.maxFreeRun()).isEqualTo(4);
    assertThat(tree.findFirstRun(4)).hasValue(2);
  }

  @Test
  @DisplayName("a run straddling three levels of the tree is still found")
  void deeplyStraddlingRun() {
    RowAvailabilityTree tree = RowAvailabilityTree.parse("XXXXXXX..............XXXXXXXXX");

    assertThat(tree.maxFreeRun()).isEqualTo(14);
    assertThat(tree.findFirstRun(14)).hasValue(7);
    assertThat(tree.findFirstRun(15)).isEmpty();
  }

  @Test
  @DisplayName("freeing a seat joins the runs on either side of it")
  void freeingJoinsRuns() {
    RowAvailabilityTree tree = RowAvailabilityTree.parse("...X...");
    assertThat(tree.maxFreeRun()).isEqualTo(3);

    tree.setFree(3);

    assertThat(tree.maxFreeRun()).isEqualTo(7);
    assertThat(tree.findFirstRun(7)).hasValue(0);
  }

  @Test
  @DisplayName("occupying a seat splits the run it was in")
  void occupyingSplitsARun() {
    RowAvailabilityTree tree = new RowAvailabilityTree(7);

    tree.setOccupied(3);

    assertThat(tree.maxFreeRun()).isEqualTo(3);
    assertThat(tree.findFirstRun(4)).isEmpty();
    assertThat(tree.findFirstRun(3)).hasValue(0);
  }

  @Test
  @DisplayName("a one-seat row works, because off-by-one lives at the boundaries")
  void singleSeatRow() {
    RowAvailabilityTree tree = new RowAvailabilityTree(1);

    assertThat(tree.maxFreeRun()).isEqualTo(1);
    assertThat(tree.findFirstRun(1)).hasValue(0);

    tree.setOccupied(0);
    assertThat(tree.maxFreeRun()).isZero();
    assertThat(tree.findFirstRun(1)).isEmpty();
  }

  @Test
  @DisplayName("setting the same seat twice is idempotent, so a replayed update cannot corrupt it")
  void repeatedUpdatesAreIdempotent() {
    RowAvailabilityTree tree = new RowAvailabilityTree(8);

    tree.setOccupied(4);
    tree.setOccupied(4);
    tree.setOccupied(4);

    assertThat(tree.maxFreeRun()).isEqualTo(4);
    assertThat(tree.toFreeArray()[4]).isFalse();
  }

  @Test
  @DisplayName("out-of-range access fails loudly rather than reading a neighbouring seat")
  void boundsAreChecked() {
    RowAvailabilityTree tree = new RowAvailabilityTree(4);

    assertThatThrownBy(() -> tree.setOccupied(4)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> tree.setOccupied(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> tree.findFirstRun(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RowAvailabilityTree(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ------------------------------------------------------------------ properties

  @Provide
  Arbitrary<String> rowPatterns() {
    return Arbitraries.strings().withChars('.', 'X').ofMinLength(1).ofMaxLength(200);
  }

  @Property(tries = 10_000)
  @Label("the tree agrees with a linear scan about where the first run is")
  void agreesWithLinearScan(
      @ForAll("rowPatterns") String pattern, @ForAll @IntRange(min = 1, max = 210) int length) {
    RowAvailabilityTree tree = RowAvailabilityTree.parse(pattern);
    boolean[] free = tree.toFreeArray();

    OptionalInt fromTree = tree.findFirstRun(length);
    OptionalInt fromScan = LinearScanAllocator.findFirstRun(free, length);

    assertThat(fromTree).as("row '%s' asking for %s", pattern, length).isEqualTo(fromScan);
  }

  @Property(tries = 10_000)
  @Label("the tree agrees with a linear scan about the longest run")
  void agreesAboutMaxRun(@ForAll("rowPatterns") String pattern) {
    RowAvailabilityTree tree = RowAvailabilityTree.parse(pattern);

    assertThat(tree.maxFreeRun())
        .as("row '%s'", pattern)
        .isEqualTo(LinearScanAllocator.maxFreeRun(tree.toFreeArray()));
  }

  @Property(tries = 2_000)
  @Label("a tree updated seat by seat matches one rebuilt from the same pattern")
  void incrementalUpdatesMatchARebuild(@ForAll("rowPatterns") String pattern) {
    RowAvailabilityTree incremental = new RowAvailabilityTree(pattern.length());
    for (int i = 0; i < pattern.length(); i++) {
      if (pattern.charAt(i) == 'X') incremental.setOccupied(i);
    }

    RowAvailabilityTree rebuilt = RowAvailabilityTree.parse(pattern);

    assertThat(incremental.maxFreeRun()).as("row '%s'", pattern).isEqualTo(rebuilt.maxFreeRun());
    assertThat(incremental).isEqualTo(rebuilt);
  }

  @Property(tries = 2_000)
  @Label("whatever findFirstRun returns really is a run of that length")
  void theAnswerIsActuallyFree(
      @ForAll("rowPatterns") String pattern, @ForAll @IntRange(min = 1, max = 50) int length) {
    RowAvailabilityTree tree = RowAvailabilityTree.parse(pattern);

    OptionalInt start = tree.findFirstRun(length);
    if (start.isEmpty()) return;

    boolean[] free = tree.toFreeArray();
    assertThat(start.getAsInt() + length).isLessThanOrEqualTo(free.length);
    for (int i = start.getAsInt(); i < start.getAsInt() + length; i++) {
      assertThat(free[i])
          .as(
              "row '%s' claimed %s free from %s, seat %s is taken",
              pattern, length, start.getAsInt(), i)
          .isTrue();
    }
  }

  @Property(tries = 2_000)
  @Label("if the tree says no run exists, a linear scan cannot find one either")
  void absenceIsAlsoCorrect(
      @ForAll("rowPatterns") String pattern, @ForAll @IntRange(min = 1, max = 50) int length) {
    RowAvailabilityTree tree = RowAvailabilityTree.parse(pattern);

    if (tree.findFirstRun(length).isPresent()) return;

    assertThat(LinearScanAllocator.findFirstRun(tree.toFreeArray(), length))
        .as("row '%s' asking for %s", pattern, length)
        .isEmpty();
  }

  @Property(tries = 1_000)
  @Label("a sequence of random occupies and frees keeps the tree in step with a plain array")
  void randomMutationsStayInStep(
      @ForAll @IntRange(min = 1, max = 120) int size,
      @ForAll @net.jqwik.api.constraints.Size(max = 200)
          java.util.List<@net.jqwik.api.constraints.IntRange(min = 0, max = 500) Integer>
              operations) {
    RowAvailabilityTree tree = new RowAvailabilityTree(size);
    boolean[] reference = new boolean[size];
    java.util.Arrays.fill(reference, true);

    for (int operation : operations) {
      int index = operation % size;
      boolean makeFree = (operation / size) % 2 == 0;
      if (makeFree) {
        tree.setFree(index);
        reference[index] = true;
      } else {
        tree.setOccupied(index);
        reference[index] = false;
      }
      assertThat(tree.maxFreeRun()).isEqualTo(LinearScanAllocator.maxFreeRun(reference));
    }

    for (int length = 1; length <= size; length++) {
      assertThat(tree.findFirstRun(length))
          .as(
              "after %s operations on a %s-seat row, asking for %s",
              operations.size(), size, length)
          .isEqualTo(LinearScanAllocator.findFirstRun(reference, length));
    }
  }
}
