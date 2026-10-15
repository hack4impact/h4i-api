import { Banner, Button, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type AgreementTemplates, type ClaimPreview, type Me, type QueueTask } from '../api/client'
import { Status, TOOL_NAME } from '../components/Status'
import { Mascot } from '../components/Mascot'
import { TopBar } from '../components/TopBar'
import { MemberDbBox } from './MemberDbBox'

/**
 * National (stage 1): the admin queue (things the portal couldn't do itself, like a Google account for a chapter
 * without an org unit yet) and claim links for imported members. National admins only.
 */
export function National({ me }: { me: Me }) {
  const [tasks, setTasks] = useState<QueueTask[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void api.GET('/api/national/queue').then(({ data, response }) => (data ? setTasks(data) : setError(response.status === 403 ? 'National admins only.' : 'Could not load.')))
  }, [])

  async function decide(task: QueueTask, action: 'done' | 'cancel') {
    const { data, error: failed, response } = await api.POST('/api/national/queue/{id}', { params: { path: { id: task.id } }, body: { action, reason: null } })
    if (!data) return setError(reason(failed, response))
    setError(null)
    setTasks(data)
  }

  const open = (tasks ?? []).filter((t) => t.status === 'open')
  const recent = (tasks ?? []).filter((t) => t.status !== 'open')

  return (
    <>
      <TopBar me={me} />
      <div className="page-head">
        <div className="inner">
          <div className="eyebrow">National</div>
          <h1 className="page-title">Admin queue, claims and the member database</h1>
        </div>
      </div>
      {!tasks ? (
        <div className="center">{error ? <Banner title="Not available" description={error} variant="critical" /> : <Spinner aria-label="Loading" />}</div>
      ) : (
        <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
          {error && <Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} />}

          <section className="box" aria-labelledby="queue-title">
            <div className="box-head" id="queue-title">Admin queue <span className="Counter">{open.length}</span></div>
            {open.length === 0 && <div className="box-row muted mascot-row"><Mascot pose="nap" size={56} />Nothing to do.</div>}
            <ul className="adopt-list" aria-label="Open tasks">
              {open.map((t) => (
                <li key={t.id}>
                  <div className="task-row">
                    <div className="adopt-title">
                      <div className="adopt-name"><span className="name-text">{t.description}</span></div>
                      <div className="adopt-meta">{TOOL_NAME[t.tool] ?? t.tool} · {new Date(t.createdAt).toLocaleString()}</div>
                    </div>
                    <span style={{ display: 'flex', gap: 8 }}>
                      <Button size="small" onClick={() => decide(t, 'done')}>Done</Button>
                      <Button size="small" onClick={() => decide(t, 'cancel')}>Cancel</Button>
                    </span>
                  </div>
                </li>
              ))}
            </ul>
            {recent.length > 0 && (
              <details className="box-row">
                <summary className="muted">Closed in the last 7 days ({recent.length})</summary>
                <ul className="plain-list">
                  {recent.map((t) => (
                    <li key={t.id}>
                      <Status tone={t.status === 'done' ? 'ok' : 'off'}>{t.status === 'done' ? 'Done' : 'Cancelled'}</Status> {t.description}
                      <span className="muted">{t.doneBy ? ` · ${t.doneBy}` : ''}</span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </section>

          <ClaimLinks me={me} />

          <AgreementTemplatesBox />

          <MemberDbBox />
        </div>
      )}
    </>
  )
}

function ClaimLinks({ me }: { me: Me }) {
  const [chapter, setChapter] = useState('')
  const [preview, setPreview] = useState<ClaimPreview | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setPreview(null)
    void api.GET('/api/national/claims/preview', { params: { query: chapter ? { chapter } : {} } }).then(({ data }) => data && setPreview(data))
  }, [chapter])

  async function send() {
    const { data, error: failed, response } = await api.POST('/api/national/claims/send', { body: { chapter: chapter || null, limit: 200 } })
    setConfirming(false)
    if (!data) return setError(reason(failed, response, 'Not sent'))
    setError(null)
    setPreview(data)
  }

  const batch = Math.min(preview?.eligible ?? 0, 200)
  return (
    <section className="box" aria-labelledby="claims-title">
      <div className="box-head" id="claims-title">Claim links</div>
      <div className="box-row muted">Imported members who haven’t claimed their record get a single-use link, valid 14 days, at every address on file. Unclaimed records become alumni a month after their first link.</div>
      {error && <div className="box-row"><Banner title="Not sent" description={error} variant="critical" /></div>}
      <div className="box-row inline-form">
        <Select aria-label="Chapter" value={chapter} onChange={(e) => { setChapter(e.target.value); setConfirming(false) }}>
          <Select.Option value="">All chapters</Select.Option>
          {me.chapters.map((c) => <Select.Option key={c.code} value={c.code}>{c.name}</Select.Option>)}
        </Select>
        {preview ? (
          <span className="muted">{preview.eligible} to send · {preview.alreadySent} with an open link · {preview.withoutEmail} without an address</span>
        ) : <Spinner size="small" aria-label="Counting" />}
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {confirming ? (
            <>
              <Button variant="danger" onClick={() => void send()}>Yes, email {batch} {batch === 1 ? 'person' : 'people'}</Button>
              <Button onClick={() => setConfirming(false)}>Cancel</Button>
            </>
          ) : <Button disabled={!batch} onClick={() => setConfirming(true)}>Send {batch} claim {batch === 1 ? 'link' : 'links'}</Button>}
        </span>
      </div>
    </section>
  )
}

const AGREEMENT_KINDS: [string, string, string][] = [
  ['volunteer', 'Volunteer agreement', 'Signed while applying or claiming a record'],
  ['code_of_conduct', 'Code of conduct', 'Signed while applying or claiming a record'],
]

/**
 * Which Documenso templates the registration documents are made from (Q52). Each kind has at most one active
 * template; replacing it makes the next version, and documents already signed stay valid.
 */
function AgreementTemplatesBox() {
  const [view, setView] = useState<AgreementTemplates | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [form, setForm] = useState({ name: '', documensoTemplateId: '' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { void api.GET('/api/national/agreement-templates').then(({ data }) => data && setView(data)) }, [])

  async function save(kind: string) {
    const { data, error: failed, response } = await api.POST('/api/national/agreement-templates', { body: { kind, ...form } })
    if (!data) return setError(reason(failed, response, 'Not saved'))
    setError(null)
    setEditing(null)
    setView(data)
  }

  async function turnOff(id: string) {
    const { data, error: failed, response } = await api.POST('/api/national/agreement-templates/{id}/deactivate', { params: { path: { id } } })
    if (!data) return setError(reason(failed, response, 'Not saved'))
    setView(data)
  }

  if (!view) return null
  const [state, tone] = !view.connected ? ['Documenso isn’t connected', 'off'] as const : view.sending ? ['Sending agreements', 'ok'] as const : ['Not sending: Documenso’s write switch is off', 'off'] as const
  return (
    <section className="box" aria-labelledby="agreements-title">
      <div className="box-head" id="agreements-title">
        Agreements <span style={{ marginLeft: 'auto' }}><Status tone={tone}>{state}</Status></span>
      </div>
      <div className="box-row muted">
        Make each template in Documenso with one signer, then paste its ID or link here. Until a kind has a template, nobody is asked to sign it.
      </div>
      {error && <div className="box-row"><Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} /></div>}
      <ul className="adopt-list" aria-label="Agreement templates">
        {AGREEMENT_KINDS.map(([kind, label, when]) => {
          const active = view.templates.find((t) => t.kind === kind && t.active)
          const older = view.templates.filter((t) => t.kind === kind && !t.active).length
          return (
            <li key={kind}>
              <div className="task-row">
                <div className="adopt-title">
                  <div className="adopt-name">
                    <span className="name-text">{label}</span>
                    {active ? <Status tone="ok">{active.name} · version {active.version}</Status> : <Status tone="off">Not set up</Status>}
                  </div>
                  <div className="adopt-meta">
                    {when}{active ? ` · ${active.sent} sent · Documenso ${active.documensoTemplateId}` : ''}{older ? ` · ${older} earlier ${older === 1 ? 'version' : 'versions'}` : ''}
                  </div>
                </div>
                {editing !== kind && (
                  <span style={{ display: 'flex', gap: 8 }}>
                    <Button size="small" onClick={() => { setEditing(kind); setForm({ name: active?.name ?? label, documensoTemplateId: '' }) }}>{active ? 'Replace' : 'Set up'}</Button>
                    {active && <Button size="small" variant="invisible" onClick={() => void turnOff(active.id)}>Turn off</Button>}
                  </span>
                )}
              </div>
              {editing === kind && (
                <div className="inline-form" style={{ padding: '0 16px 12px' }} role="group" aria-label={`${label} template`}>
                  <TextInput aria-label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={label} />
                  <TextInput aria-label="Documenso template" value={form.documensoTemplateId} onChange={(e) => setForm({ ...form, documensoTemplateId: e.target.value })}
                    placeholder="Template ID or link" style={{ minWidth: 280 }} />
                  <Button size="small" variant="primary" disabled={!form.name.trim() || !form.documensoTemplateId.trim()} onClick={() => void save(kind)}>
                    {active ? `Save as version ${active.version + 1}` : 'Save'}
                  </Button>
                  <Button size="small" onClick={() => setEditing(null)}>Cancel</Button>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
