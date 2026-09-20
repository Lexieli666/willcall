#!/usr/bin/env node
/**
 * Per-route gzipped JavaScript budget. The specification puts the ceiling at 180 KB per route;
 * this script sums the entry chunk plus everything Vite says it imports, gzips each file, and
 * fails if any route exceeds the budget. Measuring the raw bytes instead would understate what
 * a user on a phone actually waits for.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { gzipSync } from 'node:zlib'

const distDir = resolve(process.argv[2] ?? 'dist')
const budgetKb = Number(process.env.WILLCALL_JS_BUDGET_KB ?? 180)

function walk(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else out.push(full)
  }
  return out
}

let manifest = null
for (const candidate of ['.vite/manifest.json', 'manifest.json']) {
  try {
    manifest = JSON.parse(readFileSync(join(distDir, candidate), 'utf8'))
    break
  } catch {
    /* try the next location */
  }
}

const gzipOf = (file) => gzipSync(readFileSync(file), { level: 9 }).length

let failed = false

if (manifest) {
  const entries = Object.entries(manifest).filter(([, chunk]) => chunk.isEntry)
  for (const [name, chunk] of entries) {
    const seen = new Set()
    const collect = (key) => {
      if (seen.has(key)) return
      seen.add(key)
      const c = manifest[key]
      if (!c) return
      for (const imported of c.imports ?? []) collect(imported)
    }
    collect(name)
    let bytes = 0
    for (const key of seen) {
      const file = manifest[key]?.file
      if (file?.endsWith('.js')) bytes += gzipOf(join(distDir, file))
    }
    const kb = bytes / 1024
    const verdict = kb <= budgetKb ? 'ok' : 'OVER BUDGET'
    console.log(`${verdict}: route ${name} -> ${kb.toFixed(1)} KB gzipped (budget ${budgetKb} KB)`)
    if (kb > budgetKb) failed = true
  }
} else {
  // No manifest: fall back to the total of every emitted .js file, which is a strictly
  // harsher test than per-route, so a pass here is still a pass.
  const bytes = walk(distDir)
    .filter((f) => f.endsWith('.js'))
    .reduce((sum, f) => sum + gzipOf(f), 0)
  const kb = bytes / 1024
  console.log(`no manifest; total JS ${kb.toFixed(1)} KB gzipped (budget ${budgetKb} KB)`)
  if (kb > budgetKb) failed = true
}

process.exit(failed ? 1 : 0)
