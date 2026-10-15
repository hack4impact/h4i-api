import { Banner, Button, TextInput } from '@primer/react'
import { Fragment, useEffect, useState } from 'react'
import { api, reason, type MemberDbPreview, type MemberDbStatus } from '../api/client'
import { Status } from '../components/Status'

/** A member database column in words. */
const FIELD: Record<string, string> = {
  'volunteers.first_name': 'First name', 'volunteers.last_name': 'Last name', 'volunteers.preferred_name': 'Preferred name',
  'volunteers.email': 'Email', 'volunteers.org_email': 'H4I address', 'volunteers.chapter_id': 'Chapter', 'volunteers.status': 'Status',
  'volunteers.type': 'Type', 'academic_profiles.email': 'School email', 'academic_profiles.major': 'Major',
  'academic_profiles.degree_type': 'Degree', 'academic_profiles.graduation_term_id': 'Graduation term',
  'accounts.github': 'GitHub account', 'accounts.google': 'Google account', 'volunteers.deleted_at': 'Record deleted there',
}
const field = (key: string) => FIELD[key] ?? key

/**
 * The member database as an integration (wiki: staged-rollout): its state, the switches (dry run, pause), the last
 * pull, records waiting to be written or failed, and changes made there to what the portal owns (reported, not taken).
 */
export function MemberDbBox() {
  const [status, setStatus] = useState<MemberDbStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pausing, setPausing] = useState(false)
  const [why, setWhy] = useState('')
  const [preview, setPreview] = useState<{ id: string; data: MemberDbPreview | null; error: string | null } | null>(null)
  const [pulling, setPulling] = useState(false)

  const load = () => api.GET('/api/national/member-db').then(({ data }) => data && setStatus(data))
  useEffect(() => { void load() }, [])

  async function change(dryRun: boolean, paused: boolean, reasonText: string | null = null) {
    const { data, error: failed, response } = await api.PUT('/api/national/member-db', { body: { dryRun, paused, reason: reasonText } })
    if (!data) return setError(reason(failed, response, 'Not changed'))
    setError(null)
    setPausing(false)
    setWhy('')
    setStatus(data)
  }

  async function pullNow() {
    setPulling(true)
    const { data, error: failed, response } = await api.POST('/api/national/member-db/pull')
    setPulling(false)
    if (!data) return setError(reason(failed, response, 'The pull didn’t run'))
    setError(data.status === 'failed' ? `The pull failed: ${data.error}` : null)
    void load()
  }

  async function retry(personId: string) {
    const { data, error: failed, response } = await api.POST('/api/national/member-db/writes/{personId}/retry', { params: { path: { personId } } })
    if (!data) return setError(reason(failed, response))
    setStatus(data)
  }

  async function showPreview(personId: string) {
    if (preview?.id === personId) return setPreview(null)
    const { data, error: failed, response } = await api.GET('/api/national/member-db/writes/{personId}/preview', { params: { path: { personId } } })
    setPreview({ id: personId, data: data ?? null, error: data ? null : reason(failed, response, 'No preview') })
  }

  if (!status) return null
  const [state, tone]: [string, 'ok' | 'warn' | 'off' | 'danger'] = !status.configured ? ['Not connected', 'off']
    : status.paused ? ['Paused', 'warn'] : status.dryRun ? ['Dry run', 'off'] : ['Writing', 'ok']

  return (
    <section className="box" aria-labelledby="member-db-title">
      <div className="box-head" id="member-db-title">
        Member database
        <span style={{ marginLeft: 'auto' }}><Status tone={tone}>{state}</Status></span>
      </div>
      {error && <div className="box-row"><Banner title="Not done" description={error} variant="critical" onDismiss={() => setError(null)} /></div>}
      {!status.configured ? (
        <div className="box-row muted">This server has no member database set (MEMBER_DB_URL), so nothing is pulled or written.</div>
      ) : (
        <>
          <div className="box-row muted">
            {status.paused && status.pauseReason ? `Paused: ${status.pauseReason}. ` : ''}
            {status.dryRun ? 'Records wait here until writes are turned on. ' : ''}
            {!status.writeSwitch && 'This server’s write switch (PORTAL_MEMBERDB_WRITE) is off, so it stays in dry run. '}
            {status.lastPullAt ? `Last pull ${new Date(status.lastPullAt).toLocaleString()}: ${status.lastPullStatus === 'unchanged' ? 'nothing new' : status.lastPullStatus}${status.lastPullError ? ` (${status.lastPullError})` : ''}.` : 'Not pulled yet.'}
            {!status.scheduledPull && ' Pulls only when asked (PORTAL_MEMBERDB_PULL is off).'}
          </div>
          <div className="box-row inline-form">
            <Button size="small" onClick={() => void pullNow()} disabled={pulling}>{pulling ? 'Pulling…' : 'Pull now'}</Button>
            {status.dryRun
              ? <Button size="small" variant="primary" disabled={!status.writeSwitch || status.paused} onClick={() => void change(false, false)}>Turn writes on</Button>
              : <Button size="small" onClick={() => void change(true, status.paused, status.pauseReason)}>Back to dry run</Button>}
            {status.paused
              ? <Button size="small" onClick={() => void change(status.dryRun, false)}>Resume</Button>
              : pausing ? (
                <>
                  <TextInput aria-label="Why pause" placeholder="Why" value={why} onChange={(e) => setWhy(e.target.value)} />
                  <Button size="small" variant="danger" disabled={!why.trim()} onClick={() => void change(status.dryRun, true, why)}>Pause</Button>
                  <Button size="small" onClick={() => setPausing(false)}>Cancel</Button>
                </>
              ) : <Button size="small" onClick={() => setPausing(true)}>Pause…</Button>}
          </div>
        </>
      )}

      {status.failed.length > 0 && (
        <>
          <div className="box-row pane-label">Failed <span className="Counter">{status.failed.length}</span></div>
          <ul className="adopt-list" aria-label="Failed writes">
            {status.failed.map((w) => (
              <li key={w.personId}>
                <div className="task-row">
                  <div className="adopt-title">
                    <div className="adopt-name"><span className="name-text">{w.name}</span></div>
                    <div className="adopt-meta">{w.lastError}</div>
                  </div>
                  <Button size="small" onClick={() => void retry(w.personId)}>Try again</Button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="box-row pane-label">Waiting to be written <span className="Counter">{status.waiting.length}</span></div>
      {status.waiting.length === 0 && <div className="box-row muted">Nobody.</div>}
      <ul className="adopt-list" aria-label="Waiting writes">
        {status.waiting.map((w) => (
          <li key={w.personId}>
            <div className="task-row">
              <div className="adopt-title">
                <div className="adopt-name"><span className="name-text">{w.name}</span></div>
                <div className="adopt-meta">
                  Since {new Date(w.requestedAt).toLocaleString()}
                  {w.attempts > 0 ? ` · ${w.attempts} failed tries, next ${w.nextTryAt ? new Date(w.nextTryAt).toLocaleTimeString() : 'soon'}${w.lastError ? ` · ${w.lastError}` : ''}` : ''}
                </div>
              </div>
              <Button size="small" variant="invisible" onClick={() => void showPreview(w.personId)}>{preview?.id === w.personId ? 'Hide' : 'What would be written'}</Button>
            </div>
            {preview?.id === w.personId && (
              <div className="box-row">
                {preview.error ? <span className="muted">{preview.error}</span> : preview.data && (
                  <>
                    <div className="muted">{preview.data.newVolunteer ? 'A new volunteer' : 'An update to their volunteer record'}</div>
                    <dl className="facts" aria-label={`What would be written for ${w.name}`}>
                      {Object.entries(preview.data.fields).map(([k, v]) => <Fragment key={k}><dt>{field(k)}</dt><dd>{v ?? '—'}</dd></Fragment>)}
                    </dl>
                    {preview.data.notes.map((n) => <div key={n} className="muted">{n}</div>)}
                  </>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>

      {status.drift.length > 0 && (
        <>
          <div className="box-row pane-label">Changed in the member database <span className="Counter">{status.drift.length}</span></div>
          <div className="box-row muted">Fields the portal owns that someone changed there. The portal keeps its own value; change it in the portal, or ask why it was changed.</div>
          <ul className="plain-list box-row" aria-label="Changed in the member database">
            {status.drift.map((d) => (
              <li key={`${d.personId}-${d.field}`}>
                <strong>{d.name}</strong> · {field(d.field)}: <span className="muted">portal</span> {d.portalValue ?? '—'} · <span className="muted">member database</span> {d.memberDbValue ?? '—'}
                <span className="muted"> · since {new Date(d.since).toLocaleDateString()}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
