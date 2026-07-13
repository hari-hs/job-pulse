import { formatStatus } from '../applications/constants'

// Fixed pipeline order -- this IS the ordinal sequence the color ramp encodes.
const STATUS_ORDER = [
  'APPLIED',
  'ONLINE_ASSESSMENT',
  'PHONE_SCREEN',
  'ONSITE',
  'OFFER',
  'REJECTED',
  'WITHDRAWN',
]

export default function StatusBreakdownChart({ statusBreakdown }) {
  const max = Math.max(1, ...STATUS_ORDER.map((s) => statusBreakdown[s] ?? 0))

  return (
    <div className="chart-card">
      <h3>By status</h3>
      <div className="hbar-chart">
        {STATUS_ORDER.map((status, i) => {
          const count = statusBreakdown[status] ?? 0
          const pct = (count / max) * 100
          return (
            <div className="hbar-row" key={status}>
              <span className="hbar-label">{formatStatus(status)}</span>
              <div className="hbar-track">
                <div
                  className="hbar-fill"
                  style={{ width: `${pct}%`, background: `var(--chart-ordinal-${i + 1})` }}
                />
              </div>
              <span className="hbar-value">{count}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
