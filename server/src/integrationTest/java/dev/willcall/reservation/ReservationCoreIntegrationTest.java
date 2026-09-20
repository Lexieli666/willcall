package dev.willcall.reservation;

import static dev.willcall.support.InvariantAssertions.assertInvariantsHold;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.willcall.allocation.AllocationRequest;
import dev.willcall.catalog.domain.Event;
import dev.willcall.catalog.domain.Seat;
import dev.willcall.catalog.domain.SeatStatus;
import dev.willcall.catalog.store.SeatRepository;
import dev.willcall.payment.FakePaymentGateway;
import dev.willcall.payment.PaymentBehavior;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.domain.Order;
import dev.willcall.reservation.domain.OrderStatus;
import dev.willcall.reservation.service.CheckoutService;
import dev.willcall.reservation.service.HoldExpiryScheduler;
import dev.willcall.reservation.service.ReservationService;
import dev.willcall.support.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationCoreIntegrationTest extends IntegrationTestBase {

  @Autowired ReservationService reservations;
  @Autowired CheckoutService checkout;
  @Autowired SeatRepository seats;
  @Autowired HoldExpiryScheduler sweeper;
  @Autowired FakePaymentGateway gateway;

  private Event event;

  @BeforeEach
  void setUp() {
    gateway.reset();
    event = createEvent(4, 10, 120);
  }

  private List<UUID> seatIds(int from, int count) {
    List<Seat> all = seats.findByEvent(event.id());
    return all.subList(from, from + count).stream().map(Seat::id).toList();
  }

  @Test
  @DisplayName("holding exact seats marks them HELD and nothing else changes")
  void holdExactSeats() {
    List<UUID> wanted = seatIds(0, 3);

    HoldGroup group =
        reservations.acquire(event.id(), "buyer-0001", AllocationRequest.exact(event.id(), wanted));

    assertThat(group.seatIds()).containsExactlyInAnyOrderElementsOf(wanted);
    assertThat(countSeats("HELD")).isEqualTo(3);
    assertThat(countSeats("AVAILABLE")).isEqualTo(37);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a seat already held cannot be held again, and the first hold is untouched")
  void doubleHoldIsRejected() {
    List<UUID> wanted = seatIds(0, 2);
    reservations.acquire(event.id(), "buyer-0001", AllocationRequest.exact(event.id(), wanted));

    assertThatThrownBy(
            () ->
                reservations.acquire(
                    event.id(), "buyer-0002", AllocationRequest.exact(event.id(), wanted)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.SEAT_UNAVAILABLE);

    assertThat(countSeats("HELD")).isEqualTo(2);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("one buyer cannot hold the same seat twice in one request")
  void sameSeatTwiceInOneRequest() {
    UUID seat = seatIds(0, 1).get(0);

    assertThatThrownBy(
            () ->
                reservations.acquire(
                    event.id(),
                    "buyer-0001",
                    AllocationRequest.exact(event.id(), List.of(seat, seat))))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.INVALID_REQUEST);

    assertThat(countSeats("HELD")).isZero();
  }

  @Test
  @DisplayName("best available together returns adjacent seats in one row")
  void bestAvailableTogether() {
    HoldGroup group =
        reservations.acquire(event.id(), "buyer-0001", AllocationRequest.together(event.id(), 4));

    List<Seat> held = seats.findByIds(group.seatIds());
    assertThat(held).hasSize(4);
    assertThat(held.stream().map(Seat::rowId).distinct()).hasSize(1);

    List<Integer> numbers = held.stream().map(Seat::seatNumber).sorted().toList();
    for (int i = 1; i < numbers.size(); i++) {
      assertThat(numbers.get(i)).isEqualTo(numbers.get(i - 1) + 1);
    }
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("asking for more adjacent seats than any row holds is a clean 409, not a 500")
  void notEnoughContiguousSeats() {
    // Rows here are ten seats wide and the per-order cap is fifty, so eleven together is
    // impossible for a reason the buyer can act on: pick fewer, or accept seats apart.
    assertThatThrownBy(
            () ->
                reservations.acquire(
                    event.id(), "buyer-0001", AllocationRequest.together(event.id(), 11)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.NOT_ENOUGH_CONTIGUOUS_SEATS);
  }

  @Test
  @DisplayName(
      "asking for more seats than the event allows in one order is rejected before any locking")
  void moreSeatsThanTheOrderCap() {
    assertThatThrownBy(
            () ->
                reservations.acquire(
                    event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 51)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.TOO_MANY_SEATS);
  }

  @Test
  @DisplayName("confirming a hold sells the seats and writes exactly one order line each")
  void confirmSellsSeats() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 2));

    Order order = checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED);

    assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(order.lines()).hasSize(2);
    assertThat(order.totalCents()).isEqualTo(10_000);
    assertThat(countSeats("SOLD")).isEqualTo(2);
    assertThat(countSeats("HELD")).isZero();
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a declined payment puts the seats straight back on sale")
  void declinedPaymentReleasesSeats() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 2));

    assertThatThrownBy(() -> checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.DECLINE))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.PAYMENT_DECLINED);

    assertThat(countSeats("AVAILABLE")).isEqualTo(40);
    assertThat(countSeats("SOLD")).isZero();
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a gateway timeout keeps the seats held, because the charge may have landed")
  void timeoutKeepsSeatsHeld() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 2));

    assertThatThrownBy(() -> checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.TIMEOUT))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.PAYMENT_TIMEOUT);

    // Releasing here would let somebody else buy a seat this buyer may already have paid for.
    assertThat(countSeats("HELD")).isEqualTo(2);
    assertThat(countSeats("SOLD")).isZero();
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("a charge that succeeded behind a timeout is not charged again on retry")
  void succeedAfterTimeoutThenRetry() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));

    assertThatThrownBy(
            () ->
                checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED_AFTER_TIMEOUT))
        .isInstanceOf(ApiException.class);

    // The retry reaches the gateway, which recognises the order and returns the original charge.
    Order order =
        checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED_AFTER_TIMEOUT);

    assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(countSeats("SOLD")).isEqualTo(1);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("cancelling returns the seats immediately")
  void cancelReleasesSeats() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 3));

    reservations.cancel(group.id(), "buyer-0001");

    assertThat(countSeats("AVAILABLE")).isEqualTo(40);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("cancelling somebody else's hold looks exactly like a hold that does not exist")
  void cannotCancelAnotherBuyersHold() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));

    assertThatThrownBy(() -> reservations.cancel(group.id(), "buyer-0002"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.HOLD_NOT_FOUND);

    assertThat(countSeats("HELD")).isEqualTo(1);
  }

  @Test
  @DisplayName("cancel after confirm is rejected and the sale stands")
  void cancelAfterConfirmIsRejected() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));
    checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED);

    assertThatThrownBy(() -> reservations.cancel(group.id(), "buyer-0001"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);

    assertThat(countSeats("SOLD")).isEqualTo(1);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName(
      "an expired hold releases its seats exactly once, and a second sweep changes nothing")
  void expiryReleasesCapacityExactlyOnce() {
    Event shortLived =
        catalog.createEvent(
            new dev.willcall.catalog.service.CatalogService.CreateEventSpec(
                "Short Venue",
                "Short Event",
                java.time.Instant.now().plusSeconds(3600),
                java.time.Instant.now().minusSeconds(10),
                15,
                50,
                dev.willcall.catalog.domain.EventStatus.ON_SALE,
                List.of(
                    new dev.willcall.catalog.service.CatalogService.PriceTierSpec("S", 100, "USD")),
                List.of(
                    new dev.willcall.catalog.service.CatalogService.SectionSpec("F", 1, 5, "S"))));

    HoldGroup group =
        reservations.acquire(
            shortLived.id(), "buyer-0001", AllocationRequest.bestAvailable(shortLived.id(), 3));

    // Reach into the database rather than sleeping fifteen seconds.
    jdbc.update(
        "update holds set expires_at = now() - interval '1 second' where hold_group_id = ?",
        group.id());
    jdbc.update(
        "update hold_groups set expires_at = now() - interval '1 second' where id = ?", group.id());

    int firstSweep = sweeper.drain();
    int secondSweep = sweeper.drain();

    assertThat(firstSweep).isEqualTo(3);
    assertThat(secondSweep).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from seats where event_id = ? and status = 'AVAILABLE'",
                Long.class,
                shortLived.id()))
        .isEqualTo(5L);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("confirm beats a sweep that arrives after it: the seat stays sold")
  void confirmThenExpireLeavesSeatSold() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));
    checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED);

    jdbc.update(
        "update holds set expires_at = now() - interval '1 second' where hold_group_id = ?",
        group.id());
    int swept = sweeper.drain();

    // The hold is already CONFIRMED, so there is nothing ACTIVE for the sweeper to claim.
    assertThat(swept).isZero();
    assertThat(countSeats("SOLD")).isEqualTo(1);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("confirming an already-expired hold fails and does not sell the seat")
  void confirmAfterExpiryFails() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1));

    jdbc.update(
        "update holds set expires_at = now() - interval '1 second' where hold_group_id = ?",
        group.id());
    sweeper.drain();

    assertThatThrownBy(() -> checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.HOLD_NOT_ACTIVE);

    assertThat(countSeats("SOLD")).isZero();
    assertThat(countSeats("AVAILABLE")).isEqualTo(40);
    assertInvariantsHold(jdbc);
  }

  @Test
  @DisplayName("holds are not granted while an event is paused")
  void pausedEventRejectsHolds() {
    catalog.setStatus(event.id(), dev.willcall.catalog.domain.EventStatus.PAUSED);

    assertThatThrownBy(
            () ->
                reservations.acquire(
                    event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 1)))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code())
        .isEqualTo(ErrorCode.EVENT_NOT_ON_SALE);
  }

  @Test
  @DisplayName("every seat change lands in the outbox in the same transaction")
  void seatChangesAreWrittenToTheOutbox() {
    HoldGroup group =
        reservations.acquire(
            event.id(), "buyer-0001", AllocationRequest.bestAvailable(event.id(), 2));
    checkout.checkout(group.id(), "buyer-0001", PaymentBehavior.SUCCEED);

    Long entries =
        jdbc.queryForObject(
            "select count(*) from outbox where event_id = ? and type = 'seat.changed'",
            Long.class,
            event.id());

    // Two seats held, then the same two sold.
    assertThat(entries).isEqualTo(4L);
  }

  @Test
  @DisplayName("the seat version increases on every state change")
  void versionIncreasesOnEveryChange() {
    List<UUID> wanted = seatIds(0, 1);
    long before = seats.findByIds(wanted).get(0).version();

    HoldGroup group =
        reservations.acquire(event.id(), "buyer-0001", AllocationRequest.exact(event.id(), wanted));
    long afterHold = seats.findByIds(wanted).get(0).version();

    reservations.cancel(group.id(), "buyer-0001");
    long afterCancel = seats.findByIds(wanted).get(0).version();

    assertThat(afterHold).isGreaterThan(before);
    assertThat(afterCancel).isGreaterThan(afterHold);
    assertThat(seats.findByIds(wanted).get(0).status()).isEqualTo(SeatStatus.AVAILABLE);
  }
}
