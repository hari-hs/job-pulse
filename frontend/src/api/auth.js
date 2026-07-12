import client from './client'

export function login(email, password) {
  return client.post('/auth/login', { email, password }).then((res) => res.data)
}

export function register(email, password, fullName) {
  return client.post('/auth/register', { email, password, fullName }).then((res) => res.data)
}
