import { useState } from 'react'
import { AuthProvider, useAuth } from './context/AuthContext'
import AuthForm from './features/auth/AuthForm'
import AppShell from './components/AppShell'
import ApplicationsPage from './features/applications/ApplicationsPage'
import DashboardPage from './features/dashboard/DashboardPage'
import './App.css'

function AppContent() {
  const { token } = useAuth()
  const [view, setView] = useState('applications')

  if (!token) {
    return <AuthForm />
  }

  return (
    <AppShell view={view} onViewChange={setView}>
      {view === 'applications' ? <ApplicationsPage /> : <DashboardPage />}
    </AppShell>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  )
}
