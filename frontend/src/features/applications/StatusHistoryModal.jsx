import { useEffect, useState } from 'react'
import { getApplicationHistory } from '../../api/applications'
import { formatStatus } from './constants'

export default function StatusHistoryModal({ application, onClose }) {
  const [entries, setEntries] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    getApplicationHistory(application.id)
      .then((data) => {
        if (!cancelled) setEntries(data)
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load history.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [application.id])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>
          History — {application.companyName} / {application.jobTitle}
        </h2>

        {loading && <p>Loading…</p>}
        {error && <p className="error">{error}</p>}

        {!loading && !error && (
          <ul className="history-list">
            {entries.map((entry) => (
              <li key={entry.id}>
                <span className={`status-badge status-${entry.status.toLowerCase()}`}>
                  {formatStatus(entry.status)}
                </span>
                <span className="history-timestamp">{new Date(entry.changedAt).toLocaleString()}</span>
                {entry.note && <p className="history-note">{entry.note}</p>}
              </li>
            ))}
          </ul>
        )}

        <div className="modal-actions">
          <button type="button" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
