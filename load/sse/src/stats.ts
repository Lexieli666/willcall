/** Percentiles over a sample, computed the way a load report needs them. */
export class Percentiles {
  private readonly samples: number[] = []

  add(value: number): void {
    this.samples.push(value)
  }

  get count(): number {
    return this.samples.length
  }

  /**
   * Nearest-rank percentile.
   *
   * <p>Chosen over interpolation because an interpolated p99 can report a value no observation
   * ever took, which is a strange thing to publish as "the latency at the 99th percentile".
   */
  quantile(q: number): number {
    if (this.samples.length === 0) return Number.NaN
    const sorted = [...this.samples].sort((a, b) => a - b)
    const rank = Math.max(1, Math.ceil(q * sorted.length))
    return sorted[rank - 1] as number
  }

  summary(): {
    count: number
    min: number
    p50: number
    p90: number
    p95: number
    p99: number
    max: number
    mean: number
  } {
    if (this.samples.length === 0) {
      return { count: 0, min: NaN, p50: NaN, p90: NaN, p95: NaN, p99: NaN, max: NaN, mean: NaN }
    }
    const sorted = [...this.samples].sort((a, b) => a - b)
    const sum = sorted.reduce((total, value) => total + value, 0)
    return {
      count: sorted.length,
      min: sorted[0] as number,
      p50: this.quantile(0.5),
      p90: this.quantile(0.9),
      p95: this.quantile(0.95),
      p99: this.quantile(0.99),
      max: sorted[sorted.length - 1] as number,
      mean: sum / sorted.length,
    }
  }
}
