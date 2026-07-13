import { APPLICATION_STATUSES, formatStatus } from './constants'

export default function ApplicationFilters({ filters, onChange }) {
  function update(field, value) {
    onChange({ ...filters, [field]: value })
  }

  return (
    <div className="filter-bar">
      <label className="filter-field">
        Company
        <input
          type="text"
          placeholder="Search company…"
          value={filters.company}
          onChange={(e) => update('company', e.target.value)}
        />
      </label>
      <label className="filter-field">
        Status
        <select value={filters.status} onChange={(e) => update('status', e.target.value)}>
          <option value="">All</option>
          {APPLICATION_STATUSES.map((s) => (
            <option key={s} value={s}>
              {formatStatus(s)}
            </option>
          ))}
        </select>
      </label>
      <label className="filter-field">
        Applied from
        <input type="date" value={filters.dateFrom} onChange={(e) => update('dateFrom', e.target.value)} />
      </label>
      <label className="filter-field">
        Applied to
        <input type="date" value={filters.dateTo} onChange={(e) => update('dateTo', e.target.value)} />
      </label>
      <button
        type="button"
        className="link-button filter-clear"
        onClick={() => onChange({ company: '', status: '', dateFrom: '', dateTo: '' })}
      >
        Clear filters
      </button>
    </div>
  )
}
