import client from './client'

export function getDashboardSummary() {
  return client.get('/dashboard/summary').then((res) => res.data)
}
