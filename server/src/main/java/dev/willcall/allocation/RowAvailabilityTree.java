package dev.willcall.allocation;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * A segment tree over one row's seat availability, answering "where are N adjacent free seats?" in
 * O(log n) with O(log n) updates.
 *
 * <h2>What each node stores, and why three numbers rather than one</h2>
 *
 * A node covering a range keeps:
 *
 * <ul>
 *   <li>{@code maxFreeRun} — the longest run of free seats anywhere inside the range;
 *   <li>{@code prefixFreeRun} — the longest run starting at the range's left edge;
 *   <li>{@code suffixFreeRun} — the longest run ending at the range's right edge.
 * </ul>
 *
 * The prefix and suffix exist because the longest run in a parent is not necessarily the longest
 * run in either child: it can straddle the boundary between them. That straddling run is exactly
 * {@code left.suffix + right.prefix}, and the two edge figures are the only extra state needed to
 * compute it. This is the whole idea; everything else is bookkeeping.
 *
 * <pre>
 *   range:      [ . . X . | . . . X ]        '.' free, 'X' taken
 *   left child:   . . X .      max=2 prefix=2 suffix=1
 *   right child:           . . . X  max=3 prefix=3 suffix=0
 *   parent:     max = max(2, 3, suffix(1) + prefix(3)) = 4
 * </pre>
 *
 * <h2>Why a leftmost search rather than a best-fit one</h2>
 *
 * {@link #findFirstRun} returns the leftmost run that fits. Leftmost is deterministic, which makes
 * the property test's comparison against brute force exact rather than "some valid answer", and it
 * matches what a buyer expects from a seat map read left to right. A best-fit search — smallest run
 * that still fits, to avoid fragmenting long blocks — is a genuinely better allocation policy and
 * is a different function; it is not implemented because nothing measures fragmentation yet.
 *
 * <h2>Not thread-safe</h2>
 *
 * One instance belongs to one row and is mutated under that row's lock. Sharing one across threads
 * without external synchronisation would tear the parent invariants.
 */
public final class RowAvailabilityTree {

  private final int size;
  private final int[] maxRun;
  private final int[] prefixRun;
  private final int[] suffixRun;
  private final int[] nodeLength;

  /** A row where every seat is free. */
  public RowAvailabilityTree(int size) {
    if (size <= 0) throw new IllegalArgumentException("a row needs at least one seat, got " + size);
    this.size = size;
    int nodes = 4 * size;
    this.maxRun = new int[nodes];
    this.prefixRun = new int[nodes];
    this.suffixRun = new int[nodes];
    this.nodeLength = new int[nodes];
    build(1, 0, size - 1, null);
  }

  /** A row whose availability is given; {@code free[i]} is true when seat {@code i} is free. */
  public RowAvailabilityTree(boolean[] free) {
    if (free.length == 0) throw new IllegalArgumentException("a row needs at least one seat");
    this.size = free.length;
    int nodes = 4 * size;
    this.maxRun = new int[nodes];
    this.prefixRun = new int[nodes];
    this.suffixRun = new int[nodes];
    this.nodeLength = new int[nodes];
    build(1, 0, size - 1, free);
  }

  private void build(int node, int lo, int hi, boolean[] free) {
    nodeLength[node] = hi - lo + 1;
    if (lo == hi) {
      int value = (free == null || free[lo]) ? 1 : 0;
      maxRun[node] = value;
      prefixRun[node] = value;
      suffixRun[node] = value;
      return;
    }
    int mid = (lo + hi) >>> 1;
    build(2 * node, lo, mid, free);
    build(2 * node + 1, mid + 1, hi, free);
    pull(node);
  }

  /** Recomputes a node from its children. The straddling run is the only interesting case. */
  private void pull(int node) {
    int left = 2 * node;
    int right = 2 * node + 1;

    prefixRun[node] =
        prefixRun[left] == nodeLength[left] ? nodeLength[left] + prefixRun[right] : prefixRun[left];

    suffixRun[node] =
        suffixRun[right] == nodeLength[right]
            ? nodeLength[right] + suffixRun[left]
            : suffixRun[right];

    maxRun[node] =
        Math.max(Math.max(maxRun[left], maxRun[right]), suffixRun[left] + prefixRun[right]);
  }

  public int size() {
    return size;
  }

  /** The longest run of adjacent free seats in the row. */
  public int maxFreeRun() {
    return maxRun[1];
  }

  public void setOccupied(int index) {
    set(index, false);
  }

  public void setFree(int index) {
    set(index, true);
  }

  public boolean isFree(int index) {
    checkIndex(index);
    return query(1, 0, size - 1, index);
  }

  private void set(int index, boolean free) {
    checkIndex(index);
    update(1, 0, size - 1, index, free ? 1 : 0);
  }

  private void update(int node, int lo, int hi, int index, int value) {
    if (lo == hi) {
      maxRun[node] = value;
      prefixRun[node] = value;
      suffixRun[node] = value;
      return;
    }
    int mid = (lo + hi) >>> 1;
    if (index <= mid) update(2 * node, lo, mid, index, value);
    else update(2 * node + 1, mid + 1, hi, index, value);
    pull(node);
  }

  private boolean query(int node, int lo, int hi, int index) {
    if (lo == hi) return maxRun[node] == 1;
    int mid = (lo + hi) >>> 1;
    return index <= mid ? query(2 * node, lo, mid, index) : query(2 * node + 1, mid + 1, hi, index);
  }

  /**
   * The leftmost start index of a run of at least {@code length} free seats.
   *
   * <p>Descends without ever visiting a subtree that cannot contain the answer, which is what makes
   * this O(log n) rather than a walk. At each node it tries, in order: entirely in the left child;
   * straddling the boundary; entirely in the right child. That order is what makes the result
   * leftmost.
   */
  public OptionalInt findFirstRun(int length) {
    if (length <= 0) throw new IllegalArgumentException("length must be positive, got " + length);
    if (length > size || maxRun[1] < length) return OptionalInt.empty();
    return OptionalInt.of(descend(1, 0, size - 1, length));
  }

  private int descend(int node, int lo, int hi, int length) {
    if (lo == hi) return lo;

    int mid = (lo + hi) >>> 1;
    int left = 2 * node;
    int right = 2 * node + 1;

    if (maxRun[left] >= length) return descend(left, lo, mid, length);

    // The straddling candidate: the left child's suffix plus the right child's prefix. Its start
    // is (mid - leftSuffix + 1). It is only the leftmost answer if the left child alone could not
    // supply one, which is why it is checked second.
    if (suffixRun[left] + prefixRun[right] >= length) {
      return mid - suffixRun[left] + 1;
    }

    return descend(right, mid + 1, hi, length);
  }

  private void checkIndex(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("seat " + index + " is outside a row of " + size);
    }
  }

  /** Debug aid used by the property test's shrinking output. */
  public String render() {
    StringBuilder out = new StringBuilder(size);
    for (int i = 0; i < size; i++) out.append(isFree(i) ? '.' : 'X');
    return out.toString();
  }

  @Override
  public String toString() {
    return "RowAvailabilityTree[size=" + size + ", maxFreeRun=" + maxFreeRun() + "]";
  }

  /** Builds a tree from a compact string of '.' (free) and 'X' (taken); used by tests. */
  public static RowAvailabilityTree parse(String pattern) {
    boolean[] free = new boolean[pattern.length()];
    for (int i = 0; i < pattern.length(); i++) free[i] = pattern.charAt(i) == '.';
    return new RowAvailabilityTree(free);
  }

  /** The array form, for the brute-force comparison. */
  public boolean[] toFreeArray() {
    boolean[] free = new boolean[size];
    for (int i = 0; i < size; i++) free[i] = isFree(i);
    return free;
  }

  /** Equality is by content, so a rebuilt tree compares equal to an incrementally updated one. */
  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof RowAvailabilityTree tree)) return false;
    return size == tree.size && Arrays.equals(toFreeArray(), tree.toFreeArray());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(toFreeArray());
  }
}
