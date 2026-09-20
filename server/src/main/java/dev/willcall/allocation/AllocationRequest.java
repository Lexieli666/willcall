package dev.willcall.allocation;

import java.util.List;
import java.util.UUID;

/**
 * What a buyer asked for.
 *
 * <p>Exactly one of {@code seatIds} and {@code quantity} is meaningful, decided by {@code mode}.
 * They are not two overloads of one field because "give me these three seats" and "give me any
 * three together" fail for different reasons and produce different error codes.
 */
public record AllocationRequest(UUID eventId, Mode mode, List<UUID> seatIds, int quantity) {

  public enum Mode {
    /** The buyer picked seats on the map. All of them, or none. */
    EXACT,
    /** Any N seats, adjacency not required. */
    BEST_AVAILABLE,
    /** N seats next to each other in one row. */
    BEST_AVAILABLE_TOGETHER
  }

  public static AllocationRequest exact(UUID eventId, List<UUID> seatIds) {
    return new AllocationRequest(eventId, Mode.EXACT, List.copyOf(seatIds), seatIds.size());
  }

  public static AllocationRequest bestAvailable(UUID eventId, int quantity) {
    return new AllocationRequest(eventId, Mode.BEST_AVAILABLE, List.of(), quantity);
  }

  public static AllocationRequest together(UUID eventId, int quantity) {
    return new AllocationRequest(eventId, Mode.BEST_AVAILABLE_TOGETHER, List.of(), quantity);
  }
}
