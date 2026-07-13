import { useAuth } from '../context/AuthContext'

export default function AppShell({ view, onViewChange, children }) {
  const { email, fullName, logout } = useAuth()

  return (
    <div className="page">
      <header className="page-header">
        <h1>Job Pulse</h1>
        <nav className="page-nav">
          <button
            type="button"
            className={view === 'applications' ? 'nav-tab active' : 'nav-tab'}
            onClick={() => onViewChange('applications')}
          >
            Applications
          </button>
          <button
            type="button"
            className={view === 'dashboard' ? 'nav-tab active' : 'nav-tab'}
            onClick={() => onViewChange('dashboard')}
          >
            Dashboard
          </button>
        </nav>
        <div className="page-header-user">
          <span>{fullName || email}</span>
          <button type="button" onClick={logout}>
            Log out
          </button>
        </div>
      </header>
      {children}
    </div>
  )
}
