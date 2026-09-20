# Segment tree against linear scan

Derived from `results.json` by `scripts/record-jmh.sh`. Nothing here is typed by hand.

## Search: finding the first run of N adjacent free seats

| seats/row | occupancy | seats wanted | linear scan (ns) | segment tree (ns) | winner | ratio |
|---:|---:|---:|---:|---:|---|---:|
| 200 | 10% | 2 | 2.0 ± 0.0 | 5.7 ± 0.2 | linear scan | 2.9x |
| 200 | 10% | 4 | 2.7 ± 0.7 | 5.7 ± 0.6 | linear scan | 2.1x |
| 200 | 50% | 2 | 3.0 ± 0.2 | 6.4 ± 0.5 | linear scan | 2.2x |
| 200 | 50% | 4 | 30.2 ± 0.5 | 5.6 ± 0.4 | segment tree | 5.4x |
| 200 | 90% | 2 | 27.4 ± 1.7 | 6.0 ± 0.4 | segment tree | 4.6x |
| 200 | 90% | 4 | 44.5 ± 0.9 | 0.6 ± 0.0 | segment tree | 77.5x |
| 2,000 | 10% | 2 | 2.0 ± 0.0 | 8.0 ± 0.5 | linear scan | 4.1x |
| 2,000 | 10% | 4 | 2.7 ± 0.1 | 7.1 ± 0.3 | linear scan | 2.6x |
| 2,000 | 50% | 2 | 2.9 ± 0.4 | 7.8 ± 0.0 | linear scan | 2.7x |
| 2,000 | 50% | 4 | 30.9 ± 2.5 | 8.4 ± 0.7 | segment tree | 3.7x |
| 2,000 | 90% | 2 | 27.1 ± 1.9 | 10.2 ± 0.9 | segment tree | 2.7x |
| 2,000 | 90% | 4 | 473.1 ± 7.7 | 0.6 ± 0.0 | segment tree | 826.8x |
| 20,000 | 10% | 2 | 2.0 ± 0.0 | 10.2 ± 1.3 | linear scan | 5.2x |
| 20,000 | 10% | 4 | 2.7 ± 0.4 | 8.6 ± 0.1 | linear scan | 3.2x |
| 20,000 | 50% | 2 | 2.8 ± 0.1 | 7.9 ± 0.4 | linear scan | 2.8x |
| 20,000 | 50% | 4 | 31.3 ± 1.4 | 10.8 ± 0.4 | segment tree | 2.9x |
| 20,000 | 90% | 2 | 26.1 ± 0.8 | 10.9 ± 1.3 | segment tree | 2.4x |
| 20,000 | 90% | 4 | 1213.5 ± 165.6 | 10.6 ± 0.2 | segment tree | 114.0x |

## The cost the tree pays for that

| seats/row | segment tree update (ns) | segment tree build (ns) |
|---:|---:|---:|
| 200 | 61 | 1,638 |
| 2,000 | 122 | 15,847 |
| 20,000 | 159 | 188,929 |

## Run context

**local workstation, not AWS**

| Field | Value |
|---|---|
| Recorded (UTC) | 2026-09-20 19:56:33Z |
| Git commit | `502d61c306364f2cebcb42577f92e0e802248820` |
| Host kernel | Linux 6.6.114.1-microsoft-standard-WSL2 |
| Host logical cores | 32 |
| Host memory | 31Gi |
| JMH | 1.37, 1 fork, 3 warm-up and 5 measurement iterations of 1 s |
| Seed | fixed at 20260920, so every machine benchmarks the same input |

## What this actually says

The expectation going in was "the linear scan wins below about 500 seats per row, the tree wins
at 2,000 and above". **That is not what the measurement shows.** The crossover is not a seat
count at all; it is an occupancy level.

- **At 10% occupancy the linear scan wins at every size**, including 20,000 seats per row, and by
  2-5x. It finds a run within the first handful of seats and stops, so its cost is independent of
  the row length. The tree pays for a descent it did not need.
- **At 50% occupancy it depends on how many seats are wanted.** Two adjacent seats are easy to
  find, so the scan still wins. Four adjacent seats are rare enough that the scan walks a long
  way, and the tree wins by 3-5x at every size.
- **At 90% occupancy the tree wins everywhere**, and the margin grows with the row: 4.6x at 200
  seats, 2.7x at 2,000, and at four-seats-wanted the scan degrades to 473 ns at 2,000 seats and
  1,214 ns at 20,000 while the tree stays around 10 ns.
- **The tree's best case is saying no.** Where no run of four exists at all, the tree answers in
  0.6 ns — a single comparison against the root's `maxFreeRun`, no descent. The scan has to walk
  the entire row to reach the same conclusion. That is the 827x column, and it is the case a
  flash sale spends most of its time in: near the end of a sell-out, almost every request for
  adjacent seats is a request that cannot be satisfied.

**The costs the tree pays.** An update is 59-177 ns against roughly 1 ns to flip a boolean, and
construction is 1.6 µs at 200 seats, 15.7 µs at 2,000 and 172 µs at 20,000. So the tree is worth
it only when queries outnumber updates, which in a flash sale they do by a wide margin — every
buyer asking for "three together" is a query, and only the ones who succeed are an update.

**What this means for the implementation.** The tree is used as an in-memory hint that the
database then verifies, built lazily on the first contiguous request for an event
(`ContiguousSeatIndex`). Construction cost is therefore paid once per event per replica, not per
request, and the fallback to the SQL scan is an ordinary path rather than an error path. The
`willcall_allocation_index_hits` and `willcall_allocation_index_misses` counters are what say
whether it is earning its memory in production.

**What would make this wrong.** The benchmark uses uniformly random occupancy. A real seat map
does not sell uniformly: buyers cluster at the front and centre, which produces long contiguous
free blocks at the edges for much longer than a uniform model predicts. That would favour the
linear scan for longer than these numbers suggest. Measuring against a realistic sale pattern is
listed as a limitation rather than being quietly ignored.
