package dev.willcall.allocation;

import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * An in-memory index of where adjacent free seats are, one {@link RowAvailabilityTree} per row.
 *
 * <h2>What it is and, more importantly, what it is not</h2>
 *
 * It is a <b>hint</b>. It proposes a run of seats; the database then locks those exact rows and
 * checks them. If the proposal has gone stale — and under a flash sale it will, constantly — the
 * caller falls back to the SQL scan. Nothing here is ever trusted enough to allocate a seat.
 *
 * <p>That is the same division as PostgreSQL-owns-correctness / Redis-only-accelerates in ADR 0001,
 * applied one level down. The index can be wrong, arbitrarily stale, or entirely absent, and the
 * worst outcome is a slower request.
 *
 * <h2>Why bother, given the SQL scan already works</h2>
 *
 * The SQL version is a window function over every available seat in the event. It is one round trip
 * and it is correct, but its cost grows with the event. The index answers the same question in
 * O(log n) per row without touching the database at all, and the JMH benchmark publishes where that
 * starts to matter. For a small event it does not, which is why the fallback is not a failure path
 * but an ordinary one.
 *
 * <h2>Staleness</h2>
 *
 * The index is populated on first use and updated from the same seat-change stream that feeds the
 * browsers, so it lags reality by the outbox relay tick. A stale hint costs one wasted lock
 * attempt. A hint that is stale in the other direction — it thinks seats are taken when they are
 * free — costs a fallback to SQL. Both are counted, because if the miss rate is high the index is
 * not earning its memory.
 */
@Component
public class ContiguousSeatIndex {

  private static final Logger log = LoggerFactory.getLogger(ContiguousSeatIndex.class);

  private final SeatRepository seats;
  private final boolean enabled;
  private final int maxIndexedSeatsPerEvent;

  private final Map<UUID, EventIndex> indexes = new ConcurrentHashMap<>();
  private final AtomicLong indexedSeats = new AtomicLong();

  private final Counter hits;
  private final Counter misses;
  private final Counter staleProposals;
  private final Timer buildTimer;

  public ContiguousSeatIndex(
      SeatRepository seats,
      MeterRegistry meterRegistry,
      @Value("${willcall.allocation.index-enabled:true}") boolean enabled,
      @Value("${willcall.allocation.max-indexed-seats:200000}") int maxIndexedSeatsPerEvent) {
    this.seats = seats;
    this.enabled = enabled;
    this.maxIndexedSeatsPerEvent = maxIndexedSeatsPerEvent;

    this.hits =
        Counter.builder("willcall.allocation.index_hits")
            .description("Contiguous requests answered from the in-memory index")
            .register(meterRegistry);
    this.misses =
        Counter.builder("willcall.allocation.index_misses")
            .description("Contiguous requests that fell back to the SQL scan")
            .register(meterRegistry);
    this.staleProposals =
        Counter.builder("willcall.allocation.index_stale")
            .description("Index proposals the database rejected because the seats had moved")
            .register(meterRegistry);
    this.buildTimer = Timer.builder("willcall.allocation.index_build").register(meterRegistry);
  }

  /** One event's rows, each with its own tree and its own seat-id lookup. */
  private static final class EventIndex {
    private final Map<UUID, RowAvailabilityTree> treesByRow = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> seatIdsByRow = new LinkedHashMap<>();
    private final Map<UUID, int[]> positionBySeat = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> rowBySeat = new ConcurrentHashMap<>();
    private final List<UUID> rowOrder = new ArrayList<>();
  }

  /**
   * Proposes {@code quantity} adjacent seats, or nothing.
   *
   * <p>Rows are searched in display order and the leftmost run in the first row that can offer one
   * wins. Deterministic, and it matches what a person reading a seat map expects.
   */
  public Optional<List<UUID>> propose(UUID eventId, int quantity) {
    if (!enabled || quantity <= 0) {
      misses.increment();
      return Optional.empty();
    }

    EventIndex index = indexes.get(eventId);
    if (index == null) {
      misses.increment();
      return Optional.empty();
    }

    synchronized (index) {
      for (UUID rowId : index.rowOrder) {
        RowAvailabilityTree tree = index.treesByRow.get(rowId);
        if (tree == null || tree.maxFreeRun() < quantity) continue;

        OptionalInt start = tree.findFirstRun(quantity);
        if (start.isEmpty()) continue;

        List<UUID> seatIds = index.seatIdsByRow.get(rowId);
        List<UUID> run = new ArrayList<>(quantity);
        for (int i = start.getAsInt(); i < start.getAsInt() + quantity; i++) {
          run.add(seatIds.get(i));
        }
        hits.increment();
        return Optional.of(run);
      }
    }

    misses.increment();
    return Optional.empty();
  }

  /** Called when the database refused a proposal, so the miss rate reflects reality. */
  public void proposalWasStale(UUID eventId, List<UUID> proposed) {
    staleProposals.increment();
    // The proposal was wrong, so at least one of those seats is not free. Mark them all taken:
    // an over-pessimistic index costs a fallback, an over-optimistic one costs a retry loop.
    EventIndex index = indexes.get(eventId);
    if (index == null) return;
    synchronized (index) {
      for (UUID seatId : proposed) {
        applyLocked(index, seatId, SeatStatus.HELD);
      }
    }
  }

  /** Builds or rebuilds an event's index from the database. */
  public void load(UUID eventId) {
    if (!enabled) return;
    buildTimer.record(
        () -> {
          List<Seat> all = seats.findByEvent(eventId);
          if (all.isEmpty()) return;
          if (all.size() > maxIndexedSeatsPerEvent) {
            log.info(
                "not indexing event {}: {} seats exceeds the {} limit; the SQL scan will serve it",
                eventId,
                all.size(),
                maxIndexedSeatsPerEvent);
            return;
          }

          EventIndex index = new EventIndex();
          Map<UUID, List<Seat>> byRow = new LinkedHashMap<>();
          for (Seat seat : all) {
            byRow.computeIfAbsent(seat.rowId(), id -> new ArrayList<>()).add(seat);
          }

          for (Map.Entry<UUID, List<Seat>> entry : byRow.entrySet()) {
            List<Seat> rowSeats = new ArrayList<>(entry.getValue());
            rowSeats.sort(Comparator.comparingInt(Seat::seatNumber));

            boolean[] free = new boolean[rowSeats.size()];
            List<UUID> seatIds = new ArrayList<>(rowSeats.size());
            for (int i = 0; i < rowSeats.size(); i++) {
              Seat seat = rowSeats.get(i);
              free[i] = seat.status() == SeatStatus.AVAILABLE;
              seatIds.add(seat.id());
              index.positionBySeat.put(seat.id(), new int[] {i});
              index.rowBySeat.put(seat.id(), entry.getKey());
            }
            index.treesByRow.put(entry.getKey(), new RowAvailabilityTree(free));
            index.seatIdsByRow.put(entry.getKey(), List.copyOf(seatIds));
            index.rowOrder.add(entry.getKey());
          }

          indexes.put(eventId, index);
          indexedSeats.addAndGet(all.size());
          log.info("indexed event {}: {} rows, {} seats", eventId, byRow.size(), all.size());
        });
  }

  /** Applies one seat change. Called from the same stream that feeds the browsers. */
  public void apply(UUID eventId, UUID seatId, SeatStatus status) {
    if (!enabled) return;
    EventIndex index = indexes.get(eventId);
    if (index == null) return;
    synchronized (index) {
      applyLocked(index, seatId, status);
    }
  }

  private void applyLocked(EventIndex index, UUID seatId, SeatStatus status) {
    UUID rowId = index.rowBySeat.get(seatId);
    int[] position = index.positionBySeat.get(seatId);
    if (rowId == null || position == null) return;
    RowAvailabilityTree tree = index.treesByRow.get(rowId);
    if (tree == null) return;
    if (status == SeatStatus.AVAILABLE) tree.setFree(position[0]);
    else tree.setOccupied(position[0]);
  }

  public void forget(UUID eventId) {
    indexes.remove(eventId);
  }

  public boolean isIndexed(UUID eventId) {
    return indexes.containsKey(eventId);
  }

  public int maxFreeRun(UUID eventId) {
    EventIndex index = indexes.get(eventId);
    if (index == null) return -1;
    synchronized (index) {
      return index.treesByRow.values().stream()
          .mapToInt(RowAvailabilityTree::maxFreeRun)
          .max()
          .orElse(0);
    }
  }
}
