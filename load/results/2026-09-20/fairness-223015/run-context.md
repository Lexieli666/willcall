# Run context: fairness

**local Docker Compose, not AWS**

| Field | Value |
|---|---|
| Started (UTC) | 2026-09-20 22:31:33Z |
| Arrivals | 10000 |
| Seats | 5000 |
| Admission rate | 400/s |
| Git commit | `806f9176aa3e91c438d026699297365e93ffb150` |
| Application replicas | 3 |

## Method

- The inversion rate is Kendall's tau distance, normalised: of all pairs of admitted buyers, the share admitted in the opposite order to their arrival. 0% is strict FIFO; random order tends to 50%.
- Computed in SQL from the `admissions` table, which records the arrival time Redis assigned and the admission time PostgreSQL recorded. Computing it inside the load script would use the times the script *sent* requests, which is not the same question.
- Admission happens in batches across three replicas, so some inversion is expected by construction. See docs/adr/0010-fairness-policy.md.
