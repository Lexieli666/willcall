import { useQuery } from '@tanstack/react-query'
import { fetchSystemStatus } from '../lib/api'

type Tone = 'up' | 'down' | 'unknown'

function toneFor(value: string | undefined): Tone {
  if (value === 'UP') return 'up'
  if (value === undefined) return 'unknown'
  return 'down'
}

/**
 * Renders the readiness of the dependencies the reservation core needs. Redis reports
 * DEGRADED rather than DOWN on purpose: losing Redis costs the waiting room, not correctness.
 */
export function SystemStatusPanel() {
  const { data, isPending, isError } = useQuery({
    queryKey: ['system-status'],
    queryFn: fetchSystemStatus,
    refetchInterval: 15_000,
  })

  if (isPending) return <p>Checking service status…</p>
  if (isError) {
    return (
      <p role="status">
        The status endpoint could not be reached. The API may still be starting.
      </p>
    )
  }

  return (
    <ul className="wc-status-list">
      <StatusRow label="Application" value={data.status} tone={toneFor(data.status)} />
      <StatusRow label="PostgreSQL" value={data.postgres} tone={toneFor(data.postgres)} />
      <StatusRow
        label="Redis"
        value={data.redis}
        tone={data.redis === 'UP' ? 'up' : data.redis === 'DEGRADED' ? 'unknown' : 'down'}
      />
      <li>
        Replica <code>{data.replica}</code>, version <code>{data.version}</code>, commit{' '}
        <code>{data.commit}</code>
      </li>
    </ul>
  )
}

function StatusRow({ label, value, tone }: { label: string; value: string; tone: Tone }) {
  return (
    <li>
      {label}: <span className={`wc-badge wc-badge--${tone}`}>{value}</span>
    </li>
  )
}
