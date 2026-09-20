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

# Kendall's tau distance, normalised, computed in the database. Auditable from a psql prompt, which
# a number computed inside a load script is not.
#
# The normalised rate alone is close to useless at this size: five swapped pairs among eighteen
# thousand admissions is 0.000003%, which rounds to zero and reads like a number nobody measured.
# Displacement is the figure a buyer would recognise - how many places they moved - so it is
# reported alongside, and the raw inversion count is reported unrounded.
docker exec -i willcall-postgres-1 psql -U willcall -d willcall -At -F'|' <<'SQL' > "$OUT_DIR/inversion.txt"
with ordered as (
  select event_id,
         user_ref,
         row_number() over (partition by event_id order by joined_at, user_ref) as arrival_rank,
         row_number() over (partition by event_id order by admitted_at, id)     as admit_rank
  from admissions
),
pairs as (
  select a.event_id,
         count(*)                        as inversions,
         count(distinct a.user_ref)      as buyers_ahead_overtaken
  from ordered a
  join ordered b
    on a.event_id = b.event_id
   and a.arrival_rank < b.arrival_rank
   and a.admit_rank   > b.admit_rank
  group by a.event_id
),
displacement as (
  select event_id,
         max(abs(admit_rank - arrival_rank))                                       as max_displacement,
         percentile_disc(0.99) within group (order by abs(admit_rank - arrival_rank))
                                                                                   as p99_displacement,
         count(*) filter (where admit_rank <> arrival_rank)                        as moved_at_all
  from ordered
  group by event_id
),
totals as (
  select event_id, count(*) as n from ordered group by event_id
)
select totals.event_id,
       totals.n                                                 as admitted_count,
       coalesce(pairs.inversions, 0)                             as inversions,
       case when totals.n < 2 then 0
            else round(coalesce(pairs.inversions, 0)::numeric
                       / (totals.n * (totals.n - 1) / 2) * 100, 8)
       end                                                       as inversion_rate_percent,
       coalesce(pairs.buyers_ahead_overtaken, 0)                 as buyers_overtaken,
       coalesce(displacement.max_displacement, 0)                as max_displacement,
       coalesce(displacement.p99_displacement, 0)                as p99_displacement,
       coalesce(displacement.moved_at_all, 0)                    as moved_at_all
from totals
left join pairs on pairs.event_id = totals.event_id
left join displacement on displacement.event_id = totals.event_id
order by totals.n desc;
SQL

python3 - "$OUT_DIR" <<'FAIRJSON'
import json, os, sys

out_dir = sys.argv[1]
fields = ['eventId', 'admittedCount', 'inversions', 'inversionRatePercent',
          'buyersOvertaken', 'maxDisplacement', 'p99Displacement', 'movedAtAll']
rows = []
for line in open(os.path.join(out_dir, 'inversion.txt'), errors='replace'):
    parts = line.strip().split('|')
    if len(parts) != len(fields):
        continue
    row = dict(zip(fields, parts))
    row['admittedCount'] = int(row['admittedCount'])
    row['inversions'] = int(row['inversions'])
    row['inversionRatePercent'] = float(row['inversionRatePercent'])
    for key in ('buyersOvertaken', 'maxDisplacement', 'p99Displacement', 'movedAtAll'):
        row[key] = int(row[key])
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
    print(f"admitted out of rank : {b['movedAtAll']:,}")
    print(f"displacement p99/max : {b['p99Displacement']:,} / {b['maxDisplacement']:,} places")
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
