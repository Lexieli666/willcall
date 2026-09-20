/**
 * Formatters are cached per (locale, currency).
 *
 * <p>Constructing an `Intl.NumberFormat` is expensive — it builds a locale-aware formatter from
 * ICU data. A five-thousand-seat map calls this once per seat while building accessible names,
 * and constructing five thousand formatters was measured as the single largest cost in the map's
 * render, pushing it over the 120 ms budget. Caching them brought it back well under.
 */
const formatters = new Map<string, Intl.NumberFormat>()

function formatterFor(currency: string, locale: string | undefined): Intl.NumberFormat {
  const key = `${locale ?? ''}|${currency}`
  let formatter = formatters.get(key)
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, { style: 'currency', currency })
    formatters.set(key, formatter)
  }
  return formatter
}

/**
 * Formats cents for display.
 *
 * <p>Integer cents everywhere, never a float: 0.1 + 0.2 is not 0.3, and a ticket price that is
 * off by a cent in the total is a support ticket.
 */
export function formatMoney(cents: number, currency = 'USD', locale?: string): string {
  return formatterFor(currency, locale).format(cents / 100)
}

/** "2:05" from 125 seconds. Used by the hold countdown, where minutes are what a buyer reads. */
export function formatCountdown(totalSeconds: number): string {
  const clamped = Math.max(0, Math.floor(totalSeconds))
  const minutes = Math.floor(clamped / 60)
  const seconds = clamped % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

/** "2 minutes 5 seconds", for a screen reader, where "2:05" is read as a time of day. */
export function spokenCountdown(totalSeconds: number): string {
  const clamped = Math.max(0, Math.floor(totalSeconds))
  const minutes = Math.floor(clamped / 60)
  const seconds = clamped % 60
  const parts: string[] = []
  if (minutes > 0) parts.push(`${minutes} minute${minutes === 1 ? '' : 's'}`)
  if (seconds > 0 || minutes === 0) parts.push(`${seconds} second${seconds === 1 ? '' : 's'}`)
  return parts.join(' ')
}
