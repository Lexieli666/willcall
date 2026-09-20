import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SystemStatusPanel } from './SystemStatusPanel'

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('SystemStatusPanel', () => {
  it('reports Redis as degraded rather than down, because holds stay correct without it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 'UP',
            postgres: 'UP',
            redis: 'DEGRADED',
            replica: 'app-2',
            version: '1.0.0',
            commit: 'abc1234',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    renderWithClient(<SystemStatusPanel />)

    expect(await screen.findByText('DEGRADED')).toBeInTheDocument()
    expect(screen.getByText('app-2')).toBeInTheDocument()
  })

  it('tells the user the API may be starting instead of showing a blank panel', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('nope', { status: 503 })))

    renderWithClient(<SystemStatusPanel />)

    expect(await screen.findByRole('status')).toHaveTextContent(/could not be reached/i)
  })
})
