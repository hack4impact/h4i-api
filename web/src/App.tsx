import { Banner, BaseStyles, Button, Spinner, ThemeProvider } from '@primer/react'
import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { api, type Me, signIn } from './api/client'
import { loadTools } from './api/tools'
import logo from './assets/logo.svg'
import { Mascot } from './components/Mascot'
import { TopBar } from './components/TopBar'
import { ApplyApp } from './pages/Apply'
import { ChapterPage } from './pages/ChapterPage'
import { MemberHome } from './pages/MemberHome'
import { National } from './pages/National'

type Session = { state: 'loading' } | { state: 'signed-out' } | { state: 'unavailable' } | { state: 'signed-in'; me: Me; toolsMissing: boolean }

/** Applicants (no Google account yet) use their own pages and session (build plan step 10). */
const isApplicantPage = () => /^\/(join|claim|apply)(\/|$)/.test(window.location.pathname)

/** Loads who's signed in, then routes: sign-in page, no-access page, or the first chapter they can see. */
export function App() {
  if (isApplicantPage()) {
    return (
      <ThemeProvider colorMode="auto">
        <BaseStyles className="app"><ApplyApp /></BaseStyles>
      </ThemeProvider>
    )
  }
  return <MemberApp />
}

function MemberApp() {
  const [session, setSession] = useState<Session>({ state: 'loading' })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    // Only "not signed in" shows the sign-in page; a server error or a lost connection says so (review L12).
    // Who's signed in and the tools (names, order, boxes) load together, before any page renders. Without the tools the
    // pages still work, with tool keys for names and only the boxes a project has: a banner says so.
    Promise.all([api.GET('/api/me'), loadTools()])
      .then(([{ data, response }, tools]) => {
        if (!data) return setSession(response.status === 401 ? { state: 'signed-out' } : { state: 'unavailable' })
        setSession({ state: 'signed-in', me: data, toolsMissing: !tools })
      })
      .catch(() => setSession({ state: 'unavailable' }))
  }, [attempt])

  return (
    <ThemeProvider colorMode="auto">
      <BaseStyles className="app">
        {session.state === 'loading' ? (
          <div className="center"><Spinner aria-label="Loading" /></div>
        ) : session.state === 'signed-out' ? (
          <SignIn denied={new URLSearchParams(window.location.search).get('signin') === 'denied'} />
        ) : session.state === 'unavailable' ? (
          <div className="center">
            <Banner title="The portal can’t be reached" variant="critical"
              description={<>It didn’t answer just now. <Button size="small" onClick={() => { setSession({ state: 'loading' }); setAttempt((n) => n + 1) }}>Try again</Button></>} />
          </div>
        ) : (
          <BrowserRouter>
            {session.toolsMissing && (
              <div className="tools-missing"><Banner title="Tool names didn’t load" variant="warning"
                description="Pages work, but show tool keys instead of names. Reload to try again." /></div>
            )}
            <Routes>
              <Route path="/chapters/:code" element={<ChapterPage me={session.me} tab="overview" />} />
              <Route path="/chapters/:code/members" element={<ChapterPage me={session.me} tab="members" />} />
              <Route path="/chapters/:code/approvals" element={<ChapterPage me={session.me} tab="approvals" />} />
              <Route path="/chapters/:code/settings" element={<ChapterPage me={session.me} tab="settings" />} />
              <Route path="/national" element={<National me={session.me} />} />
              <Route path="/me" element={<MemberHome me={session.me} />} />
              <Route
                path="*"
                element={session.me.chapters.length > 0 ? <Navigate to={`/chapters/${session.me.chapters[0]!.code}`} replace />
                  : session.me.personId ? <MemberHome me={session.me} /> : <NoAccess me={session.me} />}
              />
            </Routes>
          </BrowserRouter>
        )}
      </BaseStyles>
    </ThemeProvider>
  )
}

/** Google's "G", for the sign-in button (Google's branding asks for the mark on a white square). */
function GoogleMark() {
  return (
    <svg viewBox="0 0 48 48" width="18" height="18" aria-hidden="true">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  )
}

/** The sign-in page: one button, which account to use, and where applicants go instead. */
function SignIn({ denied }: { denied: boolean }) {
  return (
    <main className="signin">
      <section className="signin-brand" aria-label="Hack4Impact">
        <img src={logo} alt="Hack4Impact" className="signin-logo" />
        <p className="signin-tagline">Your chapter membership, in one place.</p>
      </section>
      <section className="signin-panel" aria-labelledby="signin-title">
        <div className="signin-card">
          <h1 id="signin-title" className="signin-title">Sign in</h1>
          {denied && <Banner title="That account can’t sign in" description="Use your Hack4Impact Google account." variant="critical" />}
          <Button variant="primary" size="large" block onClick={signIn} leadingVisual={() => <span className="google-mark"><GoogleMark /></span>}>
            Sign in with Google
          </Button>
          {!denied && <p className="signin-hint">Use your @hack4impact.org account.</p>}
          <p className="signin-other">Applying? Use your chapter’s link.</p>
        </div>
      </section>
    </main>
  )
}

function NoAccess({ me }: { me: Me }) {
  return (
    <>
      <TopBar me={me} />
      <div className="center">
        <Mascot pose="shrug" size={104} />
        <h1 className="page-title">No chapter access yet</h1>
        <p>
          You're signed in as <strong>{me.email}</strong>, but this account isn't linked to a member yet.
        </p>
        <p className="muted">Sign in with the Hack4Impact address your chapter gave you, or ask your chapter lead or the national team.</p>
      </div>
    </>
  )
}
