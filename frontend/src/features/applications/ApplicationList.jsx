import { formatStatus } from './constants'

export default function ApplicationList({ applications, onEdit, onDelete }) {
  if (applications.length === 0) {
    return <p className="empty-state">No applications yet. Add your first one above.</p>
  }

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
              <span className={`status-badge status-${app.status.toLowerCase()}`}>
                {formatStatus(app.status)}
              </span>
            </td>
            <td>{app.appliedDate || '—'}</td>
            <td>{app.location || '—'}</td>
            <td className="row-actions">
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
