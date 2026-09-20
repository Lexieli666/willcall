#!/usr/bin/env bash
# Measure how far admission order drifts from arrival order under a burst.
#
# The inversion rate is computed in SQL from the admissions table, not from the load script's own
# view: the script knows when it sent a request, the database knows when the buyer actually joined
# and when they were actually admitted, and only the second pair can answer the question.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BASE_URL="${WILLCALL_BASE_URL:-http://127.0.0.1:8080}"
DATE="$(date -u +%Y-%m-%d)"
OUT_DIR="load/results/${DATE}/fairness-$(date -u +%H%M%S)"
mkdir -p "$OUT_DIR"

printf 'fairness run\n'
printf 'results: %s\n\n' "$OUT_DIR"

set +e
WILLCALL_BASE_URL="$BASE_URL" SCENARIO_OUT="$OUT_DIR" \
  k6 run \
    --summary-export "$OUT_DIR/summary.json" \
    --summary-trend-stats 'min,med,avg,p(90),p(95),p(99),max' \
    -e "BASE_URL=$BASE_URL" \
    -e "SCENARIO_OUT=$OUT_DIR" \
    -e "FAIRNESS_ARRIVALS=${FAIRNESS_ARRIVALS:-10000}" \
    -e "FAIRNESS_SEATS=${FAIRNESS_SEATS:-5000}" \
    -e "FAIRNESS_ADMISSION_RATE=${FAIRNESS_ADMISSION_RATE:-400}" \
    load/scripts/fairness.ts 2>&1 | tee "$OUT_DIR/stdout.log"
K6_EXIT=${PIPESTATUS[0]}
set -e

# The event this run created, taken from the scenario's own output.
#
# Without this the SQL below measured whichever event had the most admissions in the table, which
# after a second run was the first run's event: the published inversion rate described a run from
# forty minutes earlier under different code, and the numbers looked entirely plausible. The script
# fails rather than falls back, because "measured the wrong event" is indistinguishable from
# "measured the right one" once the number is in a table.
EVENT_ID="$(grep -oE '\(event [0-9a-f-]{36}\)' "$OUT_DIR/stdout.log" | head -1 | tr -d '()' | awk '{print $2}')"
if [ -z "$EVENT_ID" ]; then
  printf 'FAILED: could not find the event id in %s/stdout.log\n' "$OUT_DIR" >&2
  exit 2
fi
printf 'measuring admissions for event %s\n' "$EVENT_ID"

# How far admission order drifted from arrival order, computed in the database. Auditable from a
# psql prompt, which a number computed inside a load script is not.
#
# Counted on the timestamps themselves, with strict inequalities, and not on row_number() ranks.
# Admission is batched - this run admitted 16,065 buyers at 316 distinct instants, about fifty at a
# time - so within a batch there is no order at all. Ranking forces one anyway, breaking ties on a
# random UUID, and then counts the arbitrary result as unfairness. Two people admitted in the same
# batch were not admitted before or after each other, and the measure should not pretend otherwise.
#
# The batch size is reported alongside, because it is the real answer to "how unfair can this be":
# arrival order is honoured between batches and undefined within one.
docker exec -i willcall-postgres-1 psql -U willcall -d willcall -At -F'|' \
  -v event="'$EVENT_ID'" <<'SQL' > "$OUT_DIR/inversion.txt"
with admitted as (
  select user_ref, joined_at, admitted_at
  from admissions
  where event_id = :event::uuid and admitted_at is not null
),
-- Strictly out of order: joined before, admitted after. No ties, no tie-break, no invention.
inversions as (
  select count(*) as inversions, count(distinct a.user_ref) as buyers_overtaken
  from admitted a
  join admitted b
    on a.joined_at   < b.joined_at
   and a.admitted_at > b.admitted_at
),
batches as (
  select count(*) as batches, max(n) as largest_batch,
         round(avg(n), 1) as mean_batch
  from (select admitted_at, count(*) as n from admitted group by admitted_at) b
),
totals as (select count(*) as n from admitted)
select :event::uuid                                              as event_id,
       totals.n                                                  as admitted_count,
       inversions.inversions,
       case when totals.n < 2 then 0
            else round(inversions.inversions::numeric
                       / (totals.n * (totals.n - 1) / 2) * 100, 8)
       end                                                       as inversion_rate_percent,
       inversions.buyers_overtaken,
       batches.batches                                           as admission_batches,
       batches.largest_batch,
       batches.mean_batch
from totals, inversions, batches;
SQL

python3 - "$OUT_DIR" <<'FAIRJSON'
import json, os, sys

out_dir = sys.argv[1]
fields = ['eventId', 'admittedCount', 'inversions', 'inversionRatePercent',
          'buyersOvertaken', 'admissionBatches', 'largestBatch', 'meanBatch']
rows = []
for line in open(os.path.join(out_dir, 'inversion.txt'), errors='replace'):
    parts = line.strip().split('|')
    if len(parts) != len(fields):
        continue
    row = dict(zip(fields, parts))
    row['admittedCount'] = int(row['admittedCount'])
    row['inversions'] = int(row['inversions'])
    row['inversionRatePercent'] = float(row['inversionRatePercent'])
    for key in ('buyersOvertaken', 'admissionBatches', 'largestBatch'):
        row[key] = int(row[key])
    row['meanBatch'] = float(row['meanBatch'])
    n = row['admittedCount']
    row['totalPairs'] = n * (n - 1) // 2
    rows.append(row)

report = {'events': rows}
if rows:
    report['headline'] = max(rows, key=lambda r: r['admittedCount'])
json.dump(report, open(os.path.join(out_dir, 'fairness.json'), 'w'), indent=2)

if rows:
    b = report['headline']
    print('')
    print(f"admitted buyers      : {b['admittedCount']:,}")
    print(f"out-of-order pairs   : {b['inversions']:,} of {b['totalPairs']:,} possible")
    print(f"FIFO inversion rate  : {b['inversionRatePercent']}%")
    print(f"buyers overtaken     : {b['buyersOvertaken']:,}")
    print(f"admission batches    : {b['admissionBatches']:,} "
          f"(mean {b['meanBatch']}, largest {b['largestBatch']:,})")
    print('order is honoured between batches and undefined within one')
FAIRJSON

{
  printf '# Run context: fairness\n\n'
  printf '**local Docker Compose, not AWS**\n\n'
  printf '| Field | Value |\n|---|---|\n'
  printf '| Started (UTC) | %s |\n' "$(date -u +'%Y-%m-%d %H:%M:%SZ')"
  printf '| Arrivals | %s |\n' "${FAIRNESS_ARRIVALS:-10000}"
  printf '| Seats | %s |\n' "${FAIRNESS_SEATS:-5000}"
  printf '| Admission rate | %s/s |\n' "${FAIRNESS_ADMISSION_RATE:-400}"
  printf '| Git commit | `%s` |\n' "$(git rev-parse --verify HEAD 2>/dev/null || echo unknown)"
  printf '| Application replicas | %s |\n' "$(docker ps --filter 'label=com.docker.compose.project=willcall' --format '{{.Names}}' | grep -c 'app[0-9]' || echo 0)"
  printf '\n## Method\n\n'
  printf -- '- The inversion rate is Kendall'"'"'s tau distance, normalised: of all pairs of admitted buyers, the share admitted in the opposite order to their arrival. 0%% is strict FIFO; random order tends to 50%%.\n'
  printf -- '- Computed in SQL from the `admissions` table, which records the arrival time Redis assigned and the admission time PostgreSQL recorded. Computing it inside the load script would use the times the script *sent* requests, which is not the same question.\n'
  printf -- '- Admission happens in batches across three replicas, so some inversion is expected by construction. See docs/adr/0010-fairness-policy.md.\n'
} > "$OUT_DIR/run-context.md"

printf '\nwritten to %s\n' "$OUT_DIR"
exit "$K6_EXIT"
