/**
 * Lighthouse budgets are hard gates, not advice: accessibility must be exactly 100 and
 * desktop performance at least 0.90. The numbers come from section 6 of the specification and
 * are asserted here so a regression fails CI rather than being noticed later.
 */
module.exports = {
  ci: {
    collect: {
      startServerCommand: 'npm run preview',
      startServerReadyPattern: 'Local:',
      url: [process.env.WILLCALL_LHCI_URL || 'http://127.0.0.1:4173/'],
      numberOfRuns: 3,
      settings: {
        preset: 'desktop',
        chromeFlags: '--no-sandbox --headless=new --disable-dev-shm-usage',
      },
    },
    assert: {
      assertions: {
        'categories:accessibility': ['error', { minScore: 1 }],
        'categories:performance': ['error', { minScore: 0.9 }],
        'categories:best-practices': ['warn', { minScore: 0.9 }],
        'largest-contentful-paint': ['error', { maxNumericValue: 1500 }],
        'cumulative-layout-shift': ['error', { maxNumericValue: 0.05 }],
        'total-blocking-time': ['error', { maxNumericValue: 200 }],
        'color-contrast': 'error',
        'uses-responsive-images': 'off',
        'unused-javascript': 'off',
        'csp-xss': 'off',
      },
    },
    upload: { target: 'filesystem', outputDir: './.lighthouseci' },
  },
}
