package dev.willcall.reservation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.willcall.allocation.AllocationRequest;
import dev.willcall.platform.web.ApiException;
import dev.willcall.platform.web.BuyerIdentity;
import dev.willcall.platform.web.ErrorCode;
import dev.willcall.reservation.domain.HoldGroup;
import dev.willcall.reservation.service.IdempotencyService;
import dev.willcall.reservation.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HoldController {

  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final ReservationService reservations;
  private final IdempotencyService idempotency;
  private final ObjectMapper objectMapper;

  public HoldController(
      ReservationService reservations, IdempotencyService idempotency, ObjectMapper objectMapper) {
    this.reservations = reservations;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
  }

  /**
   * A hold request.
   *
   * <p>{@code seatIds} and {@code quantity} are alternatives, not options on the same request:
   * "these exact seats" and "any three together" fail differently and a caller needs to know which
   * it asked for.
   */
  public record CreateHoldRequest(
      List<UUID> seatIds, @Min(1) @Max(50) Integer quantity, Boolean together) {

    AllocationRequest toAllocation(UUID eventId) {
      boolean hasSeats = seatIds != null && !seatIds.isEmpty();
      boolean hasQuantity = quantity != null && quantity > 0;
      if (hasSeats == hasQuantity) {
        throw new ApiException(
            ErrorCode.INVALID_REQUEST,
            "Send either seatIds (exact seats) or quantity (best available), not both and not neither");
      }
      if (hasSeats) return AllocationRequest.exact(eventId, seatIds);
      return Boolean.TRUE.equals(together)
          ? AllocationRequest.together(eventId, quantity)
          : AllocationRequest.bestAvailable(eventId, quantity);
    }
  }

  public record HoldResponse(
      UUID holdId,
      UUID eventId,
      List<UUID> seatIds,
      Instant expiresAt,
      long secondsRemaining,
      String status) {

    static HoldResponse of(HoldGroup group, Instant now) {
      return new HoldResponse(
          group.id(),
          group.eventId(),
          group.seatIds(),
          group.expiresAt(),
          Math.max(0, java.time.Duration.between(now, group.expiresAt()).toSeconds()),
          group.status().name());
    }
  }

  @PostMapping("/events/{eventId}/holds")
  public ResponseEntity<?> createHold(
      @PathVariable UUID eventId,
      @Valid @RequestBody CreateHoldRequest body,
      @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
      HttpServletRequest request) {

    String userRef = BuyerIdentity.require(request);
    AllocationRequest allocation = body.toAllocation(eventId);

    IdempotencyService.Result<HoldResponse> result =
        idempotency.execute(
            idempotencyKey,
            userRef,
            "POST /api/events/{eventId}/holds:" + eventId,
            body,
            () -> {
              HoldGroup group = reservations.acquire(eventId, userRef, allocation);
              return new IdempotencyService.Outcome<>(
                  201, HoldResponse.of(group, Instant.now()), group.id());
            });

    return respond(result);
  }

  @DeleteMapping("/holds/{holdId}")
  public ResponseEntity<Void> cancelHold(@PathVariable UUID holdId, HttpServletRequest request) {
    reservations.cancel(holdId, BuyerIdentity.require(request));
    return ResponseEntity.noContent().build();
  }

  /**
   * Returns either the fresh body or the stored one from the first attempt.
   *
   * <p>The replay is written back as raw JSON rather than deserialised and re-serialised: the
   * client must receive byte-for-byte what it would have received the first time, including any
   * field a later version of the code would no longer produce.
   */
  ResponseEntity<?> respond(IdempotencyService.Result<HoldResponse> result) {
    if (result.replayed()) {
      return ResponseEntity.status(result.status())
          .header("Idempotency-Replayed", "true")
          .contentType(MediaType.APPLICATION_JSON)
          .body(result.replay().body());
    }
    return ResponseEntity.status(result.status()).body(result.body());
  }

  /** Exposed for tests that need the same JSON the controller would produce. */
  ObjectMapper objectMapper() {
    return objectMapper;
  }
}
