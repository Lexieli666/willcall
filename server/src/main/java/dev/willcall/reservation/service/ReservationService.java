package dev.willcall.reservation.service;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.PriceTier;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.EventRepository;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.payment.PaymentResult;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.platform.web.SeatOrdering;
import dev.willcall.reservation.domain.Hold;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.domain.HoldStatus;
import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.store.HoldRepository;
import dev.willcall.reservation.store.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reservation core: the only code allowed to change a seat's status.
 *
 * <h2>The two rules everything else follows from</h2>
 *
 * <ol>
 *   <li><b>No unprotected read-then-write.</b> A seat's status is read and written in the same
 *       transaction, with the row locked for the whole of it. There is no path here that checks
 *       availability and then acts on that check without the lock.
 *   <li><b>One lock order.</b> Holds first, then seats, both ascending by seat id. Acquisition is
 *       the exception and cannot deadlock against the rest, because it locks seats and then
 *       <em>inserts</em> holds rather than locking existing hold rows.
 * </ol>
 *
 * <h2>Why checkout is three steps rather than one transaction</h2>
 *
 * The payment gateway takes tens to thousands of milliseconds. Holding seat row locks across that
 * call would serialise the entire event behind the slowest card in the queue. So checkout is:
 * prepare (transaction, extends the hold to cover the payment window), charge (no transaction, no
 * locks), settle (transaction, re-checks that the hold is still ours).
 *
 * <p>The re-check in settle is the interesting part. Between prepare and settle the hold can still
 * be lost — the buyer cancels in another tab, or an operator intervenes. If the money moved and the
 * seats did not, the order is marked {@code FAILED} with {@code seats_lost_after_payment}, which is
 * a refund case and is logged at error level because it needs a human. Extending the hold in
 * prepare makes it rare; pretending it is impossible would make it silent.
 */
@Service
public class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  private final EventRepository events;
  private final SeatRepository seats;
  private final HoldRepository holds;
  private final OrderRepository orders;
  private final DomainEvents domainEvents;
  private final Clock clock;

  private final Duration checkoutGrace;
  private final int maxActiveHoldsPerUser;

  private final Counter holdsGranted;
  private final Counter holdsRejected;
  private final Counter ordersConfirmed;
  private final Counter ordersFailed;
  private final Counter seatsLostAfterPayment;

  public ReservationService(
      EventRepository events,
      SeatRepository seats,
      HoldRepository holds,
      OrderRepository orders,
      DomainEvents domainEvents,
      Clock clock,
      MeterRegistry meterRegistry,
      @Value("${willcall.checkout.grace:PT30S}") Duration checkoutGrace,
      @Value("${willcall.holds.max-active-per-user:1}") int maxActiveHoldsPerUser) {
    this.events = events;
    this.seats = seats;
    this.holds = holds;
    this.orders = orders;
    this.domainEvents = domainEvents;
    this.clock = clock;
    this.checkoutGrace = checkoutGrace;
    this.maxActiveHoldsPerUser = maxActiveHoldsPerUser;

    this.holdsGranted = Counter.builder("willcall.holds.granted").register(meterRegistry);
    this.holdsRejected = Counter.builder("willcall.holds.rejected").register(meterRegistry);
    this.ordersConfirmed = Counter.builder("willcall.orders.confirmed").register(meterRegistry);
    this.ordersFailed = Counter.builder("willcall.orders.failed").register(meterRegistry);
    this.seatsLostAfterPayment =
        Counter.builder("willcall.orders.seats_lost_after_payment")
            .description("Payments that succeeded after the seats were already gone. Refund cases.")
            .register(meterRegistry);
  }

  // ------------------------------------------------------------------ acquisition

  /**
   * Takes a hold on seats.
   *
   * <p>Everything happens in one transaction: lock the seats, verify they are available, insert the
   * hold rows, flip the seat status, write the outbox entries. If any step fails the whole thing
   * rolls back and no seat was ever held.
   */
  @Transactional
  public HoldGroup acquire(UUID eventId, String userRef, AllocationRequest request) {
    Event event =
        events.find(eventId).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));

    if (!event.status().acceptsHolds()) {
      holdsRejected.increment();
      throw new ApiException(ErrorCode.EVENT_NOT_ON_SALE);
    }
    if (request.quantity() < 1) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Ask for at least one seat");
    }
    if (request.quantity() > event.maxSeatsPerOrder()) {
      holdsRejected.increment();
      throw new ApiException(
          ErrorCode.TOO_MANY_SEATS,
          "This event allows at most " + event.maxSeatsPerOrder() + " seats in one order");
    }
    if (maxActiveHoldsPerUser > 0
        && holds.countActiveHoldsForUser(eventId, userRef) + request.quantity()
            > maxActiveHoldsPerUser * event.maxSeatsPerOrder()) {
      holdsRejected.increment();
      throw new ApiException(
          ErrorCode.ALREADY_HOLDING_SEAT, "You already hold as many seats as this event allows");
    }

    List<Seat> locked = lockSeatsFor(event, request);
    if (locked.size() < request.quantity()) {
      holdsRejected.increment();
      throw new ApiException(errorForEmptyAllocation(request, eventId));
    }

    for (Seat seat : locked) {
      if (!seat.status().isAcquirable()) {
        holdsRejected.increment();
        throw new ApiException(
            ErrorCode.SEAT_UNAVAILABLE,
            "Seat " + seat.label() + " is no longer available",
            Map.of("seatId", seat.id().toString(), "seatStatus", seat.status().name()));
      }
    }

    List<UUID> seatIds = SeatOrdering.sorted(locked.stream().map(Seat::id).toList());
    Instant now = clock.instant();
    Instant expiresAt = now.plus(event.holdTtl());
    UUID groupId = UUID.randomUUID();

    holds.insertGroup(groupId, eventId, userRef, expiresAt, seatIds.size());
    try {
      holds.insertHolds(groupId, eventId, userRef, expiresAt, seatIds);
    } catch (DuplicateKeyException e) {
      // The partial unique index refused a second ACTIVE hold on a seat. With the seat rows
      // locked this should be unreachable; if it is reached, a lock was skipped somewhere and
      // rolling back is the only safe answer.
      log.error(
          "unique violation inserting holds for seats {} — lock order may be wrong", seatIds, e);
      throw new ApiException(ErrorCode.SEAT_UNAVAILABLE);
    }

    List<SeatRepository.SeatVersion> updated =
        seats.transitionReturning(seatIds, SeatStatus.AVAILABLE, SeatStatus.HELD);
    if (updated.size() != seatIds.size()) {
      // Impossible while the rows are locked and were checked above. Treated as a bug rather
      // than a race: rolling back is correct either way.
      throw new IllegalStateException(
          "expected to hold " + seatIds.size() + " seats but " + updated.size() + " changed");
    }

    publishSeatDeltas(eventId, updated, SeatStatus.HELD);
    holdsGranted.increment(seatIds.size());

    return new HoldGroup(
        groupId,
        eventId,
        userRef,
        HoldStatus.ACTIVE,
        expiresAt,
        seatIds.size(),
        now,
        null,
        seatIds);
  }

  private List<Seat> lockSeatsFor(Event event, AllocationRequest request) {
    return switch (request.mode()) {
      case EXACT -> {
        List<UUID> requested = SeatOrdering.sorted(request.seatIds().stream().distinct().toList());
        if (requested.size() != request.seatIds().size()) {
          throw new ApiException(ErrorCode.INVALID_REQUEST, "The same seat was requested twice");
        }
        List<Seat> found = seats.lockForAcquisition(requested);
        if (found.size() != requested.size()) {
          throw new ApiException(ErrorCode.SEAT_NOT_FOUND, "One of those seats does not exist");
        }
        for (Seat seat : found) {
          if (!seat.eventId().equals(event.id())) {
            throw new ApiException(ErrorCode.SEAT_NOT_FOUND, "That seat belongs to another event");
          }
        }
        yield found;
      }
      case BEST_AVAILABLE -> seats.claimAnyAvailable(event.id(), request.quantity());
      case BEST_AVAILABLE_TOGETHER -> seats.claimContiguousInAnyRow(event.id(), request.quantity());
    };
  }

  private ErrorCode errorForEmptyAllocation(AllocationRequest request, UUID eventId) {
    return switch (request.mode()) {
      case EXACT -> ErrorCode.SEAT_UNAVAILABLE;
      case BEST_AVAILABLE ->
          seats.countAvailable(eventId) == 0 ? ErrorCode.SOLD_OUT : ErrorCode.SEAT_UNAVAILABLE;
      case BEST_AVAILABLE_TOGETHER -> ErrorCode.NOT_ENOUGH_CONTIGUOUS_SEATS;
    };
  }

  // ------------------------------------------------------------------ cancel

  /** Gives the seats back immediately rather than waiting for the TTL. */
  @Transactional
  public void cancel(UUID holdGroupId, String userRef) {
    List<Hold> locked = holds.lockGroupHolds(holdGroupId);
    if (locked.isEmpty()) throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
    if (!locked.get(0).userRef().equals(userRef)) {
      // Not a 403: telling a stranger that a hold exists is itself information.
      throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
    }

    List<UUID> activeSeatIds =
        SeatOrdering.sorted(
            locked.stream()
                .filter(h -> h.status() == HoldStatus.ACTIVE)
                .map(Hold::seatId)
                .toList());
    if (activeSeatIds.isEmpty()) {
      throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE, "That hold has already been resolved");
    }

    holds.resolveGroupHolds(holdGroupId, HoldStatus.CANCELLED);
    holds.resolveGroup(holdGroupId, HoldStatus.CANCELLED);
    List<SeatRepository.SeatVersion> released =
        seats.transitionReturning(activeSeatIds, SeatStatus.HELD, SeatStatus.AVAILABLE);
    publishSeatDeltas(locked.get(0).eventId(), released, SeatStatus.AVAILABLE);
  }

  // ------------------------------------------------------------------ checkout

  /** What prepare produced and settle needs. */
  public record CheckoutDraft(
      UUID orderId,
      UUID eventId,
      UUID holdGroupId,
      List<UUID> seatIds,
      int totalCents,
      String currency) {}

  /**
   * Reserves the order and extends the hold to cover the payment window.
   *
   * <p>The extension is the reason a slow card does not lose the buyer their seats. It is bounded
   * by {@code willcall.checkout.grace} so a client that starts checkout and walks away cannot hold
   * seats indefinitely.
   */
  @Transactional
  public CheckoutDraft prepareCheckout(UUID holdGroupId, String userRef) {
    List<Hold> locked = holds.lockGroupHolds(holdGroupId);
    if (locked.isEmpty()) throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
    Hold first = locked.get(0);
    if (!first.userRef().equals(userRef)) throw new ApiException(ErrorCode.HOLD_NOT_FOUND);

    Instant now = clock.instant();
    boolean allActive = locked.stream().allMatch(h -> h.status() == HoldStatus.ACTIVE);
    if (!allActive) {
      Optional<UUID> confirmed = orders.findConfirmedOrderForHoldGroup(holdGroupId);
      if (confirmed.isPresent()) {
        // Already bought. Returning the existing order is friendlier than an error and is what
        // a client that lost the response to its first confirm needs to see.
        throw new ApiException(
            ErrorCode.HOLD_NOT_ACTIVE,
            "Those seats are already yours",
            Map.of("orderId", confirmed.get().toString()));
      }
      throw new ApiException(ErrorCode.HOLD_NOT_ACTIVE);
    }
    if (locked.stream().anyMatch(h -> !h.expiresAt().isAfter(now))) {
      throw new ApiException(ErrorCode.HOLD_EXPIRED);
    }

    List<UUID> seatIds = SeatOrdering.sorted(locked.stream().map(Hold::seatId).toList());
    Event event =
        events.find(first.eventId()).orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));

    Map<UUID, Integer> priceBySeat = priceSeats(event.id(), seatIds);
    int total = priceBySeat.values().stream().mapToInt(Integer::intValue).sum();
    String currency =
        events.findPriceTiers(event.id()).stream()
            .map(PriceTier::currency)
            .findFirst()
            .orElse("USD");

    holds.extendGroup(holdGroupId, now.plus(checkoutGrace));

    // Reuse the pending order if this hold group already has one. The gateway is idempotent on
    // the order id, so minting a fresh id for every retry would defeat that and charge twice for
    // the one case idempotency exists to cover: a charge that succeeded behind a timeout.
    UUID orderId =
        orders
            .findPendingOrderForHoldGroup(holdGroupId)
            .orElseGet(
                () -> {
                  UUID fresh = UUID.randomUUID();
                  orders.insertPending(fresh, event.id(), holdGroupId, userRef, total, currency);
                  return fresh;
                });

    return new CheckoutDraft(orderId, event.id(), holdGroupId, seatIds, total, currency);
  }

  /**
   * The outcome of settlement, returned rather than thrown.
   *
   * <p>Returning is not a style preference. Declining a payment has to <em>commit</em> the release
   * of the seats, and throwing out of a {@code @Transactional} method rolls the transaction back —
   * so the first version of this code released the seats and then immediately un-released them, and
   * the integration test caught it as two seats that never came back on sale. Translating these
   * into HTTP failures is the caller's job, after the transaction has committed.
   */
  public sealed interface Settlement {
    /** Paid and allocated. */
    record Confirmed(Order order) implements Settlement {}

    /** The gateway said no. The seats are already back on sale. */
    record Declined(UUID orderId, String failureCode) implements Settlement {}

    /** No answer. The charge may have landed, so the seats stay held until the hold expires. */
    record TimedOut(UUID orderId) implements Settlement {}

    /** Paid, but the hold was gone by the time the money cleared. A refund case. */
    record SeatsLost(UUID orderId) implements Settlement {}
  }

  /** Applies the gateway's answer. Re-checks the hold, because the world moved while we waited. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Settlement settleCheckout(CheckoutDraft draft, PaymentResult payment) {
    switch (payment.status()) {
      case DECLINED -> {
        orders.markFailed(draft.orderId(), payment.failureCode());
        releaseSeatsAfterFailedPayment(draft);
        ordersFailed.increment();
        return new Settlement.Declined(draft.orderId(), payment.failureCode());
      }
      case TIMED_OUT -> {
        // The charge may have landed. The seats stay held until the extended TTL expires, so a
        // retry can still complete; releasing them now could sell a seat somebody already paid
        // for. The order stays PENDING deliberately, and the retry reuses it.
        ordersFailed.increment();
        return new Settlement.TimedOut(draft.orderId());
      }
      case SUCCEEDED -> {
        /* fall through to settlement below */
      }
    }

    List<Hold> locked = holds.lockGroupHolds(draft.holdGroupId());
    boolean stillOurs =
        !locked.isEmpty() && locked.stream().allMatch(h -> h.status() == HoldStatus.ACTIVE);

    if (!stillOurs) {
      // Money moved, seats did not. Rare, because prepare extended the hold past the payment
      // window, but not impossible: a cancel from another tab gets here. This is a refund case,
      // logged at error level because it needs a human.
      seatsLostAfterPayment.increment();
      orders.markFailed(draft.orderId(), "seats_lost_after_payment");
      log.error(
          "payment {} succeeded but hold group {} was no longer active - refund required",
          payment.reference(),
          draft.holdGroupId());
      return new Settlement.SeatsLost(draft.orderId());
    }

    int resolved = holds.resolveGroupHolds(draft.holdGroupId(), HoldStatus.CONFIRMED);
    if (resolved != locked.size()) {
      throw new IllegalStateException(
          "confirmed " + resolved + " of " + locked.size() + " holds while holding the row locks");
    }
    holds.resolveGroup(draft.holdGroupId(), HoldStatus.CONFIRMED);

    List<SeatRepository.SeatVersion> sold =
        seats.transitionReturning(draft.seatIds(), SeatStatus.HELD, SeatStatus.SOLD);
    if (sold.size() != draft.seatIds().size()) {
      throw new IllegalStateException(
          "sold "
              + sold.size()
              + " of "
              + draft.seatIds().size()
              + " seats while holding the locks");
    }

    orders.insertLines(
        draft.orderId(), draft.seatIds(), priceSeats(draft.eventId(), draft.seatIds()));
    orders.markConfirmed(draft.orderId(), payment.reference());

    publishSeatDeltas(draft.eventId(), sold, SeatStatus.SOLD);
    ordersConfirmed.increment();

    Order order =
        orders.find(draft.orderId()).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    return new Settlement.Confirmed(order);
  }

  private void releaseSeatsAfterFailedPayment(CheckoutDraft draft) {
    List<Hold> locked = holds.lockGroupHolds(draft.holdGroupId());
    List<UUID> active =
        SeatOrdering.sorted(
            locked.stream()
                .filter(h -> h.status() == HoldStatus.ACTIVE)
                .map(Hold::seatId)
                .toList());
    if (active.isEmpty()) return;
    holds.resolveGroupHolds(draft.holdGroupId(), HoldStatus.CANCELLED);
    holds.resolveGroup(draft.holdGroupId(), HoldStatus.CANCELLED);
    List<SeatRepository.SeatVersion> released =
        seats.transitionReturning(active, SeatStatus.HELD, SeatStatus.AVAILABLE);
    publishSeatDeltas(draft.eventId(), released, SeatStatus.AVAILABLE);
  }

  private Map<UUID, Integer> priceSeats(UUID eventId, List<UUID> seatIds) {
    Map<UUID, Integer> amountByTier = new HashMap<>();
    for (PriceTier tier : events.findPriceTiers(eventId)) {
      amountByTier.put(tier.id(), tier.amountCents());
    }
    Map<UUID, Integer> result = new LinkedHashMap<>();
    for (Seat seat : seats.findByIds(seatIds)) {
      result.put(seat.id(), amountByTier.getOrDefault(seat.priceTierId(), 0));
    }
    return result;
  }

  private void publishSeatDeltas(
      UUID eventId, List<SeatRepository.SeatVersion> updated, SeatStatus status) {
    List<UUID> ids = updated.stream().map(SeatRepository.SeatVersion::seatId).toList();
    long[] versions = updated.stream().mapToLong(SeatRepository.SeatVersion::version).toArray();
    domainEvents.seatsChanged(eventId, ids, status, versions);
  }

  // ------------------------------------------------------------------ queries

  /** Everything the buyer's own session needs: their holds, their orders, the clock. */
  public record BuyerState(
      UUID eventId,
      String userRef,
      Instant now,
      List<HoldGroup> activeHolds,
      List<Order> orders,
      int availableSeats) {}

  @Transactional(readOnly = true)
  public Optional<Order> findOrder(UUID orderId) {
    return orders.find(orderId);
  }

  @Transactional(readOnly = true)
  public BuyerState buyerState(UUID eventId, String userRef) {
    return new BuyerState(
        eventId,
        userRef,
        clock.instant(),
        holds.findActiveGroupsForUser(eventId, userRef),
        orders.findByUser(eventId, userRef),
        seats.countAvailable(eventId));
  }
}
