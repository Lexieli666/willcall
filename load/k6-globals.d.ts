/**
 * k6 provides a console object that the @types/k6 package does not declare, because it is neither
 * the DOM's nor Node's. Declaring it here is narrower than pulling in the DOM lib, which would
 * also make `document` and `window` typecheck in scripts that have neither.
 */
declare const console: {
  log(...args: unknown[]): void
  info(...args: unknown[]): void
  warn(...args: unknown[]): void
  error(...args: unknown[]): void
  debug(...args: unknown[]): void
}
