import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TOOLS } from './test/tools'
import { App } from './App'

/** Answers API calls from a table of URL path (or "METHOD path", which wins) → [status, body]. */
function api(routes: Record<string, [number, unknown]>) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    const path = new URL(typeof input === 'string' ? input : input.url, 'http://localhost').pathname
    const method = typeof input === 'string' ? 'GET' : input.method
    const [status, body] = routes[`${method} ${path}`] ?? routes[path] ?? (path === '/api/tools' ? [200, TOOLS] : [404, {}])
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }))
}

const me = (chapters: unknown[]) => ({ email: 'lena@hack4impact.org', name: 'Lena Lead', personId: 'p1', nationalAdmin: false, chapters })
const umd = { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', role: 'lead' }

afterEach(() => {
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

describe('App', () => {
  it('still opens pages when only the tool list fails to load, and says so', async () => {
    window.history.pushState({}, '', '/me')
    api({
      '/api/me': [200, { ...me([]), email: 'marcus@hack4impact.org', name: 'Marcus Chen' }],
      '/api/tools': [500, {}],
      '/api/me/home': [200, { name: 'Marcus Chen', status: 'active', chapters: [], accounts: [], agreements: [] }],
    })
    render(<App />)
    expect(await screen.findByText('Tool names didn’t load')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Marcus Chen' })).toBeInTheDocument()
  })

  it('asks a signed-out visitor to sign in with Google', async () => {
    api({ '/api/me': [401, {}] })
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Sign in with Google' })).toBeInTheDocument()
  })

  it('explains a refused account after Google sends it back', async () => {
    window.history.pushState({}, '', '/?signin=denied')
    api({ '/api/me': [401, {}] })
    render(<App />)
    expect(await screen.findByText(/Use your Hack4Impact Google account/)).toBeInTheDocument()
  })

  it('tells someone whose Google account isn’t linked to a member that they have no access', async () => {
    api({ '/api/me': [200, { ...me([]), personId: null }] })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'No chapter access yet' })).toBeInTheDocument()
  })

  it('shows a member without a chapter role their chapters and the accounts H4I has for them', async () => {
    api({
      '/api/me': [200, { ...me([]), email: 'marcus@hack4impact.org', name: 'Marcus Chen' }],
      '/api/me/home': [200, {
        name: 'Marcus Chen', status: 'active', chapters: [{ code: 'demo', name: 'Hack4Impact Demo University', role: null }],
        accounts: [
          { tool: 'google', login: 'marcus@demo.hack4impact.org', state: 'confirmed' },
          { tool: 'github', login: 'marcus-c', state: 'unverified' },
        ],
        agreements: [{ id: 'a1', name: 'Volunteer agreement', status: 'signed', signedAt: '2026-10-14T12:00:00Z', hasPdf: true }],
      }],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Marcus Chen' })).toBeInTheDocument()
    expect(screen.getByText('Hack4Impact Demo University')).toBeInTheDocument()
    const accounts = screen.getByRole('list', { name: 'Your accounts' })
    expect(within(accounts).getByText('marcus@demo.hack4impact.org')).toBeInTheDocument()
    expect(within(accounts).getByText('Confirmed')).toBeInTheDocument()
    expect(within(accounts).getByText('On record, not confirmed')).toBeInTheDocument() // imported, not yet confirmed by signing in
    const documents = screen.getByRole('list', { name: 'Your documents' })
    expect(within(documents).getByText('Volunteer agreement')).toBeInTheDocument()
    expect(within(documents).getByRole('link', { name: 'Download signed copy' })).toHaveAttribute('href', '/api/agreements/a1/pdf')
  })

  it("takes a lead to their chapter, and the members tab shows status as words and filters", async () => {
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, waitingApplications: 3, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd' }],
      '/api/chapters/umd/members': [200, [
        { personId: 'p1', name: 'Lena Lead', email: 'lena@hack4impact.org', status: 'active', kind: 'student', chapterRole: 'lead', title: null, accounts: [{ tool: 'google', state: 'confirmed' }], claimed: true, claimable: false, claimLinkSentAt: null },
        { personId: 'p2', name: 'Alan Alumnus', email: null, status: 'alumni', kind: 'student', chapterRole: null, title: null, accounts: [], claimed: false, claimable: true, claimLinkSentAt: null },
      ]],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Hack4Impact UMD' })).toBeInTheDocument()
    expect(screen.getByText('Registration link')).toHaveAttribute('href', 'https://join.hack4impact.org/umd')
    expect(within(screen.getByRole('region', { name: 'Claims' })).getByText('1 of 2')).toBeInTheDocument() // Alan's imported record isn't claimed yet
    expect(within(screen.getByRole('navigation', { name: 'Chapter sections' })).getByRole('link', { name: /Approvals/ })).toHaveTextContent('3') // waiting for a lead

    await userEvent.click(within(screen.getByRole('navigation', { name: 'Chapter sections' })).getByRole('link', { name: /Members/ }))
    const table = await screen.findByRole('table', { name: 'Members' })
    expect(within(table).getByText('Alumni')).toBeInTheDocument() // status in words, not just a colored dot
    expect(within(table).getByText(/not claimed yet/)).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /claim link/ })).not.toBeInTheDocument() // national only

    await userEvent.type(screen.getByRole('textbox', { name: 'Filter members' }), 'lena')
    await waitFor(() => expect(within(table).queryByText('Alan Alumnus')).not.toBeInTheDocument())
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
  })

  it('lets national send one imported member a claim link from the members table', async () => {
    window.history.pushState({}, '', '/chapters/umd/members')
    api({
      '/api/me': [200, { ...me([umd]), nationalAdmin: true }],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 1, leads: 1 }, registrationLink: null }],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/members': [200, [
        { personId: 'p1', name: 'Lena Lead', email: 'lena@hack4impact.org', status: 'active', kind: 'student', chapterRole: 'lead', title: null, projects: [], accounts: [], claimed: true, claimable: false, claimLinkSentAt: null },
        { personId: 'p2', name: 'Alan Alumnus', email: null, status: 'alumni', kind: 'student', chapterRole: null, title: null, projects: [], accounts: [], claimed: false, claimable: true, claimLinkSentAt: null },
      ]],
      'POST /api/national/claims/people/p2': [200, { addresses: 2, expiresAt: '2026-10-27T12:00:00Z' }],
    })
    render(<App />)
    const table = await screen.findByRole('table', { name: 'Members' })
    expect(within(table).getAllByRole('button', { name: 'Send claim link' })).toHaveLength(1) // only the imported, unclaimed one
    await userEvent.click(within(table).getByRole('button', { name: 'Send claim link' }))
    const dialog = await screen.findByRole('dialog', { name: 'Send a claim link' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(await within(dialog).findByText(/Sent to 2 addresses on file/)).toBeInTheDocument()
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    expect(calls().some((r) => r.method === 'POST' && r.url.endsWith('/api/national/claims/people/p2'))).toBe(true)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    expect(within(table).getByRole('button', { name: 'Send a new link' })).toBeInTheDocument()
  })

  it('lets national work through the admin queue', async () => {
    window.history.pushState({}, '', '/national')
    api({
      '/api/me': [200, { ...me([umd]), nationalAdmin: true }],
      'GET /api/national/queue': [200, [{ id: 't1', tool: 'google', action: 'create_account', description: 'Create a Google account for Ada Lovelace at umd.hack4impact.org', createdAt: '2026-10-10T12:00:00Z', status: 'open', doneBy: null, doneAt: null }]],
      'POST /api/national/queue/t1': [200, []],
      '/api/national/claims/preview': [200, { eligible: 3, alreadySent: 0, withoutEmail: 1 }],
    })
    render(<App />)
    const open = await screen.findByRole('list', { name: 'Open tasks' })
    expect(within(open).getByText(/Create a Google account for Ada Lovelace/)).toBeInTheDocument()
    await userEvent.click(within(open).getByRole('button', { name: 'Done' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    const done = await waitFor(() => calls().find((r) => r.method === 'POST' && r.url.endsWith('/queue/t1'))!)
    expect(await done.clone().json()).toEqual({ action: 'done', reason: null })
    expect(await screen.findByText('Nothing to do.')).toBeInTheDocument()
    expect(await screen.findByText(/3 to send/)).toBeInTheDocument() // claim links sit beside the queue
  })

  it('shows national the member database: waiting records with what would be written, drift, and the switches', async () => {
    window.history.pushState({}, '', '/national')
    const status = (over: object = {}) => ({
      configured: true, writeSwitch: true, scheduledPull: true, dryRun: true, paused: false, pauseReason: null,
      lastPullAt: '2026-10-14T12:00:00Z', lastPullStatus: 'unchanged', lastPullError: null,
      waiting: [{ personId: 'p1', name: 'Ada Lovelace', requestedAt: '2026-10-14T11:00:00Z', attempts: 0, nextTryAt: null, lastError: null }],
      failed: [],
      drift: [{ personId: 'p2', name: 'Alan Turing', field: 'volunteers.first_name', portalValue: 'Alan', memberDbValue: 'Al', since: '2026-10-13T12:00:00Z' }],
      ...over,
    })
    api({
      '/api/me': [200, { ...me([umd]), nationalAdmin: true }],
      'GET /api/national/queue': [200, []],
      '/api/national/claims/preview': [200, { eligible: 0, alreadySent: 0, withoutEmail: 0 }],
      'GET /api/national/member-db': [200, status()],
      'PUT /api/national/member-db': [200, status({ dryRun: false })],
      '/api/national/member-db/writes/p1/preview': [200, { newVolunteer: true, fields: { 'volunteers.email': 'ada@personal.test', 'academic_profiles.major': 'Computer Science' }, notes: ['No fall 2028 term at their school yet: graduation left out until it’s seeded'] }],
    })
    render(<App />)
    const box = await screen.findByRole('region', { name: /Member database/ })
    expect(within(box).getByText('Dry run')).toBeInTheDocument()
    expect(within(box).getByText(/nothing new/)).toBeInTheDocument()
    await userEvent.click(within(within(box).getByRole('list', { name: 'Waiting writes' })).getByRole('button', { name: 'What would be written' }))
    expect(await within(box).findByText('ada@personal.test')).toBeInTheDocument()
    expect(within(box).getByText('A new volunteer')).toBeInTheDocument()
    expect(within(box).getByText(/graduation left out until it’s seeded/)).toBeInTheDocument()
    const drift = within(box).getByRole('list', { name: 'Changed in the member database' })
    expect(within(drift).getByText(/First name/)).toBeInTheDocument() // the column in words
    await userEvent.click(within(box).getByRole('button', { name: 'Turn writes on' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    const put = await waitFor(() => calls().find((r) => r.method === 'PUT' && r.url.endsWith('/api/national/member-db'))!)
    expect(await put.clone().json()).toEqual({ dryRun: false, paused: false, reason: null })
    expect(await within(box).findByText('Writing')).toBeInTheDocument()
  })

  const applicant = (over: object = {}) => ({
    kind: 'new', status: 'started', schoolEmail: 'ada@umd.edu', emailVerified: true, githubLogin: null, githubConfigured: true,
    chapter: { code: 'umd', name: 'Hack4Impact UMD', emailDomains: ['umd.edu'], applicationsOpen: true, applicationUrl: null, questions: [{ id: 'q1', label: 'Why join?', kind: 'long_text', options: [], required: true }] },
    details: { firstName: null, lastName: null, preferredName: null, personalEmail: null, graduationYear: null, roleInterest: null, stillActive: null },
    answers: {}, missing: ['your GitHub account', 'your name'], declineReason: null, account: null, agreements: [], ...over,
  })

  it('takes a new applicant from the chapter link through a code to a sent application', async () => {
    window.history.pushState({}, '', '/join/umd')
    api({
      '/api/apply/chapters/umd': [200, applicant().chapter],
      '/api/apply/chapters/umd/code': [202, {}],
      '/api/apply/chapters/umd/verify': [200, applicant()],
      'PUT /api/apply/me': [200, applicant({ githubLogin: 'ada-gh', missing: [] })],
      '/api/apply/me/submit': [200, applicant({ status: 'submitted' })],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Hack4Impact UMD' })).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /School email/ }), 'ada@umd.edu')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await userEvent.type(await screen.findByRole('textbox', { name: 'Code' }), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText(/Still needed: your GitHub account, your name/)).toBeInTheDocument() // the checklist, in words
    expect(screen.getByRole('button', { name: 'Sign in with GitHub' })).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /First name/ }), 'Ada')
    // What the member database's school profile needs (Q48).
    await userEvent.type(screen.getByRole('textbox', { name: /Major/ }), 'Computer Science')
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Degree' }), "Bachelor's")
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Graduating in' }), 'spring')
    await userEvent.type(screen.getByRole('spinbutton', { name: /Year/ }), '2028')
    await userEvent.type(screen.getByRole('textbox', { name: /Why join\?/ }), 'To build')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    const put = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'PUT')!
    expect(await put.clone().json()).toMatchObject({
      details: { firstName: 'Ada', major: 'Computer Science', degreeType: "Bachelor's", graduationSeason: 'spring', graduationYear: 2028 }, answers: { q1: 'To build' },
    })
    await userEvent.click(await screen.findByRole('button', { name: 'Send application' }))
    expect(await screen.findByRole('heading', { name: 'Application sent' })).toBeInTheDocument()
  })

  it('sends applicants to the chapter’s own application when it has turned the portal’s off', async () => {
    window.history.pushState({}, '', '/join/umd')
    api({ '/api/apply/chapters/umd': [200, { ...applicant().chapter, applicationsOpen: false, applicationUrl: 'https://forms.gle/umd' }] })
    render(<App />)
    expect(await screen.findByText(/runs its own application process/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Apply to Hack4Impact UMD' })).toHaveAttribute('href', 'https://forms.gle/umd')
    expect(screen.queryByRole('textbox', { name: /School email/ })).not.toBeInTheDocument()
  })

  it('has applicants sign the volunteer agreement in Documenso before they can send', async () => {
    window.history.pushState({}, '', '/apply')
    const view = applicant({
      githubLogin: 'ada-gh', missing: ['the Volunteer agreement'],
      agreements: [{ kind: 'volunteer', name: 'Volunteer agreement', status: 'not_started', signUrl: null }, { kind: 'code_of_conduct', name: 'Code of conduct', status: 'signed', signUrl: null }],
    })
    api({
      '/api/apply/me': [200, view],
      'PUT /api/apply/me': [200, view],
      '/api/apply/me/agreements/volunteer': [200, { url: 'https://sign.example/sign/tok' }],
    })
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Send application' })).toBeDisabled() // not signed yet
    expect(screen.getByText('Signed')).toBeInTheDocument() // the code of conduct
    await userEvent.click(screen.getByRole('button', { name: 'Read and sign' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    await waitFor(() => expect(calls().some((r) => r.url.endsWith('/api/apply/me/agreements/volunteer'))).toBe(true))
    expect(calls().findIndex((r) => r.method === 'PUT')).toBeLessThan(calls().findIndex((r) => r.url.endsWith('/agreements/volunteer'))) // saved first: the name goes on it
  })

  it('lets an imported member claim their record and say they have moved on', async () => {
    window.history.pushState({}, '', '/claim/tok123')
    const claim = applicant({ kind: 'claim', githubLogin: 'ada-gh', details: { ...applicant().details, firstName: 'Ada', lastName: 'Lovelace' }, missing: ['whether you’re still in the chapter'] })
    api({
      '/api/apply/claim': [200, claim],
      'PUT /api/apply/me': [200, { ...claim, missing: [] }],
      '/api/apply/me/submit': [200, { ...claim, status: 'completed', account: 'ada@umd.hack4impact.org' }],
    })
    render(<App />)
    expect(await screen.findByText('Claim your record')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /First name/ })).toHaveValue('Ada') // pre-filled from the import
    await userEvent.click(screen.getByRole('radio', { name: /I’ve moved on/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Claim my record' }))
    expect(await screen.findByRole('heading', { name: 'Record claimed' })).toBeInTheDocument()
    expect(screen.getByText('ada@umd.hack4impact.org')).toBeInTheDocument()
    const put = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'PUT')!
    expect((await put.clone().json()).details.stillActive).toBe(false)
  })

  it('hides the questions and the empty waiting list when the chapter runs its own application process', async () => {
    const chapter = { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 0 }
    const settings = (open: boolean) => ({
      questions: [{ id: 'q1', label: 'Why join?', kind: 'long_text', options: [], required: true }], googleDomain: 'umd.hack4impact.org', googleOrgUnit: '/UMD',
      applicationsOpen: open, applicationUrl: open ? null : 'https://forms.gle/umd', canEditQuestions: true, canEditAccounts: false,
    })
    const routes = {
      '/api/me': [200, me([umd])], '/api/chapters/umd': [200, chapter], '/api/chapters/umd/members': [200, []], '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/registrations': [200, []], '/api/chapters/umd/roster': [200, []],
      '/api/chapters/umd/notion': [200, { teamspaceId: null, defaultParent: null, template: null, titlePattern: '{project} ({semester})', routes: [], configured: false, problems: 0, canEdit: true }],
    } satisfies Record<string, [number, unknown]>

    window.history.pushState({}, '', '/chapters/umd/settings')
    api({ ...routes, '/api/chapters/umd/registration-settings': [200, settings(true)] })
    const { unmount } = render(<App />)
    expect(await screen.findByRole('button', { name: 'Add question' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: /our own application process/ }))
    expect(screen.queryByRole('button', { name: 'Add question' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Question' })).not.toBeInTheDocument()
    unmount()

    window.history.pushState({}, '', '/chapters/umd/approvals')
    api({ ...routes, '/api/chapters/umd/registration-settings': [200, settings(false)] })
    render(<App />)
    expect(await screen.findByText('Invite a roster')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Waiting for a decision')).not.toBeInTheDocument())
  })

  it('shows leads the match and lets them approve as a matched record or decline with a reason', async () => {
    window.history.pushState({}, '', '/chapters/umd/approvals')
    const reg = {
      id: 'r1', kind: 'new', status: 'submitted', name: 'Ada Lovelace', schoolEmail: 'ada@umd.edu', githubLogin: 'ada-gh', graduationYear: 2028, roleInterest: 'Developer',
      matchStatus: 'duplicate', matches: [{ personId: 'p9', name: 'Ada Lovelace', status: 'alumni', chapters: ['Hack4Impact UMD'], why: ['same email'] }, { personId: 'p8', name: 'Ada L', status: 'active', chapters: [], why: ['same name in this chapter'], account: 'ada@umd.hack4impact.org' }],
      answers: { 'Why join?': 'To build' }, githubChanged: false, submittedAt: '2026-10-11T12:00:00Z', decidedAt: null, declineReason: null,
    }
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 0 }],
      '/api/chapters/umd/members': [200, []],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/registrations': [200, [reg]],
      '/api/chapters/umd/registrations/r1/approve': [200, []],
      '/api/chapters/umd/roster': [200, []],
    })
    render(<App />)
    const list = await screen.findByRole('list', { name: 'Waiting for a decision' })
    expect(within(list).getByText('Possible duplicate')).toBeInTheDocument()
    expect(within(list).getByText(/same email/)).toBeInTheDocument()
    expect(within(list).getByText('To build')).toBeInTheDocument()

    await userEvent.click(within(list).getByRole('button', { name: 'Decline' }))
    expect(within(list).getAllByRole('button', { name: 'Decline' }).at(-1)).toBeDisabled() // a reason first
    await userEvent.click(within(list).getByRole('button', { name: 'Cancel' }))

    await userEvent.click(within(list).getAllByRole('radio')[1]!) // the alumni record, but Ada L already has an H4I account
    expect(within(list).getByText('This would give them a second H4I account')).toBeInTheDocument()
    expect(within(list).getByRole('button', { name: 'Approve with a second account' })).toBeInTheDocument()
    await userEvent.click(within(list).getAllByRole('radio')[2]!) // the record with the account
    expect(within(list).queryByText('This would give them a second H4I account')).not.toBeInTheDocument()
    await userEvent.click(within(list).getByRole('button', { name: 'Approve as this record' }))
    const approve = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.url.endsWith('/approve'))!
    expect(await approve.clone().json()).toEqual({ personId: 'p8', secondAccount: false })
  })
})
