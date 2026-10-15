import { Banner, Dialog, Select, Spinner, TextInput } from '@primer/react'
import { DataTable, Table } from '@primer/react/experimental'
import { useMemo, useState } from 'react'
import { api, reason, type MemberRow } from '../api/client'
import { ROLE_LABEL, STATUS_LABEL, STATUS_TONE, Status, TOOL_NAME } from '../components/Status'
import { ToolIcon } from '../components/ToolIcon'

/** Filters a chapter's members by text (name or email), status and chapter role ("none" = no role). */
export function filterMembers(members: MemberRow[], query: string, status: string, role: string) {
  const q = query.trim().toLowerCase()
  return members.filter(
    (m) =>
      (!q || m.name.toLowerCase().includes(q) || (m.email ?? '').toLowerCase().includes(q)) &&
      (!status || m.status === status) &&
      (!role || (role === 'none' ? !m.chapterRole : m.chapterRole === role)),
  )
}

/**
 * The members table. Chapter role and status are separate columns (design draft review: the draft mixed
 * chapter roles, project roles and status in one "Role" column).
 */
export function MembersTable({ members, national = false }: { members: MemberRow[] | null; national?: boolean }) {
  const [claim, setClaim] = useState<MemberRow | null>(null)
  // Links sent from this page, until the members list is loaded again.
  const [sent, setSent] = useState<Record<string, string>>({})
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [role, setRole] = useState('')
  const rows = useMemo(
    () => filterMembers(members ?? [], query, status, role).map((m) => ({ ...m, id: m.personId })),
    [members, query, status, role],
  )

  if (!members) return <div className="center"><Spinner aria-label="Loading members" /></div>

  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      <div className="box">
        <div className="box-head" id="members-title">Members</div>
        <div className="filters">
          <TextInput aria-label="Filter members" placeholder="Filter by name or email" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <Select.Option value="">All statuses</Select.Option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <Select.Option key={value} value={value}>{label}</Select.Option>
            ))}
          </Select>
          <Select aria-label="Chapter role" value={role} onChange={(e) => setRole(e.target.value)}>
            <Select.Option value="">All roles</Select.Option>
            <Select.Option value="lead">Chapter lead</Select.Option>
            <Select.Option value="co_lead">Co-lead</Select.Option>
            <Select.Option value="viewer">Viewer</Select.Option>
            <Select.Option value="none">No chapter role</Select.Option>
          </Select>
          <span className="count">{rows.length} of {members.length}</span>
        </div>
        <div className="table-scroll">
          <Table.Container>
            <DataTable
              aria-labelledby="members-title"
              data={rows}
              columns={[
                {
                  header: 'Name',
                  field: 'name',
                  rowHeader: true,
                  renderCell: (m) => (
                    <div>
                      <strong>{m.name}</strong>
                      <div className="muted">{m.email ?? 'No H4I or school email'}{m.claimed ? '' : ' · not claimed yet'}</div>
                      {national && m.claimable && (
                        <div className="muted">
                          {(sent[m.personId] ?? m.claimLinkSentAt) ? `Claim link sent ${new Date(sent[m.personId] ?? m.claimLinkSentAt!).toLocaleDateString()} · ` : ''}
                          <button type="button" className="timeline-toggle" onClick={() => setClaim(m)}>
                            {(sent[m.personId] ?? m.claimLinkSentAt) ? 'Send a new link' : 'Send claim link'}
                          </button>
                        </div>
                      )}
                    </div>
                  ),
                },
                { header: 'Chapter role', field: 'chapterRole', renderCell: (m) => (m.chapterRole ? `${ROLE_LABEL[m.chapterRole]}${m.title ? ` · ${m.title}` : ''}` : '—') },
                { header: 'Status', field: 'status', renderCell: (m) => <Status tone={STATUS_TONE[m.status] ?? 'off'}>{STATUS_LABEL[m.status] ?? m.status}</Status> },
                {
                  header: 'Tool accounts',
                  field: 'accounts',
                  renderCell: (m) =>
                    m.accounts.length === 0 ? (
                      <span className="muted">None linked</span>
                    ) : (
                      <span style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px' }}>
                        {m.accounts.map((a, i) => (
                          <span key={i} className={`account-chip ${a.state === 'confirmed' ? '' : 'muted'}`} title={`${TOOL_NAME[a.tool] ?? a.tool}: ${a.state}`}>
                            <ToolIcon tool={a.tool} label />{a.state === 'confirmed' ? '' : <span>{a.state}</span>}
                          </span>
                        ))}
                      </span>
                    ),
                },
              ]}
            />
          </Table.Container>
        </div>
      </div>
      {claim && <SendClaim member={claim} onClose={() => setClaim(null)} onSent={(at) => setSent((s) => ({ ...s, [claim.personId]: at }))} />}
    </div>
  )
}


/**
 * National sends one imported member a claim link: someone whose link ran out or went astray, or who was never sent
 * one. It goes to every address on file, and any earlier link of theirs stops working.
 */
function SendClaim({ member, onClose, onSent }: { member: MemberRow; onClose: () => void; onSent: (at: string) => void }) {
  const [state, setState] = useState<'ask' | 'sending' | { addresses: number; until: string }>('ask')
  const [error, setError] = useState<string | null>(null)
  const again = Boolean(member.claimLinkSentAt)
  async function send() {
    setState('sending')
    const { data, error: failed, response } = await api.POST('/api/national/claims/people/{personId}', { params: { path: { personId: member.personId } } })
    if (!data) {
      setState('ask')
      return setError(reason(failed, response, 'Not sent'))
    }
    setState({ addresses: data.addresses, until: data.expiresAt })
    onSent(new Date().toISOString())
  }
  const done = typeof state === 'object'
  return (
    <Dialog title={again ? 'Send a new claim link' : 'Send a claim link'} subtitle={member.name} width="medium" onClose={onClose}
      footerButtons={done ? [{ buttonType: 'primary', content: 'Done', onClick: onClose }] : [
        { buttonType: 'default', content: 'Cancel', onClick: onClose },
        { buttonType: 'primary', content: state === 'sending' ? 'Sending…' : 'Send', disabled: state === 'sending', onClick: () => void send() },
      ]}>
      {error && <Banner title="Not sent" description={error} variant="critical" />}
      {done ? (
        <p>Sent to {state.addresses} {state.addresses === 1 ? 'address' : 'addresses'} on file. The link works once, until {new Date(state.until).toLocaleDateString()}.</p>
      ) : (
        <p>
          {member.name} gets a single-use link, valid 14 days, at every address on file, to claim their record without approval.
          {again ? ' The link they already have stops working.' : ''} Their deadline to claim still counts from their first link.
        </p>
      )}
    </Dialog>
  )
}
