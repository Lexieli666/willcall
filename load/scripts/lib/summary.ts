/**
 * A machine-readable end-of-test summary alongside k6's own.
 *
 * <p>k6's `--summary-export` writes the metrics but not the context a reader needs to interpret
 * them: which scenario, how long, what the thresholds were and whether each one held. This adds a
 * second file carrying that, so `RESULTS_SUMMARY.md` can be generated from the raw output rather
 * than assembled by hand.
 */
export interface K6Summary {
  metrics: Record<string, Record<string, number> & { thresholds?: Record<string, boolean> }>
  state?: { testRunDurationMs?: number }
}

export function summaryFile(
  scenario: string,
  data: K6Summary,
  extra: Record<string, unknown> = {},
): Record<string, string> {
  const thresholds: Record<string, boolean> = {}
  for (const [metric, values] of Object.entries(data.metrics ?? {})) {
    const declared = (values as { thresholds?: Record<string, boolean> }).thresholds
    if (!declared) continue
    for (const [expression, passed] of Object.entries(declared)) {
      // k6 reports `{ ok: boolean }` in some versions and a bare boolean in others.
      thresholds[`${metric}: ${expression}`] =
        typeof passed === 'boolean' ? passed : Boolean((passed as { ok?: boolean }).ok)
    }
  }

  const report = {
    scenario,
    durationMs: data.state?.testRunDurationMs ?? null,
    thresholds,
    allThresholdsPassed: Object.values(thresholds).every(Boolean),
    ...extra,
  }

  // Not '.', which is the repository root when a scenario is run by hand. Two dashboard captures
  // left k6-summary.json tracked at the top level that way, and the hygiene check refused the
  // push. A run with no output directory is an ad-hoc run, and its summary belongs in /tmp.
  const out = __ENV.SCENARIO_OUT || '/tmp/willcall-scenario'
  return {
    [`${out}/k6-summary.json`]: JSON.stringify({ report, metrics: data.metrics }, null, 2),
    stdout: `\nthresholds: ${Object.entries(thresholds)
      .map(([name, passed]) => `${passed ? 'PASS' : 'FAIL'} ${name}`)
      .join('\n            ')}\n`,
  }
}
