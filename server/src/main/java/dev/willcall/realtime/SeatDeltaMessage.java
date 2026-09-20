package dev.willcall.realtime;

import dev.willcall.catalog.domain.SeatStatus;
import java.util.List;
import java.util.UUID;

/**
 * The payload of a {@code delta} frame, as documented in docs/realtime-protocol.md.
 *
 * <p><b>{@code fromSequence} exists because coalescing and gap detection fight each other.</b> A
 * window that batches three seat changes consumes three sequence numbers but sends one frame. With
 * only a single {@code sequence} field the frame would carry the highest of the three, and a client
 * holding N that received a frame stamped N+3 would correctly conclude that two messages were
 * missing — from every multi-seat hold, forever. Carrying the range makes the two mechanisms
 * compatible: the client checks that {@code fromSequence} is exactly its cursor plus one, and then
 * advances to {@code sequence}.
 *
 * <p>This was found by an end-to-end test that injected a real gap and then noticed the client was
 * reporting gaps that had not happened.
 */
public record SeatDeltaMessage(
    long fromSequence, long sequence, List<SeatChange> changes, SeatCounts counts) {

  /**
   * One seat's new state.
   *
   * @param version the seat's row version; a client applies a change only when this exceeds the
   *     version it already holds, which is what makes duplicate and out-of-order delivery harmless
   * @param committedAt when the transaction that made this change committed. Present so that
   *     propagation latency can be measured from the commit to the browser, which is what the
   *     protocol's budget is about. Measuring from when the fan-out picked the row up would quietly
   *     exclude the relay's own queueing — part of what a buyer actually waits for — and would make
   *     the published percentile smaller than the truth. Null on a snapshot, where there is no
   *     single commit to point at.
   */
  public record SeatChange(
      UUID id, SeatStatus status, long version, java.time.Instant committedAt) {

    public SeatChange(UUID id, SeatStatus status, long version) {
      this(id, status, version, null);
    }
  }

  /** Carried on every delta so the "seats left" figure can never drift from the map. */
  public record SeatCounts(int available, int held, int sold) {}
}
