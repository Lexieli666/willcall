# Load testing

`load/scripts/*.ts` are k6 scenarios, written in TypeScript and typechecked in CI
(`cd load && npm run typecheck`). They are run through `scripts/run-load.sh`, never directly,
because the runner is what writes the `run-context.md` that makes a result auditable.

```bash
make up                       # three replicas behind the edge proxy
./scripts/run-load.sh smoke   # results land in load/results/<date>/<scenario>-<time>/
```

Each results directory contains:

| File | What it is |
|---|---|
| `run-context.md` | Commit, host, container limits, replica count, target, and whether the run was local or cloud. Generated from the live environment, never typed. |
| `summary.json` | k6's end-of-test summary. Every published percentile comes from this file. |
| `stdout.log` | Full k6 console output, including threshold pass/fail. |
| `exit-code.txt` | k6's exit code. Non-zero means a threshold failed. |
| `verify-invariants.log` | The invariant check run immediately after the load, when the run touched the reservation core. |
| `raw.json.gz` | Per-sample data, present when `WILLCALL_RAW_SAMPLES=1`. |

`RESULTS_SUMMARY.md` maps every number published in `README.md` to the raw file it came from,
and states which targets were not met.

## Environment variables

| Variable | Default | Effect |
|---|---|---|
| `WILLCALL_BASE_URL` | `http://127.0.0.1:8080` | Target. Anything not on loopback is labelled accordingly in the run context. |
| `WILLCALL_RAW_SAMPLES` | `0` | `1` keeps per-sample JSON (large). |
| `WILLCALL_SKIP_INVARIANTS` | `0` | `1` skips the post-run invariant check. Only for scenarios that do not touch the reservation core. |
