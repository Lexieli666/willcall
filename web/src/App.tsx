import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ConfirmationPage } from './routes/ConfirmationPage'
import { EventPage } from './routes/EventPage'
import { EventsPage } from './routes/EventsPage'
import { OrganizerPage } from './routes/OrganizerPage'
import { SystemStatusPanel } from './components/SystemStatusPanel'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Seat state arrives over Server-Sent Events, so polling would add load without adding
      // freshness. The queries here are for data that does not change during a sale.
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 30_000,
    },
  },
})

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Layout>
          <Routes>
            <Route path="/" element={<EventsPage />} />
            <Route path="/events/:eventId" element={<EventPage />} />
            <Route path="/orders/:orderId" element={<ConfirmationPage />} />
            <Route path="/organizer" element={<OrganizerPage />} />
            <Route path="/status" element={<StatusPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </Layout>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

function StatusPage() {
  return (
    <>
      <h1>Service status</h1>
      <section className="wc-card" aria-labelledby="status-heading">
        <h2 id="status-heading">Dependencies</h2>
        <SystemStatusPanel />
      </section>
    </>
  )
}

function NotFoundPage() {
  return (
    <>
      <h1>No such page</h1>
      <p>
        The address you followed does not match anything here. <a href="/">Back to the events</a>.
      </p>
    </>
  )
}
