import { PersonIcon, SignOutIcon } from '@primer/octicons-react'
import { ActionList, ActionMenu } from '@primer/react'
import { Link, useNavigate } from 'react-router'
import { type Me, signOut } from '../api/client'
import logo from '../assets/logo.svg'

function initials(me: Me) {
  const source = me.name ?? me.email
  return source.split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((s) => s[0]!.toUpperCase()).join('')
}

/** Header: wordmark, chapter switcher (only chapters the user has a role in), account menu. */
export function TopBar({ me, chapterCode }: { me: Me; chapterCode?: string }) {
  const navigate = useNavigate()
  const current = me.chapters.find((c) => c.code === chapterCode)
  return (
    <header className="topbar">
      <a className="wordmark" href="/">
        <img src={logo} alt="Hack4Impact" />
      </a>
      {me.chapters.length > 0 && (
        <>
          <span className="divider" />
          <ActionMenu>
            <ActionMenu.Button aria-label="Switch chapter">{current?.code.toUpperCase() ?? 'Chapters'}</ActionMenu.Button>
            <ActionMenu.Overlay width="medium">
              <ActionList selectionVariant="single">
                {me.chapters.map((c) => (
                  <ActionList.Item key={c.id} selected={c.code === chapterCode} onSelect={() => navigate(`/chapters/${c.code}`)}>
                    {c.name}
                  </ActionList.Item>
                ))}
              </ActionList>
            </ActionMenu.Overlay>
          </ActionMenu>
        </>
      )}
      {me.nationalAdmin && (
        <Link className="topbar-link" to="/national">National</Link>
      )}
      <span className="spacer" />
      <ActionMenu>
        <ActionMenu.Anchor>
          <button className="initials" aria-label={`Account: ${me.email}`} style={{ border: 0, cursor: 'pointer' }}>
            {initials(me)}
          </button>
        </ActionMenu.Anchor>
        <ActionMenu.Overlay>
          <ActionList>
            <ActionList.Group>
              <ActionList.GroupHeading>{me.email}</ActionList.GroupHeading>
            </ActionList.Group>
            <ActionList.Divider />
            {me.personId && (
              <ActionList.Item onSelect={() => navigate('/me')}>
                <ActionList.LeadingVisual><PersonIcon /></ActionList.LeadingVisual>My membership
              </ActionList.Item>
            )}
            <ActionList.Item onSelect={() => void signOut()}>
              <ActionList.LeadingVisual><SignOutIcon /></ActionList.LeadingVisual>Sign out
            </ActionList.Item>
          </ActionList>
        </ActionMenu.Overlay>
      </ActionMenu>
    </header>
  )
}
