import { Banner, Button, Label, Spinner, Textarea } from '@primer/react'
import { Fragment, useEffect, useState } from 'react'
import { api, reason, type RegistrationSummary, type RosterInviteView } from '../api/client'
import { STATUS_LABEL, Status } from '../components/Status'

const MATCH: Record<string, [string, 'success' | 'accent' | 'attention' | 'severe']> = {
  new: ['New', 'success'],
  returning: ['Returning', 'accent'],
  alumni: ['Alumni record found', 'attention'],
  duplicate: ['Possible duplicate', 'severe'],
}

/**
 * Approvals (PRD: pending approvals with database match status, approve and decline) and roster invites. Leads and
 * co-leads decide; viewers see the same list without the buttons.
 */
export function Approvals({ code, canEdit }: { code: string; canEdit: boolean }) {
  const [list, setList] = useState<RegistrationSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [applicationsOpen, setApplicationsOpen] = useState<boolean | null>(null)
  const path = { params: { path: { code } } }

  useEffect(() => {
    void api.GET('/api/chapters/{code}/registrations', path).then(({ data }) => setList(data ?? []))
    void api.GET('/api/chapters/{code}/registration-settings', path).then(({ data }) => setApplicationsOpen(data?.applicationsOpen ?? true))
  }, [code])

  async function act(call: Promise<{ data?: RegistrationSummary[]; error?: unknown; response: Response }>) {
    const { data, error: failed, response } = await call
    if (!data) { setError(reason(failed, response)); return false }
    setError(null)
    setList(data)
    return true
  }

  if (!list || applicationsOpen === null) return <div className="center"><Spinner aria-label="Loading approvals" /></div>
  const pending = list.filter((r) => r.status === 'submitted')
  const decided = list.filter((r) => r.status !== 'submitted')

  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      {error && <Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} />}
      {(applicationsOpen || pending.length > 0) && <section className="box" aria-labelledby="pending-title">
        <div className="box-head" id="pending-title">Waiting for a decision <span className="Counter">{pending.length}</span></div>
        {pending.length === 0 && <div className="box-row muted">No applications waiting. Share the chapter’s registration link to get some.</div>}
        <ul className="adopt-list" aria-label="Waiting for a decision">
          {pending.map((r) => (
            <Pending key={r.id} r={r} canEdit={canEdit}
              onApprove={(personId, secondAccount) => act(api.POST('/api/chapters/{code}/registrations/{id}/approve', { params: { path: { code, id: r.id } }, body: { personId, secondAccount } }))}
              onDecline={(why) => act(api.POST('/api/chapters/{code}/registrations/{id}/decline', { params: { path: { code, id: r.id } }, body: { reason: why } }))} />
          ))}
        </ul>
        {decided.length > 0 && (
          <details className="box-row">
            <summary className="muted">Decided in the last 30 days ({decided.length})</summary>
            <ul className="plain-list">
              {decided.map((r) => (
                <li key={r.id}>
                  <Status tone={r.status === 'approved' ? 'ok' : 'off'}>{r.status === 'approved' ? 'Approved' : 'Declined'}</Status> {r.name}
                  <span className="muted">{r.kind === 'roster' ? ' · roster' : ''}{r.declineReason ? ` · ${r.declineReason}` : ''}</span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </section>}
      {canEdit && <Roster code={code} />}
    </div>
  )
}

function Pending({ r, canEdit, onApprove, onDecline }: {
  r: RegistrationSummary; canEdit: boolean; onApprove: (personId: string | null, secondAccount: boolean) => Promise<boolean>; onDecline: (why: string) => Promise<boolean>
}) {
  const [declining, setDeclining] = useState(false)
  const [why, setWhy] = useState('')
  const [as, setAs] = useState<string | null>(r.matches.length === 1 && r.matchStatus !== 'duplicate' ? r.matches[0]!.personId : null)
  const [label, variant] = MATCH[r.matchStatus ?? 'new'] ?? ['New', 'success']
  // A match with an H4I account when the approval isn't into that record: a second address for one person.
  const holder = r.matches.find((m) => m.account && m.personId !== as)
  const secondAccount = Boolean(holder) && !r.matches.find((m) => m.personId === as)?.account
  return (
    <li>
      <div className="registration">
        <div className="adopt-name">
          <span className="name-text">{r.name}</span>
          <Label variant={variant}>{label}</Label>
          {r.kind === 'roster' && <Label variant="secondary">Roster</Label>}
          {r.githubChanged && <Label variant="attention">Different GitHub than on record</Label>}
        </div>
        <dl className="facts">
          <dt>School email</dt><dd>{r.schoolEmail ?? '—'}</dd>
          <dt>GitHub</dt><dd>{r.githubLogin ?? '—'}</dd>
          <dt>Studies</dt><dd>{[r.major, r.degreeType].filter(Boolean).join(', ') || '—'}</dd>
          <dt>Graduates</dt><dd>{r.graduationYear ? `${r.graduationSeason ? `${r.graduationSeason[0]!.toUpperCase()}${r.graduationSeason.slice(1)} ` : ''}${r.graduationYear}` : '—'}</dd>
          <dt>Interested in</dt><dd>{r.roleInterest ?? 'Not sure yet'}</dd>
          {Object.entries(r.answers).map(([q, a]) => <Fragment key={q}><dt>{q}</dt><dd>{a || '—'}</dd></Fragment>)}
        </dl>
        {r.matches.length > 0 && (
          <fieldset className="choice">
            <legend>{canEdit ? 'Approve as' : 'Matches'}</legend>
            {canEdit && (
              <label className="check"><input type="radio" name={`as-${r.id}`} checked={as === null} onChange={() => setAs(null)} /> A new person</label>
            )}
            {r.matches.map((m) => (
              <label className="check" key={m.personId}>
                {canEdit && <input type="radio" name={`as-${r.id}`} checked={as === m.personId} onChange={() => setAs(m.personId)} />}
                {m.name} <span className="muted">· {STATUS_LABEL[m.status] ?? m.status}{m.chapters.length ? ` · ${m.chapters.join(', ')}` : ''}{m.account ? ` · has ${m.account}` : ''} · {m.why.join(', ')}</span>
              </label>
            ))}
          </fieldset>
        )}
        {canEdit && !declining && secondAccount && holder && (
          <Banner variant="warning" title="This would give them a second H4I account"
            description={`${holder.name} already has ${holder.account}. If that's the same person, approve as that record instead.`} />
        )}
        {canEdit && !declining && (
          <div style={{ display: 'flex', gap: 8 }}>
            <Button variant={secondAccount ? 'danger' : 'primary'} onClick={() => void onApprove(as, secondAccount)}>
              {secondAccount ? 'Approve with a second account' : as ? 'Approve as this record' : 'Approve'}
            </Button>
            <Button onClick={() => setDeclining(true)}>Decline</Button>
          </div>
        )}
        {declining && (
          <div className="form-grid">
            <label>Why? The applicant gets this by email. <Textarea value={why} onChange={(e) => setWhy(e.target.value)} /></label>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button variant="danger" disabled={!why.trim()} onClick={async () => { if (await onDecline(why)) setDeclining(false) }}>Decline</Button>
              <Button onClick={() => setDeclining(false)}>Cancel</Button>
            </div>
          </div>
        )}
      </div>
    </li>
  )
}

/** PRD: a lead uploads a roster CSV of accepted members; each gets a personal link and is approved when they finish. */
function Roster({ code }: { code: string }) {
  const [invites, setInvites] = useState<RosterInviteView[]>([])
  const [csv, setCsv] = useState('')
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    void api.GET('/api/chapters/{code}/roster', { params: { path: { code } } }).then(({ data }) => data && setInvites(data))
  }, [code])

  async function upload() {
    const { data, error: failed, response } = await api.POST('/api/chapters/{code}/roster', { params: { path: { code } }, body: { csv } })
    if (!data) return setError(reason(failed, response, 'The roster could not be sent'))
    setError(null)
    setInvites(data.invites)
    setResult(`Invited ${data.invited}.${data.skipped.length ? ` Skipped: ${data.skipped.join('; ')}` : ''}`)
    setCsv('')
  }

  return (
    <section className="box" aria-labelledby="roster-title">
      <div className="box-head" id="roster-title">Invite a roster</div>
      <div className="box-row form-grid">
        <span className="muted">People you’ve already accepted. Each gets a personal link and joins without another approval. Columns: first_name, last_name, school_email, and optionally personal_email.</span>
        {error && <Banner title="Not sent" description={error} variant="critical" />}
        {result && <Banner title="Sent" description={result} variant="success" />}
        <label>
          CSV
          <Textarea rows={5} value={csv} onChange={(e) => setCsv(e.target.value)} placeholder={'first_name,last_name,school_email\nGrace,Hopper,grace@umd.edu'} />
        </label>
        <label>
          Or a file
          <input type="file" accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) void f.text().then(setCsv) }} />
        </label>
        <div><Button variant="primary" disabled={!csv.trim()} onClick={() => void upload()}>Send invites</Button></div>
      </div>
      {invites.length > 0 && (
        <ul className="adopt-list" aria-label="Roster invites">
          {invites.map((i) => (
            <li key={i.id}>
              <div className="task-row">
                <div className="adopt-title">
                  <div className="adopt-name"><span className="name-text">{i.name}</span><span className="muted">{i.schoolEmail}</span></div>
                  <div className="adopt-meta">Sent {new Date(i.createdAt).toLocaleDateString()} · link works until {new Date(i.expiresAt).toLocaleDateString()}</div>
                </div>
                <Status tone={i.status === 'completed' ? 'ok' : 'off'}>
                  {{ sent: 'Invited', started: 'Started', completed: 'Joined', expired: 'Expired' }[i.status] ?? i.status}
                </Status>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
