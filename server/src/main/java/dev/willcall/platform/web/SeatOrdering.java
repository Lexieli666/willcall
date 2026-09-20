package dev.willcall.platform.web;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One definition of "ascending seat id order", shared by the Java code and matching what PostgreSQL
 * does.
 *
 * <p>This exists because the two disagree by default. {@code UUID.compareTo} compares the most and
 * least significant bits as <em>signed</em> longs, so any UUID with the top bit set sorts before
 * every UUID without it. PostgreSQL compares uuid values as sixteen unsigned bytes, which is the
 * same as lexicographic order on the canonical hex string. A model-based test caught the
 * difference: the service picked the seat PostgreSQL considered lowest and the reference model
 * expected the one Java considered lowest.
 *
 * <p>The consequence is small today, because every lock is ordered by PostgreSQL inside the query
 * that takes it. It would not stay small: the moment a caller sorts ids in Java and then locks them
 * one at a time in that order, two callers with overlapping sets take the same locks in opposite
 * orders and deadlock. Having one comparator that agrees with the database removes that possibility
 * rather than relying on nobody ever writing that loop.
 */
public final class SeatOrdering {

  /** Ascending in the same order PostgreSQL uses for the {@code uuid} type. */
  public static final Comparator<UUID> ASCENDING = Comparator.comparing(UUID::toString);

  private SeatOrdering() {}

  public static List<UUID> sorted(List<UUID> ids) {
    return ids.stream().sorted(ASCENDING).toList();
  }
}
