import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { fetchOrder } from '../lib/reservations'
import { formatMoney } from '../lib/money'

export function ConfirmationPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const { data, isPending, isError } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => fetchOrder(orderId as string),
    enabled: Boolean(orderId),
  })

  if (isPending) return <p>Loading your order…</p>
  if (isError || !data) {
    return (
      <p role="alert" className="wc-message wc-message--error">
        That order could not be found.
      </p>
    )
  }

  const confirmed = data.status === 'CONFIRMED'

  return (
    <>
      <h1>{confirmed ? 'Your seats are confirmed' : 'Order status'}</h1>

      <div className={`wc-message ${confirmed ? 'wc-message--success' : 'wc-message--error'}`} role="status">
        <p>
          Order <code>{data.orderId}</code> is <strong>{data.status}</strong>.
        </p>
      </div>

      <dl className="wc-keyvalue">
        <dt>Seats</dt>
        <dd>{data.seatIds.length}</dd>
        <dt>Total</dt>
        <dd>{formatMoney(data.totalCents, data.currency)}</dd>
        {data.paymentReference && (
          <>
            <dt>Payment reference</dt>
            <dd>
              <code>{data.paymentReference}</code>
            </dd>
          </>
        )}
        {data.confirmedAt && (
          <>
            <dt>Confirmed at</dt>
            <dd>{new Date(data.confirmedAt).toLocaleString()}</dd>
          </>
        )}
      </dl>

      <p>
        No real payment was taken and no real ticket was issued. Willcall is a load-tested
        demonstration service.
      </p>

      <p>
        <Link to="/">Back to the events</Link>
      </p>
    </>
  )
}
