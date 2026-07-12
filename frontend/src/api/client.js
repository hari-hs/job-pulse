import axios from 'axios'

// The JWT lives only in this module-level variable — plain JS memory, not
// localStorage/sessionStorage — per DESIGN.md's "JWT stored in memory"
// choice (a page reload logs you out; that's the deliberate tradeoff for
// reduced token-theft exposure via XSS).
let authToken = null
let unauthorizedHandler = null

export function setAuthToken(token) {
  authToken = token
}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

const client = axios.create({ baseURL: '/api' })

client.interceptors.request.use((config) => {
  if (authToken) {
    config.headers.Authorization = `Bearer ${authToken}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && unauthorizedHandler) {
      unauthorizedHandler()
    }
    return Promise.reject(error)
  },
)

export default client
