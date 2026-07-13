export default function PaginationControls({ page, totalPages, totalElements, onPageChange }) {
  if (totalElements === 0) {
    return null
  }

  return (
    <div className="pagination">
      <button type="button" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        Previous
      </button>
      <span className="pagination-status">
        Page {page + 1} of {Math.max(totalPages, 1)} · {totalElements} total
      </span>
      <button type="button" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>
        Next
      </button>
    </div>
  )
}
