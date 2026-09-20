package dev.willcall.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.willcall.platform.web.SeatOrdering;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeatOrderingTest {

  @Test
  @DisplayName("ordering is unsigned-byte order, which is what PostgreSQL uses, not UUID.compareTo")
  void differsFromUuidCompareTo() {
    // 8000... has the top bit of the most significant long set, so UUID.compareTo treats it as
    // negative and sorts it first. PostgreSQL sorts it after 0fff..., and so must we.
    UUID low = UUID.fromString("0fffffff-0000-0000-0000-000000000000");
    UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");

    assertThat(high.compareTo(low)).isNegative();
    assertThat(SeatOrdering.ASCENDING.compare(high, low)).isPositive();
    assertThat(SeatOrdering.sorted(List.of(high, low))).containsExactly(low, high);
  }

  /**
   * Random UUIDs, plus the boundary values that expose the signed-versus-unsigned difference: jqwik
   * has no built-in UUID arbitrary, and a generator of purely random v4 UUIDs would set the top bit
   * only half the time.
   */
  @Provide
  Arbitrary<List<UUID>> uuidLists() {
    Arbitrary<UUID> random = Arbitraries.create(UUID::randomUUID);
    Arbitrary<UUID> extremes =
        Arbitraries.of(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            UUID.fromString("0fffffff-ffff-ffff-ffff-ffffffffffff"),
            UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
            UUID.fromString("80000000-0000-0000-0000-000000000000"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
    return Arbitraries.frequencyOf(Tuple.of(3, random), Tuple.of(1, extremes)).list().ofMaxSize(20);
  }

  @Property
  @Label("sorting is a permutation: same elements, ascending, idempotent")
  void sortingIsAPermutation(@ForAll("uuidLists") List<UUID> ids) {
    List<UUID> sorted = SeatOrdering.sorted(ids);

    assertThat(sorted).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(SeatOrdering.sorted(sorted)).isEqualTo(sorted);
    for (int i = 1; i < sorted.size(); i++) {
      assertThat(sorted.get(i - 1).toString().compareTo(sorted.get(i).toString()))
          .isLessThanOrEqualTo(0);
    }
  }
}
