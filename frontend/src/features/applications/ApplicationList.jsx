import { APPLICATION_STATUSES, formatStatus } from './constants'

export default function ApplicationList({ applications, onEdit, onDelete, onStatusChange, onViewHistory }) {
  return (
    <table className="application-table">
      <thead>
        <tr>
          <th>Company</th>
          <th>Role</th>
          <th>Status</th>
          <th>Applied</th>
          <th>Location</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {applications.map((app) => (
          <tr key={app.id}>
            <td>{app.companyName}</td>
            <td>{app.jobTitle}</td>
            <td>
              <select
                className={`status-select status-${app.status.toLowerCase()}`}
                value={app.status}
                onChange={(e) => onStatusChange(app, e.target.value)}
              >
                {APPLICATION_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {formatStatus(s)}
                  </option>
                ))}
              </select>
            </td>
            <td>{app.appliedDate || '—'}</td>
            <td>{app.location || '—'}</td>
            <td className="row-actions">
              <button type="button" onClick={() => onViewHistory(app)}>
                History
              </button>
              <button type="button" onClick={() => onEdit(app)}>
                Edit
              </button>
              <button type="button" className="danger" onClick={() => onDelete(app.id)}>
                Delete
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
