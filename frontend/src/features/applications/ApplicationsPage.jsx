import { useCallback, useEffect, useState } from 'react'
import {
  changeApplicationStatus,
  createApplication,
  deleteApplication,
  getApplications,
  updateApplication,
} from '../../api/applications'
import ApplicationList from './ApplicationList'
import ApplicationForm from './ApplicationForm'
import ApplicationFilters from './ApplicationFilters'
import PaginationControls from './PaginationControls'
import StatusHistoryModal from './StatusHistoryModal'

const EMPTY_FILTERS = { company: '', status: '', dateFrom: '', dateTo: '' }

export default function ApplicationsPage() {
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [page, setPage] = useState(0)
  const [pageData, setPageData] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState(null) // null | 'new' | application object
  const [viewingHistoryFor, setViewingHistoryFor] = useState(null) // null | application object

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      setPageData(await getApplications({ ...filters, page }))
    } catch {
      setError('Failed to load applications.')
    } finally {
      setLoading(false)
    }
  }, [filters, page])

  useEffect(() => {
    load()
  }, [load])

  function handleFiltersChange(next) {
    setFilters(next)
    setPage(0) // a changed filter invalidates the current page position
  }

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

  async function handleStatusChange(app, newStatus) {
    if (newStatus === app.status) {
      return
    }
    try {
      await changeApplicationStatus(app.id, newStatus, null)
      await load()
    } catch {
      setError('Failed to update status.')
    }
  }

  const filtersActive = Object.values(filters).some(Boolean)

  return (
    <>
      <div className="toolbar">
        <button type="button" onClick={() => setEditing('new')}>
          + New Application
        </button>
      </div>

      <ApplicationFilters filters={filters} onChange={handleFiltersChange} />

      {error && <p className="error">{error}</p>}

      {loading ? (
        <p>Loading…</p>
      ) : pageData.content.length === 0 ? (
        <p className="empty-state">
          {filtersActive ? 'No applications match your filters.' : 'No applications yet. Add your first one above.'}
        </p>
      ) : (
        <ApplicationList
          applications={pageData.content}
          onEdit={setEditing}
          onDelete={handleDelete}
          onStatusChange={handleStatusChange}
          onViewHistory={setViewingHistoryFor}
        />
      )}

      <PaginationControls
        page={pageData.page}
        totalPages={pageData.totalPages}
        totalElements={pageData.totalElements}
        onPageChange={setPage}
      />

      {editing && (
        <ApplicationForm
          initial={editing === 'new' ? null : editing}
          onSave={handleSave}
          onCancel={() => setEditing(null)}
        />
      )}

      {viewingHistoryFor && (
        <StatusHistoryModal application={viewingHistoryFor} onClose={() => setViewingHistoryFor(null)} />
      )}
    </>
  )
}
