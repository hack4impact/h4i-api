import { Banner, Button, Radio, Select, Spinner, Textarea, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type ApplicantView, type ApplyChapter } from '../api/client'
import logo from '../assets/logo.svg'
import { Status } from '../components/Status'

type Details = ApplicantView['details']

/**
 * The applicant's pages (build plan step 10), outside Google sign-in: /join/<chapter> (with ?invite= for a roster
 * invite), /claim/<token>, and /apply once a session exists. One checklist: school email, GitHub, details, send.
 */

/** The member database keeps degree as free text; these keep it consistent. */
const DEGREES = ["Associate's", "Bachelor's", "Master's", 'PhD', 'Other']
const SEASONS: [string, string][] = [['spring', 'Spring'], ['summer', 'Summer'], ['fall', 'Fall'], ['winter', 'Winter']]

export function ApplyApp() {
  const path = window.location.pathname
  const params = new URLSearchParams(window.location.search)
  const [view, setView] = useState<ApplicantView | null>(null)
  const [chapter, setChapter] = useState<ApplyChapter | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    async function start() {
      const join = path.match(/^\/join\/([^/]+)/)
      const claim = path.match(/^\/claim\/([^/]+)/)
      if (claim) {
        const { data, error: failed, response } = await api.POST('/api/apply/claim', { body: { token: claim[1]! } })
        if (data) { setView(data); window.history.replaceState({}, '', '/apply') } else setError(reason(failed, response, 'This link could not be opened'))
      } else if (join && params.get('invite')) {
        const { data, error: failed, response } = await api.POST('/api/apply/roster', { body: { token: params.get('invite')! } })
        if (data) { setView(data); window.history.replaceState({}, '', '/apply') } else setError(reason(failed, response, 'This invite could not be opened'))
      } else if (join) {
        const { data } = await api.GET('/api/apply/chapters/{code}', { params: { path: { code: join[1]! } } })
        if (data) setChapter(data); else setError('There is no such chapter.')
      } else {
        const { data } = await api.GET('/api/apply/me')
        if (data) setView(data); else setError('Your session has ended. Open your chapter’s link, or your claim link, again.')
        if (params.get('github') === 'failed') setError('GitHub sign-in didn’t work. Try again.')
      }
      setLoading(false)
    }
    void start()
  }, [])

  return (
    <div className="apply">
      <header className="apply-head"><img src={logo} alt="Hack4Impact" /></header>
      <main className="apply-main">
        {loading ? <Spinner aria-label="Loading" /> : view ? <Application view={view} onChange={setView} initialError={error} />
          : chapter ? <EmailStep chapter={chapter} onVerified={setView} />
          : <Banner title="Not available" description={error ?? 'Something went wrong.'} variant="critical" />}
      </main>
    </div>
  )
}

/** Step 1 for new applicants: a code to the school address. */
function EmailStep({ chapter, onVerified }: { chapter: ApplyChapter; onVerified: (v: ApplicantView) => void }) {
  if (!chapter.applicationsOpen) return <OwnProcess chapter={chapter} />
  return <CodeForm chapter={chapter} onVerified={onVerified} />
}

/** The chapter runs its own application process (decision 116): point there instead. */
function OwnProcess({ chapter }: { chapter: ApplyChapter }) {
  return (
    <section className="apply-card" aria-labelledby="apply-title">
      <div className="eyebrow">Join</div>
      <h1 id="apply-title" className="apply-title">{chapter.name}</h1>
      <p>{chapter.name} runs its own application process{chapter.applicationUrl ? '.' : '. Ask the chapter’s leads how to apply.'}</p>
      {chapter.applicationUrl && <div><Button as="a" href={chapter.applicationUrl} variant="primary">Apply to {chapter.name}</Button></div>}
      <p className="muted">Already accepted? Your leads will email you a personal link to finish joining.</p>
    </section>
  )
}

function CodeForm({ chapter, onVerified }: { chapter: ApplyChapter; onVerified: (v: ApplicantView) => void }) {
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function send(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    const { error: failed, response } = await api.POST('/api/apply/chapters/{code}/code', { params: { path: { code: chapter.code } }, body: { email } })
    setBusy(false)
    if (response.status !== 202) return setError(reason(failed, response, 'The code could not be sent'))
    setError(null)
    setSent(true)
  }

  async function verify(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    const { data, error: failed, response } = await api.POST('/api/apply/chapters/{code}/verify', { params: { path: { code: chapter.code } }, body: { email, code } })
    setBusy(false)
    if (!data) return setError(reason(failed, response, 'That didn’t work'))
    window.history.replaceState({}, '', '/apply')
    onVerified(data)
  }

  return (
    <section className="apply-card" aria-labelledby="apply-title">
      <div className="eyebrow">Join</div>
      <h1 id="apply-title" className="apply-title">{chapter.name}</h1>
      <p className="muted">Start with your school email. We’ll send a code to prove it’s yours.</p>
      {error && <Banner title="Not yet" description={error} variant="critical" />}
      {!sent ? (
        <form className="form-grid" onSubmit={send}>
          <label>
            School email
            <TextInput type="email" required value={email} onChange={(e) => setEmail(e.target.value)}
              placeholder={chapter.emailDomains[0] ? `you@${chapter.emailDomains[0]}` : 'you@school.edu'} />
          </label>
          <div><Button type="submit" variant="primary" disabled={busy || !email}>Send code</Button></div>
        </form>
      ) : (
        <form className="form-grid" onSubmit={verify}>
          <p>We sent a 6-digit code to <strong>{email}</strong>. It works for 10 minutes.</p>
          <label>Code <TextInput inputMode="numeric" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} /></label>
          <div style={{ display: 'flex', gap: 8 }}>
            <Button type="submit" variant="primary" disabled={busy || code.length < 6}>Continue</Button>
            <Button onClick={() => { setSent(false); setCode('') }}>Use another address</Button>
          </div>
        </form>
      )}
    </section>
  )
}

/** The rest of the checklist, and where the application stands once it's sent. */
function Application({ view, onChange, initialError }: { view: ApplicantView; onChange: (v: ApplicantView) => void; initialError: string | null }) {
  const [details, setDetails] = useState<Details>(view.details)
  const [answers, setAnswers] = useState<Record<string, string>>(view.answers)
  const [error, setError] = useState<string | null>(initialError)
  const [saved, setSaved] = useState(false)
  const claim = view.kind === 'claim'
  const closed = view.kind === 'new' && !view.chapter.applicationsOpen
  const set = (patch: Partial<Details>) => { setDetails({ ...details, ...patch }); setSaved(false) }

  async function save(): Promise<ApplicantView | null> {
    const { data, error: failed, response } = await api.PUT('/api/apply/me', { body: { details, answers } })
    if (!data) { setError(reason(failed, response, 'Not saved')); return null }
    setError(null)
    setSaved(true)
    onChange(data)
    return data
  }

  /** Saves first (the name goes on the document), then leaves for Documenso; signing comes back to /apply. */
  async function sign(kind: string) {
    if (!(await save())) return
    const { data, error: failed, response } = await api.POST('/api/apply/me/agreements/{kind}', { params: { path: { kind } } })
    if (!data) return setError(reason(failed, response, 'The document couldn’t be opened'))
    window.location.assign(data.url)
  }

  async function submit() {
    const current = await save()
    if (!current) return
    const { data, error: failed, response } = await api.POST('/api/apply/me/submit')
    if (!data) return setError(reason(failed, response, 'Not sent'))
    onChange(data)
  }

  if (view.status !== 'started') return <Outcome view={view} />

  return (
    <section className="apply-card" aria-labelledby="apply-title">
      <div className="eyebrow">{claim ? 'Claim your record' : view.kind === 'roster' ? 'Finish joining' : 'Apply'}</div>
      <h1 id="apply-title" className="apply-title">{view.chapter.name}</h1>
      {error && <Banner title="Not yet" description={error} variant="critical" onDismiss={() => setError(null)} />}
      {closed && (
        <Banner title="Applications are closed here" variant="warning"
          description={`${view.chapter.name} now runs its own application process${view.chapter.applicationUrl ? `: ${view.chapter.applicationUrl}` : '. Ask the chapter’s leads how to apply.'}`} />
      )}
      <ol className="apply-steps">
        <li>
          <Status tone={view.emailVerified ? 'ok' : 'warn'}>School email</Status>
          <span className="muted">{view.schoolEmail ?? 'not verified'}</span>
        </li>
        <li>
          <Status tone={view.githubLogin ? 'ok' : 'warn'}>GitHub</Status>
          {view.githubLogin ? <span className="muted">Signed in as {view.githubLogin}</span>
            : view.githubConfigured ? <Button size="small" onClick={() => window.location.assign('/api/apply/github/start')}>Sign in with GitHub</Button>
            : <span className="muted">GitHub sign-in isn’t set up yet</span>}
        </li>
        {view.agreements.map((a) => (
          <li key={a.kind}>
            <Status tone={a.status === 'signed' ? 'ok' : 'warn'}>{a.name}</Status>
            {a.status === 'signed' ? <span className="muted">Signed</span>
              : <Button size="small" onClick={() => void sign(a.kind)}>{a.status === 'sent' ? 'Continue signing' : 'Read and sign'}</Button>}
          </li>
        ))}
        <li>
          <Status tone={view.missing.length === 0 ? 'ok' : 'warn'}>Your details</Status>
          <span className="muted">{view.missing.length === 0 ? 'Complete' : `Still needed: ${view.missing.join(', ')}`}</span>
        </li>
      </ol>

      <form className="form-grid" onSubmit={(e) => { e.preventDefault(); void save() }}>
        <div className="two-up">
          <label>First name <TextInput required value={details.firstName ?? ''} onChange={(e) => set({ firstName: e.target.value })} /></label>
          <label>Last name <TextInput required value={details.lastName ?? ''} onChange={(e) => set({ lastName: e.target.value })} /></label>
        </div>
        <label>Name you go by <TextInput value={details.preferredName ?? ''} onChange={(e) => set({ preferredName: e.target.value })} placeholder="Optional" /></label>
        <label>
          Personal email
          <TextInput type="email" value={details.personalEmail ?? ''} onChange={(e) => set({ personalEmail: e.target.value })} placeholder="For after you graduate" />
        </label>
        <fieldset className="choice">
          <legend>Your studies</legend>
          <div className="two-up">
            <label>Major <TextInput required value={details.major ?? ''} onChange={(e) => set({ major: e.target.value })} placeholder="e.g. Computer Science" /></label>
            <label>
              Degree
              <Select required value={details.degreeType ?? ''} onChange={(e) => set({ degreeType: e.target.value || null })}>
                <Select.Option value="">Choose…</Select.Option>
                {DEGREES.map((d) => <Select.Option key={d} value={d}>{d}</Select.Option>)}
              </Select>
            </label>
          </div>
          <div className="two-up">
            <label>
              Graduating in
              <Select required value={details.graduationSeason ?? ''} onChange={(e) => set({ graduationSeason: e.target.value || null })}>
                <Select.Option value="">Season…</Select.Option>
                {SEASONS.map(([value, label]) => <Select.Option key={value} value={value}>{label}</Select.Option>)}
              </Select>
            </label>
            <label>Year <TextInput type="number" required value={details.graduationYear?.toString() ?? ''} onChange={(e) => set({ graduationYear: e.target.value ? Number(e.target.value) : null })} placeholder="2028" /></label>
          </div>
        </fieldset>
        {claim ? (
          <fieldset className="choice">
            <legend>Are you still part of {view.chapter.name}?</legend>
            <label className="check"><Radio name="active" value="yes" checked={details.stillActive === true} onChange={() => set({ stillActive: true })} /> Yes, I’m still a member</label>
            <label className="check"><Radio name="active" value="no" checked={details.stillActive === false} onChange={() => set({ stillActive: false })} /> No, I’ve moved on (I’ll be kept as an alumnus)</label>
          </fieldset>
        ) : (
          <>
            <label>
              Role you’re interested in
              <Select value={details.roleInterest ?? ''} onChange={(e) => set({ roleInterest: e.target.value || null })}>
                <Select.Option value="">Not sure yet</Select.Option>
                {['Developer', 'Designer', 'Product manager', 'Tech lead'].map((r) => <Select.Option key={r} value={r}>{r}</Select.Option>)}
              </Select>
            </label>
            {view.chapter.questions.map((q) => (
              <label key={q.id}>
                {q.label}{q.required ? '' : ' (optional)'}
                {q.kind === 'long_text' ? <Textarea value={answers[q.id] ?? ''} onChange={(e) => { setAnswers({ ...answers, [q.id]: e.target.value }); setSaved(false) }} />
                  : q.kind === 'choice' || q.kind === 'yes_no' ? (
                    <Select value={answers[q.id] ?? ''} onChange={(e) => { setAnswers({ ...answers, [q.id]: e.target.value }); setSaved(false) }}>
                      <Select.Option value="">Choose…</Select.Option>
                      {(q.kind === 'yes_no' ? ['Yes', 'No'] : q.options).map((o) => <Select.Option key={o} value={o}>{o}</Select.Option>)}
                    </Select>
                  ) : <TextInput value={answers[q.id] ?? ''} onChange={(e) => { setAnswers({ ...answers, [q.id]: e.target.value }); setSaved(false) }} />}
              </label>
            ))}
          </>
        )}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button type="submit">Save</Button>
          <Button variant="primary" onClick={() => void submit()} disabled={!view.githubLogin || closed || view.agreements.some((a) => a.status !== 'signed')}>{claim ? 'Claim my record' : 'Send application'}</Button>
          {saved && <span className="muted">Saved</span>}
        </div>
      </form>
    </section>
  )
}

function Outcome({ view }: { view: ApplicantView }) {
  const done = view.status === 'approved' || view.status === 'completed'
  return (
    <section className="apply-card" aria-labelledby="apply-title">
      <div className="eyebrow">{view.chapter.name}</div>
      <h1 id="apply-title" className="apply-title">
        {view.status === 'submitted' ? 'Application sent' : view.status === 'declined' ? 'Not approved' : view.kind === 'claim' ? 'Record claimed' : 'Welcome to Hack4Impact'}
      </h1>
      {view.status === 'submitted' && <p>Your chapter’s leads will review it. We’ll email you when they decide.</p>}
      {view.status === 'declined' && <><p>The chapter’s reason:</p><blockquote className="apply-quote">{view.declineReason}</blockquote></>}
      {done && (
        <>
          <p>{view.account ? <>Your Hack4Impact account is <strong>{view.account}</strong>. The temporary password is in your email.</> : 'Check your email for what happens next.'}</p>
          <p>Your Slack channels, GitHub team and other tools follow within a day. From now on, sign in to the portal with your Hack4Impact Google account.</p>
          <Button as="a" href="/" variant="primary">Go to the portal</Button>
        </>
      )}
    </section>
  )
}
