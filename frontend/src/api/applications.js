import client from './client'

export function getApplications({ status, company, dateFrom, dateTo, page = 0, size = 20 } = {}) {
  const params = { page, size }
  if (status) params.status = status
  if (company) params.company = company
  if (dateFrom) params.dateFrom = dateFrom
  if (dateTo) params.dateTo = dateTo
  return client.get('/applications', { params }).then((res) => res.data)
}

export function createApplication(data) {
  return client.post('/applications', data).then((res) => res.data)
}

export function updateApplication(id, data) {
  return client.put(`/applications/${id}`, data).then((res) => res.data)
}

export function deleteApplication(id) {
  return client.delete(`/applications/${id}`)
}

export function changeApplicationStatus(id, status, note) {
  return client.patch(`/applications/${id}/status`, { status, note }).then((res) => res.data)
}

export function getApplicationHistory(id) {
  return client.get(`/applications/${id}/history`).then((res) => res.data)
}
