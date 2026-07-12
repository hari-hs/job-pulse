import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../context/AuthContext'
import { createApplication, deleteApplication, getApplications, updateApplication } from '../../api/applications'
import ApplicationList from './ApplicationList'
import ApplicationForm from './ApplicationForm'

export default function ApplicationsPage() {
  const { email, fullName, logout } = useAuth()
  const [applications, setApplications] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState(null) // null | 'new' | application object

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setApplications(await getApplications())
    } catch {
      setError('Failed to load applications.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function handleSave(formData) {
    if (editing === 'new') {
      await createApplication(formData)
    } else {
      await updateApplication(editing.id, formData)
    }
    setEditing(null)
    await load()
  }

  async function handleDelete(id) {
    if (!window.confirm('Delete this application?')) {
      return
    }
    await deleteApplication(id)
    await load()
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1>Job Pulse</h1>
        <div className="page-header-user">
          <span>{fullName || email}</span>
          <button type="button" onClick={logout}>
            Log out
          </button>
        </div>
      </header>

      <div className="toolbar">
        <button type="button" onClick={() => setEditing('new')}>
          + New Application
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Loading…</p>
      ) : (
        <ApplicationList applications={applications} onEdit={setEditing} onDelete={handleDelete} />
      )}

      {editing && (
        <ApplicationForm
          initial={editing === 'new' ? null : editing}
          onSave={handleSave}
          onCancel={() => setEditing(null)}
        />
      )}
    </div>
  )
}
