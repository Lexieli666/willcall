import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

/**
 * Page shell. The skip link is the first focusable element on every route and the <main>
 * element is the single landmark that receives focus after a route change, which is what
 * makes keyboard navigation between routes survivable.
 */
export function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="wc-shell">
      <a className="wc-skip-link" href="#main">
        Skip to main content
      </a>
      <header className="wc-header">
        <div className="wc-header__inner">
          <div>
            <p className="wc-wordmark">Willcall</p>
            <p className="wc-tagline">Fixed seats, one crowd, no oversells.</p>
          </div>
          <nav className="wc-nav" aria-label="Main">
            <Link to="/">Events</Link>
            <Link to="/organizer">Organizer</Link>
            <Link to="/status">Status</Link>
          </nav>
        </div>
      </header>
      <main className="wc-main" id="main" tabIndex={-1}>
        {children}
      </main>
      <footer className="wc-footer">
        <div className="wc-footer__inner">
          <p>
            Willcall is a load-tested demonstration service. No real payments are taken and no
            real tickets are issued.
          </p>
        </div>
      </footer>
    </div>
  )
}
