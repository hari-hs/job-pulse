import client from './client'

export function getApplications() {
  return client.get('/applications').then((res) => res.data)
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
