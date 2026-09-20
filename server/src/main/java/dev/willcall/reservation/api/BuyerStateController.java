package dev.willcall.reservation.api;

import dev.willcall.platform.web.BuyerIdentity;
import dev.willcall.reservation.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the buyer's own session needs in one call.
 *
 * <p>One call rather than three so that a page reload during a flash sale costs one round trip. The
 * countdown is returned as seconds remaining computed on the server: a client that computes it from
 * {@code expiresAt} against its own clock shows the wrong number to anyone whose device clock is
 * off, and phone clocks are off often enough to matter.
 */
@RestController
@RequestMapping("/api/events/{eventId}/me")
public class BuyerStateController {

  private final ReservationService reservations;

  public BuyerStateController(ReservationService reservations) {
    this.reservations = reservations;
  }

  public record HoldView(
      UUID holdId, List<UUID> seatIds, Instant expiresAt, long secondsRemaining, String status) {}

  public record OrderView(UUID orderId, String status, int totalCents, List<UUID> seatIds) {}

  public record BuyerStateResponse(
      UUID eventId,
      String userRef,
      Instant serverTime,
      List<HoldView> holds,
      List<OrderView> orders,
      int availableSeats) {}

  @GetMapping
  public BuyerStateResponse myState(@PathVariable UUID eventId, HttpServletRequest request) {
    String userRef = BuyerIdentity.require(request);
    ReservationService.BuyerState state = reservations.buyerState(eventId, userRef);

    return new BuyerStateResponse(
        eventId,
        userRef,
        state.now(),
        state.activeHolds().stream()
            .map(
                group ->
                    new HoldView(
                        group.id(),
                        group.seatIds(),
                        group.expiresAt(),
                        Math.max(0, Duration.between(state.now(), group.expiresAt()).toSeconds()),
                        group.status().name()))
            .toList(),
        state.orders().stream()
            .map(
                order ->
                    new OrderView(
                        order.id(),
                        order.status().name(),
                        order.totalCents(),
                        order.lines().stream()
                            .map(dev.willcall.reservation.domain.OrderLine::seatId)
                            .toList()))
            .toList(),
        state.availableSeats());
  }
}
