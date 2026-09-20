package dev.willcall.waitingroom;

import dev.willcall.platform.web.BuyerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/queue")
public class WaitingRoomController {

  private final WaitingRoomService waitingRoom;

  public WaitingRoomController(WaitingRoomService waitingRoom) {
    this.waitingRoom = waitingRoom;
  }

  /**
   * What a buyer is told about their place.
   *
   * @param beyondInventory true when more people are ahead than there are seats left. Shown
   *     plainly, because letting somebody queue for something that cannot exist is the one failure
   *     a queue is supposed to prevent.
   */
  public record QueueResponse(
      String state,
      long position,
      long queueLength,
      Long estimatedWaitSeconds,
      boolean beyondInventory,
      Instant joinedAt,
      String admissionToken,
      Instant serverTime) {

    static QueueResponse of(QueuePosition position, Instant now) {
      return new QueueResponse(
          position.state().name(),
          position.position(),
          position.queueLength(),
          position.estimatedWaitSeconds(),
          position.beyondInventory(),
          position.joinedAt(),
          position.admissionToken(),
          now);
    }
  }

  /**
   * Joins, or reports the place already held.
   *
   * <p>Not idempotent by {@code Idempotency-Key} but idempotent by nature: joining twice returns
   * the same position and does not move anybody. A phone that double-taps and a tab that reloads
   * both arrive here.
   */
  @PostMapping("/join")
  public ResponseEntity<QueueResponse> join(
      @PathVariable UUID eventId, HttpServletRequest request) {
    String userRef = BuyerIdentity.require(request);
    QueuePosition position = waitingRoom.join(eventId, userRef);
    return ResponseEntity.ok(QueueResponse.of(position, Instant.now()));
  }

  @GetMapping("/me")
  public QueueResponse position(@PathVariable UUID eventId, HttpServletRequest request) {
    String userRef = BuyerIdentity.require(request);
    return QueueResponse.of(waitingRoom.position(eventId, userRef), Instant.now());
  }

  @DeleteMapping("/me")
  public ResponseEntity<Void> leave(@PathVariable UUID eventId, HttpServletRequest request) {
    waitingRoom.leave(eventId, BuyerIdentity.require(request));
    return ResponseEntity.noContent().build();
  }

  /** Queue depth and admitted count, for the organizer dashboard and the load reports. */
  @GetMapping("/stats")
  public QueueStats stats(@PathVariable UUID eventId) {
    return new QueueStats(
        waitingRoom.queueLength(eventId),
        waitingRoom.admittedCount(eventId),
        waitingRoom.findEvent(eventId).map(waitingRoom::rateFor).orElse(0.0));
  }

  public record QueueStats(long waiting, long admitted, double admissionRatePerSecond) {}
}
