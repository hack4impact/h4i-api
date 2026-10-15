import { Banner, Button, Checkbox, Radio, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type RegistrationSettings as Settings } from '../api/client'

type Question = { label: string; kind: string; options: string; required: boolean }

/**
 * Chapter settings: whether people apply through the portal or the chapter's own process, the chapter's own
 * registration questions, and where new members' Google accounts go.
 */
export function RegistrationSettings({ code }: { code: string }) {
  const [view, setView] = useState<Settings | null>(null)
  const [questions, setQuestions] = useState<Question[]>([])
  const [domain, setDomain] = useState('')
  const [unit, setUnit] = useState('')
  const [open, setOpen] = useState(true)
  const [url, setUrl] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  function load(v: Settings) {
    setView(v)
    setQuestions(v.questions.map((q) => ({ label: q.label, kind: q.kind, options: q.options.join(', '), required: q.required })))
    setDomain(v.googleDomain)
    setUnit(v.googleOrgUnit ?? '')
    setOpen(v.applicationsOpen)
    setUrl(v.applicationUrl ?? '')
  }
  useEffect(() => {
    void api.GET('/api/chapters/{code}/registration-settings', { params: { path: { code } } }).then(({ data }) => data && load(data))
  }, [code])

  async function save(e: React.FormEvent) {
    e.preventDefault()
    const { data, error: failed, response } = await api.PUT('/api/chapters/{code}/registration-settings', {
      params: { path: { code } },
      body: {
        questions: questions.map((q) => ({ label: q.label, kind: q.kind, required: q.required, options: q.options.split(',').map((o) => o.trim()).filter(Boolean) })),
        googleDomain: domain || null,
        googleOrgUnit: unit || null,
        applicationsOpen: open,
        applicationUrl: open ? null : url || null,
      },
    })
    if (!data) return setError(reason(failed, response, 'Not saved'))
    setError(null)
    setSaved(true)
    load(data)
  }

  if (!view) return <Spinner aria-label="Loading registration settings" />
  const edit = view.canEditQuestions
  const set = (i: number, patch: Partial<Question>) => { setQuestions(questions.map((q, j) => (j === i ? { ...q, ...patch } : q))); setSaved(false) }

  return (
    <form className="box" aria-labelledby="registration-settings" onSubmit={save}>
      <div className="box-head" id="registration-settings">Registration</div>
      {error && <div className="box-row"><Banner title="Not saved" description={error} variant="critical" /></div>}
      {saved && !error && <div className="box-row"><Banner title="Saved" variant="success" /></div>}
      <div className="box-row">
        <fieldset className="choice">
          <legend>How people apply</legend>
          <label className="check">
            <Radio name="applications" value="open" disabled={!edit} checked={open} onChange={() => { setOpen(true); setSaved(false) }} />
            Through the portal: anyone with the chapter’s link applies, and you approve them on the Approvals tab
          </label>
          <label className="check">
            <Radio name="applications" value="own" disabled={!edit} checked={!open} onChange={() => { setOpen(false); setSaved(false) }} />
            We run our own application process, then invite the people we accept with a roster
          </label>
          {!open && (
            <label>
              Where to apply instead
              <TextInput disabled={!edit} value={url} onChange={(e) => { setUrl(e.target.value); setSaved(false) }} placeholder="https://forms.gle/… (optional)" />
              <span className="muted">The chapter’s link shows this instead of the portal’s application.</span>
            </label>
          )}
        </fieldset>
      </div>
      {open && (
        <div className="box-row muted">
          Everyone answers name, personal email, graduation year and role. Add your chapter’s own questions below; applicants see them after verifying their school email.
        </div>
      )}
      {open && questions.map((q, i) => (
        <div className="box-row inline-form" key={i} role="group" aria-label={`Question ${i + 1}`}>
          <span className="muted">{i + 1}.</span>
          <TextInput aria-label="Question" disabled={!edit} value={q.label} onChange={(e) => set(i, { label: e.target.value })} placeholder="Why do you want to join?" style={{ minWidth: 260 }} />
          <Select aria-label="Answer type" disabled={!edit} value={q.kind} onChange={(e) => set(i, { kind: e.target.value })}>
            <Select.Option value="text">Short answer</Select.Option>
            <Select.Option value="long_text">Paragraph</Select.Option>
            <Select.Option value="choice">Choice</Select.Option>
            <Select.Option value="yes_no">Yes or no</Select.Option>
          </Select>
          {q.kind === 'choice' && <TextInput aria-label="Options" disabled={!edit} value={q.options} onChange={(e) => set(i, { options: e.target.value })} placeholder="Options, comma-separated" />}
          <label className="check"><Checkbox disabled={!edit} checked={q.required} onChange={(e) => set(i, { required: e.target.checked })} /> Required</label>
          {edit && <Button size="small" variant="invisible" onClick={() => { setQuestions(questions.filter((_, j) => j !== i)); setSaved(false) }}>Remove</Button>}
        </div>
      ))}
      {open && edit && <div className="box-row"><Button onClick={() => setQuestions([...questions, { label: '', kind: 'text', options: '', required: false }])}>Add question</Button></div>}
      <div className="box-row form-grid">
        <strong>New members’ Google accounts</strong>
        <span className="muted">first-name@{domain || 'chapter.hack4impact.org'}{view.canEditAccounts ? '' : '. National sets these.'}</span>
        <label>Domain <TextInput disabled={!view.canEditAccounts} value={domain} onChange={(e) => setDomain(e.target.value)} /></label>
        <label>Org unit <TextInput disabled={!view.canEditAccounts} value={unit} onChange={(e) => setUnit(e.target.value)} placeholder="/University of Maryland, College Park" /></label>
        {!view.googleOrgUnit && <span className="muted">Until the org unit is set, approvals still work and national gets a task to create each account.</span>}
      </div>
      {edit && <div className="box-row"><Button type="submit" variant="primary">Save registration settings</Button></div>}
    </form>
  )
}
