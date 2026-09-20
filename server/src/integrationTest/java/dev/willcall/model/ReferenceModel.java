package dev.willcall.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An independent model of what the reservation core is supposed to do.
 *
 * <p>Deliberately written without looking at {@code ReservationService}: it works in plain maps
 * over seat indices, has no database, no locks and no transactions, and it is small enough to read
 * in one sitting and agree with. That independence is the entire value. A model that mirrors the
 * implementation's structure would reproduce the implementation's bugs and agree with it happily.
 *
 * <p>The rules it encodes, which are the specification restated:
 *
 * <ul>
 *   <li>A seat can be acquired only from AVAILABLE.
 *   <li>A hold group is all-or-nothing: if any seat in it is unavailable, none are taken.
 *   <li>Exactly one of confirm, cancel and expire wins for a given group; the rest are no-ops.
 *   <li>Expiry releases only seats still held by that group.
 *   <li>A confirmed seat is never released by anything.
 * </ul>
 */
public final class ReferenceModel {

  public enum SeatState {
    AVAILABLE,
    HELD,
    SOLD
  }

  public enum GroupState {
    ACTIVE,
    CONFIRMED,
    CANCELLED,
    EXPIRED
  }

  /** A hold group in the model: which seats, whose, and when it expires (in logical ticks). */
  public record Group(
      String id, String buyer, List<Integer> seats, long expiresAtTick, GroupState state) {
    Group withState(GroupState next) {
      return new Group(id, buyer, seats, expiresAtTick, next);
    }

    Group withExpiry(long tick) {
      return new Group(id, buyer, seats, tick, state);
    }
  }

  private final int seatCount;
  private final Map<Integer, SeatState> seatStates = new HashMap<>();
  private final Map<String, Group> groups = new LinkedHashMap<>();
  private long tick;

  public ReferenceModel(int seatCount) {
    this.seatCount = seatCount;
    for (int i = 0; i < seatCount; i++) seatStates.put(i, SeatState.AVAILABLE);
  }

  public long tick() {
    return tick;
  }

  public void advance(long ticks) {
    tick += ticks;
  }

  public SeatState seat(int index) {
    return seatStates.get(index);
  }

  public List<Integer> seatsInState(SeatState state) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < seatCount; i++) if (seatStates.get(i) == state) out.add(i);
    return out;
  }

  public Optional<Group> group(String id) {
    return Optional.ofNullable(groups.get(id));
  }

  public List<Group> activeGroups() {
    return groups.values().stream().filter(g -> g.state() == GroupState.ACTIVE).toList();
  }

  /**
   * @return true when the hold was granted
   */
  public boolean tryHoldExact(String groupId, String buyer, List<Integer> seats, long ttlTicks) {
    if (seats.isEmpty()) return false;
    if (seats.stream().distinct().count() != seats.size()) return false;
    for (int seat : seats) {
      if (seat < 0 || seat >= seatCount) return false;
      if (seatStates.get(seat) != SeatState.AVAILABLE) return false;
    }
    for (int seat : seats) seatStates.put(seat, SeatState.HELD);
    groups.put(
        groupId, new Group(groupId, buyer, List.copyOf(seats), tick + ttlTicks, GroupState.ACTIVE));
    return true;
  }

  /** Takes the lowest-numbered available seats, matching the implementation's ordering. */
  public Optional<List<Integer>> tryHoldBestAvailable(
      String groupId, String buyer, int quantity, long ttlTicks) {
    List<Integer> available = seatsInState(SeatState.AVAILABLE);
    if (available.size() < quantity) return Optional.empty();
    List<Integer> chosen = available.subList(0, quantity);
    boolean ok = tryHoldExact(groupId, buyer, chosen, ttlTicks);
    return ok ? Optional.of(List.copyOf(chosen)) : Optional.empty();
  }

  /**
   * @return true when this call is the one that confirmed the group
   */
  public boolean confirm(String groupId) {
    Group group = groups.get(groupId);
    if (group == null || group.state() != GroupState.ACTIVE) return false;
    if (group.expiresAtTick() <= tick) return false;
    for (int seat : group.seats()) seatStates.put(seat, SeatState.SOLD);
    groups.put(groupId, group.withState(GroupState.CONFIRMED));
    return true;
  }

  /**
   * @return true when this call is the one that cancelled the group
   */
  public boolean cancel(String groupId) {
    Group group = groups.get(groupId);
    if (group == null || group.state() != GroupState.ACTIVE) return false;
    for (int seat : group.seats()) {
      if (seatStates.get(seat) == SeatState.HELD) seatStates.put(seat, SeatState.AVAILABLE);
    }
    groups.put(groupId, group.withState(GroupState.CANCELLED));
    return true;
  }

  /** Pushes a group's expiry out, the way starting checkout does. */
  public void extend(String groupId, long ticks) {
    Group group = groups.get(groupId);
    if (group == null || group.state() != GroupState.ACTIVE) return;
    groups.put(groupId, group.withExpiry(Math.max(group.expiresAtTick(), tick + ticks)));
  }

  /** Expires everything whose deadline has passed. Returns how many groups it expired. */
  public int sweep() {
    int expired = 0;
    for (Map.Entry<String, Group> entry : new LinkedHashMap<>(groups).entrySet()) {
      Group group = entry.getValue();
      if (group.state() != GroupState.ACTIVE || group.expiresAtTick() > tick) continue;
      for (int seat : group.seats()) {
        if (seatStates.get(seat) == SeatState.HELD) seatStates.put(seat, SeatState.AVAILABLE);
      }
      groups.put(entry.getKey(), group.withState(GroupState.EXPIRED));
      expired++;
    }
    return expired;
  }

  public Map<SeatState, Integer> counts() {
    Map<SeatState, Integer> out = new LinkedHashMap<>();
    for (SeatState state : SeatState.values()) out.put(state, seatsInState(state).size());
    return out;
  }
}
