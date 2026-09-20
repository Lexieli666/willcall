import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Layout } from './components/Layout'
import { SystemStatusPanel } from './components/SystemStatusPanel'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Seat state arrives over SSE, so polling would only add load without adding freshness.
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 5_000,
    },
  },
})

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Layout>
        <h1>Willcall</h1>
        <p>
          Willcall sells a fixed inventory of seats to a crowd that arrives all at once. It admits
          buyers from a fair waiting room, holds seats with an expiring timer, confirms checkout
          idempotently, and pushes live seat state to every open browser.
        </p>
        <div className="wc-grid-2">
          <section className="wc-card" aria-labelledby="status-heading">
            <h2 id="status-heading">Service status</h2>
            <SystemStatusPanel />
          </section>
          <section className="wc-card" aria-labelledby="scope-heading">
            <h2 id="scope-heading">What this deployment does</h2>
            <ul>
              <li>Fair waiting room with live position</li>
              <li>Exact-seat and best-available-together acquisition</li>
              <li>Expiring holds and idempotent checkout</li>
              <li>Live seat updates over Server-Sent Events</li>
              <li>Keyboard- and screen-reader-operable seat map</li>
            </ul>
          </section>
        </div>
      </Layout>
    </QueryClientProvider>
  )
}
