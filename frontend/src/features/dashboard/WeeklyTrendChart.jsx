function formatWeekLabel(iso) {
  const date = new Date(`${iso}T00:00:00`)
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

export default function WeeklyTrendChart({ weeklyTrend }) {
  const max = Math.max(1, ...weeklyTrend.map((w) => w.count))

  return (
    <div className="chart-card">
      <h3>Applications per week</h3>
      <div className="vbar-chart">
        {weeklyTrend.map((point) => {
          const pct = (point.count / max) * 100
          return (
            <div className="vbar-col" key={point.weekStart}>
              <span className="vbar-value">{point.count > 0 ? point.count : ''}</span>
              <div className="vbar-track">
                <div className="vbar-fill" style={{ height: `${pct}%` }} />
              </div>
              <span className="vbar-label">{formatWeekLabel(point.weekStart)}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
