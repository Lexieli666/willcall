package dev.willcall.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.SeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The in-memory allocation hint.
 *
 * <p>What matters here is not that it finds seats — the segment tree's own tests cover that — but
 * that it is honest about not knowing. An index that silently returns a stale answer would let the
 * reservation core hand out a seat twice; an index that returns nothing merely costs a fallback.
 */
@ExtendWith(MockitoExtension.class)
class ContiguousSeatIndexTest {

  @Mock SeatRepository seats;

  private ContiguousSeatIndex index;
  private UUID eventId;
  private UUID rowId;
  private List<Seat> rowSeats;

  @BeforeEach
  void setUp() {
    index = new ContiguousSeatIndex(seats, new SimpleMeterRegistry(), true, 200_000);
    eventId = UUID.randomUUID();
    rowId = UUID.randomUUID();
    rowSeats = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      rowSeats.add(
          new Seat(
              UUID.randomUUID(),
              eventId,
              rowId,
              null,
              i,
              "A-" + i,
              SeatStatus.AVAILABLE,
              0,
              Instant.now()));
    }
  }

  @Test
  @DisplayName("an event that was never indexed proposes nothing rather than guessing")
  void unknownEventProposesNothing() {
    assertThat(index.propose(eventId, 2)).isEmpty();
    assertThat(index.isIndexed(eventId)).isFalse();
  }

  @Test
  @DisplayName("after loading, it proposes the leftmost run of the requested length")
  void proposesLeftmostRun() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);

    List<UUID> proposal = index.propose(eventId, 3).orElseThrow();

    assertThat(proposal)
        .containsExactly(rowSeats.get(0).id(), rowSeats.get(1).id(), rowSeats.get(2).id());
  }

  @Test
  @DisplayName("a seat change applied to the index moves the proposal")
  void appliedChangesMoveTheProposal() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);

    index.apply(eventId, rowSeats.get(0).id(), SeatStatus.HELD);
    index.apply(eventId, rowSeats.get(1).id(), SeatStatus.SOLD);

    assertThat(index.propose(eventId, 3).orElseThrow())
        .containsExactly(rowSeats.get(2).id(), rowSeats.get(3).id(), rowSeats.get(4).id());
  }

  @Test
  @DisplayName("it proposes nothing when no run is long enough")
  void noRunLongEnough() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);

    for (int i = 0; i < 10; i += 2) index.apply(eventId, rowSeats.get(i).id(), SeatStatus.SOLD);

    assertThat(index.propose(eventId, 2)).isEmpty();
    assertThat(index.maxFreeRun(eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a rejected proposal marks those seats taken, so the next attempt does not repeat it")
  void staleProposalIsPessimised() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);

    List<UUID> first = index.propose(eventId, 2).orElseThrow();
    index.proposalWasStale(eventId, first);
    List<UUID> second = index.propose(eventId, 2).orElseThrow();

    assertThat(second).doesNotContainAnyElementsOf(first);
  }

  @Test
  @DisplayName("an event bigger than the limit is left to the database rather than indexed")
  void oversizedEventIsNotIndexed() {
    ContiguousSeatIndex small = new ContiguousSeatIndex(seats, new SimpleMeterRegistry(), true, 5);
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);

    small.load(eventId);

    assertThat(small.isIndexed(eventId)).isFalse();
    assertThat(small.propose(eventId, 2)).isEmpty();
  }

  @Test
  @DisplayName("disabled, it never proposes anything and never touches the database")
  void disabledIndexIsInert() {
    ContiguousSeatIndex disabled =
        new ContiguousSeatIndex(seats, new SimpleMeterRegistry(), false, 200_000);

    disabled.load(eventId);

    assertThat(disabled.isIndexed(eventId)).isFalse();
    assertThat(disabled.propose(eventId, 1)).isEmpty();
  }

  @Test
  @DisplayName("forgetting an event makes it propose nothing again")
  void forgetDropsTheIndex() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);
    assertThat(index.isIndexed(eventId)).isTrue();

    index.forget(eventId);

    assertThat(index.isIndexed(eventId)).isFalse();
    assertThat(index.propose(eventId, 1)).isEmpty();
  }

  @Test
  @DisplayName("a change for a seat it does not know about is ignored, not an error")
  void unknownSeatIsIgnored() {
    when(seats.findByEvent(eventId)).thenReturn(rowSeats);
    index.load(eventId);

    index.apply(eventId, UUID.randomUUID(), SeatStatus.SOLD);

    assertThat(index.maxFreeRun(eventId)).isEqualTo(10);
  }
}
