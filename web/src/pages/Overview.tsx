import { Link as PrimerLink } from '@primer/react'
import { Link } from 'react-router'
import { type ChapterOverview, type MemberRow } from '../api/client'
import { allTools } from '../api/tools'
import { ROLE_LABEL, Status } from '../components/Status'
import { ToolName } from '../components/ToolIcon'

/** Chapter overview: leads, how far claims have got, members' accounts, and the side pane with counts and links. */
export function Overview({ chapter, members }: { chapter: ChapterOverview; members: MemberRow[] | null }) {
  const leads = (members ?? []).filter((m) => m.chapterRole)
  const active = (members ?? []).filter((m) => m.status === 'active' || m.status === 'removal_requested')
  // Imported records waiting for their person to claim them, and those who already have.
  const unclaimed = (members ?? []).filter((m) => !m.claimed)
  const claimed = (members ?? []).length - unclaimed.length
  return (
    <div className="page-body">
      <main style={{ display: 'grid', gap: 24, alignContent: 'start' }}>
        <section className="box" aria-labelledby="leads">
          <div className="box-head" id="leads">Leads and viewers</div>
          {leads.length === 0 ? (
            <div className="box-row muted">No chapter roles yet. National grants the first leads.</div>
          ) : (
            leads.map((m) => (
              <div className="box-row" key={m.personId}>
                <strong>{m.name}</strong>{' '}
                <span className="muted">
                  {ROLE_LABEL[m.chapterRole!]}
                  {m.title ? ` · ${m.title}` : ''}
                </span>
              </div>
            ))
          )}
        </section>
        <section className="box" aria-labelledby="claims">
          <div className="box-head" id="claims">Claims</div>
          <div className="box-row" style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
            <span>Members who have confirmed their details</span>
            <Status tone={unclaimed.length === 0 && claimed > 0 ? 'ok' : 'off'}>{claimed} of {(members ?? []).length}</Status>
          </div>
          <div className="box-row muted">
            Imported members confirm their details through a claim link from national. Until they do, their record is as the member database had it.
          </div>
        </section>
        <section className="box" aria-labelledby="coverage">
          <div className="box-head" id="coverage">Accounts of active members</div>
          {allTools().map(({ key: tool }) => {
            const linked = active.filter((m) => m.accounts.some((a) => a.tool === tool)).length
            return (
              <div className="box-row" key={tool} style={{ display: 'flex', justifyContent: 'space-between' }}>
                <ToolName tool={tool} />
                <Status tone={linked === active.length && active.length > 0 ? 'ok' : 'off'}>
                  {linked} of {active.length} linked
                </Status>
              </div>
            )
          })}
          <div className="box-row muted">The accounts the portal knows about.</div>
        </section>
      </main>
      <aside>
        <div className="pane-section" style={{ paddingTop: 0 }}>
          <div className="pane-label">This chapter</div>
          <div className="stats">
            <Stat label="Active members" value={chapter.stats.activeMembers} />
            <Stat label="Alumni" value={chapter.stats.alumni} />
            <Stat label="Waiting for approval" value={chapter.stats.waitingApplications} />
            <Stat label="Leads" value={chapter.stats.leads} />
          </div>
        </div>
        <div className="pane-section">
          <div className="pane-label">Chapter links</div>
          <div>
            <PrimerLink href={chapter.registrationLink}>Registration link</PrimerLink>{' '}
            <span className="muted">{chapter.registrationLink.replace(/^https?:\/\//, '')}</span>
          </div>
          <div>
            <PrimerLink as={Link} to={`/chapters/${chapter.code}/members`}>Members</PrimerLink>
          </div>
          <div>
            <PrimerLink as={Link} to={`/chapters/${chapter.code}/approvals`}>Approvals</PrimerLink>
          </div>
        </div>
        <div className="dots" aria-hidden="true" />
      </aside>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}
