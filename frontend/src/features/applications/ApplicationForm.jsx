import { useState } from 'react'
import { APPLICATION_STATUSES, formatStatus } from './constants'

const emptyForm = {
  companyName: '',
  jobTitle: '',
  jobUrl: '',
  status: 'APPLIED',
  appliedDate: '',
  location: '',
  source: '',
  notes: '',
}

export default function ApplicationForm({ initial, onSave, onCancel }) {
  const [form, setForm] = useState(() => (initial ? { ...emptyForm, ...initial } : emptyForm))
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  function update(field, value) {
    setForm((f) => ({ ...f, [field]: value }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')
    setSubmitting(true)
    try {
      await onSave({ ...form, appliedDate: form.appliedDate || null })
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to save. Please check the form and try again.')
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <form className="modal" onSubmit={handleSubmit} onClick={(e) => e.stopPropagation()}>
        <h2>{initial ? 'Edit Application' : 'New Application'}</h2>

        <label>
          Company *
          <input value={form.companyName} onChange={(e) => update('companyName', e.target.value)} required />
        </label>
        <label>
          Job title *
          <input value={form.jobTitle} onChange={(e) => update('jobTitle', e.target.value)} required />
        </label>
        <label>
          Job URL
          <input value={form.jobUrl || ''} onChange={(e) => update('jobUrl', e.target.value)} />
        </label>
        {!initial && (
          <label>
            Status *
            <select value={form.status} onChange={(e) => update('status', e.target.value)} required>
              {APPLICATION_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {formatStatus(s)}
                </option>
              ))}
            </select>
          </label>
        )}
        <label>
          Applied date
          <input
            type="date"
            value={form.appliedDate || ''}
            onChange={(e) => update('appliedDate', e.target.value)}
          />
        </label>
        <label>
          Location
          <input value={form.location || ''} onChange={(e) => update('location', e.target.value)} />
        </label>
        <label>
          Source
          <input value={form.source || ''} onChange={(e) => update('source', e.target.value)} />
        </label>
        <label>
          Notes
          <textarea value={form.notes || ''} onChange={(e) => update('notes', e.target.value)} rows={3} />
        </label>

        {error && <p className="error">{error}</p>}

        <div className="modal-actions">
          <button type="button" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" disabled={submitting}>
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </div>
  )
}
