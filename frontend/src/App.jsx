import { AuthProvider, useAuth } from './context/AuthContext'
import AuthForm from './features/auth/AuthForm'
import ApplicationsPage from './features/applications/ApplicationsPage'
import './App.css'

function AppContent() {
  const { token } = useAuth()
  return token ? <ApplicationsPage /> : <AuthForm />
}

export default function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  )
}
