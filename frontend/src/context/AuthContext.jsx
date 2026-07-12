import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { login as apiLogin, register as apiRegister } from '../api/auth'
import { setAuthToken, setUnauthorizedHandler } from '../api/client'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState(null) // { token, email, fullName } | null

  const logout = useCallback(() => {
    setAuthToken(null)
    setAuth(null)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
  }, [logout])

  const login = useCallback(async (email, password) => {
    const data = await apiLogin(email, password)
    setAuthToken(data.token)
    setAuth({ token: data.token, email: data.email, fullName: data.fullName })
  }, [])

  const register = useCallback(async (email, password, fullName) => {
    const data = await apiRegister(email, password, fullName)
    setAuthToken(data.token)
    setAuth({ token: data.token, email: data.email, fullName: data.fullName })
  }, [])

  return (
    <AuthContext.Provider value={{ ...auth, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return ctx
}
