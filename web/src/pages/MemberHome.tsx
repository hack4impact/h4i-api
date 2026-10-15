import { Banner, Spinner } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type Me, type MemberHome as Home } from '../api/client'
import { ROLE_LABEL, STATUS_LABEL, Status, TOOL_NAME } from '../components/Status'
import { ToolIcon } from '../components/ToolIcon'
import { Mascot } from '../components/Mascot'
import { TopBar } from '../components/TopBar'

const ACCOUNT_STATE: Record<string, [string, 'ok' | 'warn' | 'off']> = {
  confirmed: ['Confirmed', 'ok'],
  accepted: ['Confirmed', 'ok'],
  invited: ['Invited', 'warn'],
  unverified: ['On record, not confirmed', 'off'],
  suspended: ['Suspended', 'off'],
  removed: ['Removed', 'off'],
}

const AGREEMENT_STATE: Record<string, [string, 'ok' | 'warn' | 'off']> = {
  signed: ['Signed', 'ok'],
  sent: ['Waiting for your signature', 'warn'],
  declined: ['Declined', 'off'],
  expired: ['Expired', 'off'],
}

/** A member's own page (stage 1): their status, chapters, the accounts H4I has on record for them and what they signed. */
export function MemberHome({ me }: { me: Me }) {
  const [home, setHome] = useState<Home | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    void api.GET('/api/me/home').then(({ data, error: failed, response }) => (data ? setHome(data) : setError(reason(failed, response, 'Your page could not be loaded'))))
  }, [])

  return (
    <>
      <TopBar me={me} />
      {error ? <div className="center"><Banner title="Not available" description={error} variant="critical" /></div>
        : !home ? <div className="center"><Spinner aria-label="Loading your page" /></div>
        : (
          <>
            <div className="page-head">
              <div className="inner">
                <div className="eyebrow">{home.status === 'active' ? 'Member' : STATUS_LABEL[home.status] ?? home.status}</div>
                <h1 className="page-title">{home.name}</h1>
                <div className="member-chapters">
                  {home.chapters.map((c) => (
                    <span key={c.code}>{c.name}{c.role ? ` · ${ROLE_LABEL[c.role] ?? c.role}` : ''}</span>
                  ))}
                </div>
              </div>
            </div>
            <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
              <section className="box" aria-labelledby="my-accounts">
                <div className="box-head" id="my-accounts">Your accounts <span className="Counter">{home.accounts.length}</span></div>
                {home.accounts.length === 0 && <div className="box-row muted mascot-row"><Mascot pose="peek" size={64} />No accounts on record yet.</div>}
                <ul className="adopt-list" aria-label="Your accounts">
                  {home.accounts.map((a, i) => {
                    const [label, tone] = ACCOUNT_STATE[a.state] ?? [a.state, 'off']
                    return (
                      <li key={i}>
                        <div className="access-row">
                          <span className="access-icon"><ToolIcon tool={a.tool} label /></span>
                          <div className="access-line">
                            <span className="res-name">{a.login ?? TOOL_NAME[a.tool] ?? a.tool}</span>
                            <span className="muted">{TOOL_NAME[a.tool] ?? a.tool}</span>
                            <span className="access-state"><Status tone={tone}>{label}</Status></span>
                          </div>
                        </div>
                      </li>
                    )
                  })}
                </ul>
              </section>
              {home.agreements.length > 0 && (
                <section className="box" aria-labelledby="my-agreements">
                  <div className="box-head" id="my-agreements">Your documents <span className="Counter">{home.agreements.length}</span></div>
                  <ul className="adopt-list" aria-label="Your documents">
                    {home.agreements.map((a) => {
                      const [label, tone] = AGREEMENT_STATE[a.status] ?? [a.status, 'off']
                      return (
                        <li key={a.id}>
                          <div className="access-row">
                            <div className="access-line">
                              <span className="res-name">{a.name}</span>
                              {a.signedAt && <span className="muted">{new Date(a.signedAt).toLocaleDateString()}</span>}
                              {a.hasPdf && <a href={`/api/agreements/${a.id}/pdf`}>Download signed copy</a>}
                              <span className="access-state"><Status tone={tone}>{label}</Status></span>
                            </div>
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                </section>
              )}
            </div>
          </>
        )}
    </>
  )
}
