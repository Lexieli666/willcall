package dev.willcall.reservation.service;

import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.platform.web.SeatOrdering;
import dev.willcall.reservation.domain.Hold;
import dev.willcall.reservation.store.HoldRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns expired holds to the pool.
 *
 * <h2>Why every replica runs this, with no leader election</h2>
 *
 * The batch is claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED}, so two sweepers never see
 * the same row. Adding a leader would add a dependency (and a failure mode where the leader is dead
 * but not yet noticed) in exchange for nothing: the skip-locked claim already guarantees each hold
 * is expired exactly once.
 *
 * <h2>Why expiry is not done lazily on read</h2>
 *
 * Expiring a hold when somebody happens to look at the seat would mean taking a hold lock while
 * already holding a seat lock, which is the reverse of the order the rest of the core uses, and
 * therefore a deadlock. A background sweeper at a short interval keeps one lock order everywhere.
 * The cost is that a seat can look held for up to one tick after its hold expires; the tick is 250
 * ms by default, which is well inside the time it takes a person to notice.
 *
 * <h2>Exactly once</h2>
 *
 * The seat transition is conditional on the seat still being {@code HELD}. A hold that was
 * confirmed a microsecond before the sweeper claimed it leaves the seat {@code SOLD}, the
 * conditional update matches nothing, and no capacity is released twice. The count of rows actually
 * changed is what is reported, not the count of holds claimed.
 */
@Component
public class HoldExpirySweeper {

  private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

  private final HoldRepository holds;
  private final SeatRepository seats;
  private final DomainEvents domainEvents;
  private final Clock clock;
  private final int batchSize;

  private final Counter holdsExpired;
  private final Counter seatsReleased;

  public HoldExpirySweeper(
      HoldRepository holds,
      SeatRepository seats,
      DomainEvents domainEvents,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${willcall.sweeper.batch-size:500}") int batchSize) {
    this.holds = holds;
    this.seats = seats;
    this.domainEvents = domainEvents;
    this.clock = clock;
    this.batchSize = batchSize;
    this.holdsExpired = Counter.builder("willcall.holds.expired").register(meterRegistry);
    this.seatsReleased =
        Counter.builder("willcall.seats.released_by_sweeper").register(meterRegistry);
  }

  public int batchSize() {
    return batchSize;
  }

  /** Claims one batch of expired holds and returns how many were claimed. */
  @Transactional
  public int sweepOnce() {
    List<Hold> expired = holds.claimExpired(clock.instant(), batchSize);
    if (expired.isEmpty()) return 0;

    List<UUID> holdIds = expired.stream().map(Hold::id).toList();
    holds.markExpired(holdIds);
    holds.markGroupsExpired(expired.stream().map(Hold::holdGroupId).distinct().toList());

    // Group by event so each event's deltas go out together, and so the outbox rows carry the
    // right event id for the relay's per-event ordering.
    Map<UUID, List<Hold>> byEvent =
        expired.stream().collect(java.util.stream.Collectors.groupingBy(Hold::eventId));

    int releasedTotal = 0;
    for (Map.Entry<UUID, List<Hold>> entry : byEvent.entrySet()) {
      List<UUID> seatIds =
          SeatOrdering.sorted(entry.getValue().stream().map(Hold::seatId).toList());
      List<SeatRepository.SeatVersion> released =
          seats.transitionReturning(seatIds, SeatStatus.HELD, SeatStatus.AVAILABLE);
      releasedTotal += released.size();
      if (!released.isEmpty()) {
        domainEvents.seatsChanged(
            entry.getKey(),
            released.stream().map(SeatRepository.SeatVersion::seatId).toList(),
            SeatStatus.AVAILABLE,
            released.stream().mapToLong(SeatRepository.SeatVersion::version).toArray());
      }
    }

    holdsExpired.increment(expired.size());
    seatsReleased.increment(releasedTotal);

    if (releasedTotal != expired.size() && log.isDebugEnabled()) {
      // Not an error: the difference is holds that were confirmed between the claim and the
      // conditional update, whose seats are correctly still SOLD.
      log.debug(
          "sweeper expired {} holds and released {} seats; {} were confirmed in the meantime",
          expired.size(),
          releasedTotal,
          expired.size() - releasedTotal);
    }

    return expired.size();
  }
}
