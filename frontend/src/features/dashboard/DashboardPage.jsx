import { useEffect, useState } from 'react'
import { getDashboardSummary } from '../../api/dashboard'
import StatTile from './StatTile'
import StatusBreakdownChart from './StatusBreakdownChart'
import WeeklyTrendChart from './WeeklyTrendChart'

export default function DashboardPage() {
  const [summary, setSummary] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    getDashboardSummary()
      .then(setSummary)
      .catch(() => setError('Failed to load dashboard.'))
  }, [])

  if (error) {
    return <p className="error">{error}</p>
  }
  if (!summary) {
    return <p>Loading…</p>
  }

  const responsePct = Math.round(summary.responseRate * 100)

  return (
    <div>
      <div className="stat-row">
        <StatTile label="Total applications" value={summary.totalApplications} />
        <StatTile label="Response rate" value={`${responsePct}%`} />
      </div>
      <div className="chart-grid">
        <StatusBreakdownChart statusBreakdown={summary.statusBreakdown} />
        <WeeklyTrendChart weeklyTrend={summary.weeklyTrend} />
      </div>
    </div>
  )
}
