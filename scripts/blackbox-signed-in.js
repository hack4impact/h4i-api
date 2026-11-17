// Signed-in black-box checks of the stage 1 API, written from docs/API.md. Runs in a browser tab on the API's origin,
// after Google sign-in, as a national admin who also leads UMD. Phases share state on window.bb.
window.bb = window.bb || { results: [], state: {} };
(() => {
  const bb = window.bb;
  bb.xsrf = () => decodeURIComponent((document.cookie.match(/XSRF-TOKEN=([^;]+)/) || [])[1] || '');
  bb.api = async (method, path, body, opts = {}) => {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && !opts.noCsrf) headers['X-XSRF-TOKEN'] = bb.xsrf();
    const r = await fetch(path, { method, headers, credentials: 'same-origin', redirect: 'manual', body: body === undefined ? undefined : JSON.stringify(body) });
    const text = r.type === 'opaqueredirect' ? '' : await r.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch (e) {}
    return { status: r.status, json, text, type: r.type };
  };
  bb.check = (name, ok, got) => { bb.results.push({ name, ok: !!ok, got: ok ? undefined : (typeof got === 'string' ? got : JSON.stringify(got)).slice(0, 300) }); return !!ok; };
  bb.has = (o, keys) => o && keys.every(k => k in o);
  bb.summary = () => ({ total: bb.results.length, passed: bb.results.filter(r => r.ok).length, failed: bb.results.filter(r => !r.ok) });

  bb.phaseA = async () => {
    const { api, check, has, state } = bb;
    const ts = Date.now().toString(36);
    state.ts = ts;
    await api('GET', '/api/status'); // sets XSRF-TOKEN

    // ---- 6.2 Signed-in members
    let r = await api('GET', '/api/me');
    check('me: 200 with email, name, personId, nationalAdmin, chapters', r.status === 200 && has(r.json, ['email', 'name', 'personId', 'nationalAdmin', 'chapters']), r);
    state.me = r.json || {};
    check('me: linked to a person', state.me.personId, r.json);
    check('me: national admin', state.me.nationalAdmin === true, r.json);
    const chapters = state.me.chapters || [];
    check('me: chapters have id, code, name, role', chapters.length > 0 && chapters.every(c => has(c, ['id', 'code', 'name', 'role'])), chapters);
    check('me: every role is lead, co_lead, viewer or national', chapters.every(c => ['lead', 'co_lead', 'viewer', 'national'].includes(c.role)), chapters);
    check('me: national sees gt as national', (chapters.find(c => c.code === 'gt') || {}).role === 'national', chapters);
    r = await api('GET', '/api/me/home');
    check('me/home: 200 with agreements, projects, access', r.status === 200 && has(r.json, ['agreements', 'projects', 'access']), r);
    r = await api('GET', '/api/tools');
    check('tools: slack, google, github, notion, vaultwarden in order', r.status === 200 && JSON.stringify(r.json.map(t => t.key)) === '["slack","google","github","notion","vaultwarden"]' && r.json.every((t, i) => t.order === i + 1 && t.name), r);

    // ---- 6.3 Chapters
    r = await api('GET', '/api/chapters/umd');
    check('chapter: overview fields', r.status === 200 && has(r.json, ['id', 'code', 'name', 'status', 'role', 'stats', 'registrationLink']) && has(r.json.stats, ['activeMembers', 'alumni', 'waitingApplications', 'leads']), r);
    check('chapter: registrationLink ends with /umd', r.json && /\/umd$/.test(r.json.registrationLink), r.json);
    r = await api('GET', '/api/chapters/nope');
    check('chapter: unknown is 404 "No chapter nope"', r.status === 404 && r.json && r.json.detail === 'No chapter nope', r);
    r = await api('GET', '/api/chapters/umd/members');
    state.members = r.json || [];
    check('members: 200, an array of rows with name and email', r.status === 200 && Array.isArray(r.json) && r.json.every(m => 'name' in m && 'email' in m), r.text.slice(0, 300));
    check('members: no personal email field anywhere', !/personalEmail|personal_email/.test(r.text), 'found a personal email key');
    const firstNoRole = state.members.findIndex(m => !m.chapterRole);
    check('members: people with a chapter role come first', firstNoRole < 0 || state.members.slice(firstNoRole).every(m => !m.chapterRole), state.members.map(m => m.chapterRole));
    check('members: rows have claimable and claimLinkSentAt', state.members.every(m => 'claimable' in m && 'claimLinkSentAt' in m), state.members[0]);
    r = await api('GET', '/api/chapters/umd/registrations');
    check('registrations: 200, an array', r.status === 200 && Array.isArray(r.json), r);
    check('registrations: no claims and no unsent applications listed', (r.json || []).every(x => x.kind !== 'claim' && x.status !== 'started'), r.json);

    // Settings
    r = await api('GET', '/api/chapters/umd/registration-settings');
    state.settings = r.json;
    check('settings: fields', r.status === 200 && has(r.json, ['questions', 'googleDomain', 'googleOrgUnit', 'applicationsOpen', 'applicationUrl', 'canEditQuestions', 'canEditAccounts']), r);
    check('settings: a lead who is national may edit both', r.json && r.json.canEditQuestions === true && r.json.canEditAccounts === true, r.json);
    const put = body => api('PUT', '/api/chapters/umd/registration-settings', body);
    r = await put({ questions: [{ label: ' ', kind: 'text', options: [], required: true }] });
    check('settings: "Question 1 needs a label"', r.status === 400 && r.json.detail === 'Question 1 needs a label', r);
    r = await put({ questions: [{ label: 'Q', kind: 'nope', options: [], required: true }] });
    check('settings: "Question 1: unknown kind"', r.status === 400 && r.json.detail === 'Question 1: unknown kind', r);
    r = await put({ questions: [{ label: 'Q', kind: 'choice', options: ['one', ' '], required: true }] });
    check('settings: "Question 1: a choice needs at least two options"', r.status === 400 && r.json.detail === 'Question 1: a choice needs at least two options', r);
    r = await put({ questions: [], applicationsOpen: false, applicationUrl: 'http://example.org' });
    check('settings: "The application link should start with https://"', r.status === 400 && r.json.detail === 'The application link should start with https://', r);
    const questions = [{ label: 'Why do you want to join?', kind: 'long_text', options: [], required: false }, { label: 'Team', kind: 'choice', options: ['Design', 'Engineering'], required: false }];
    r = await put({ questions, applicationsOpen: true, applicationUrl: null, googleDomain: state.settings.googleDomain, googleOrgUnit: state.settings.googleOrgUnit });
    check('settings: saved, two questions in order with ids', r.status === 200 && r.json.questions.length === 2 && r.json.questions[0].label === questions[0].label && r.json.questions.every(q => q.id), r);
    const ids = r.json && r.json.questions.map(q => q.id);
    r = await put({ questions });
    check('settings: unchanged questions keep their ids', r.status === 200 && JSON.stringify(r.json.questions.map(q => q.id)) === JSON.stringify(ids), r.json);
    check('settings: applicationsOpen left out leaves the switch', r.json && r.json.applicationsOpen === true, r.json);
    check('settings: every kind in the docs is accepted', (await put({ questions: ['text', 'long_text', 'yes_no'].map(kind => ({ label: kind, kind, options: [], required: false })) })).status === 200, 'a documented kind was refused');
    await put({ questions }); // optional questions, so the applicant phase can send a form

    // Roster
    const roster = csv => api('POST', '/api/chapters/umd/roster', { csv });
    r = await api('GET', '/api/chapters/umd/roster');
    check('roster: 200, an array', r.status === 200 && Array.isArray(r.json), r);
    r = await roster('  \n ');
    check('roster: "The roster is empty"', r.status === 400 && r.json.detail === 'The roster is empty', r);
    r = await roster('name,email\nA,b@umd.edu');
    check('roster: header error', r.status === 400 && r.json.detail === 'The first line needs columns first_name, last_name, school_email (and optionally personal_email)', r);
    r = await roster('first_name,last_name,school_email\n' + Array.from({ length: 501 }, (_, i) => `A,B,x${i}@umd.edu`).join('\n'));
    check('roster: "At most 500 people per roster"', r.status === 400 && r.json.detail === 'At most 500 people per roster', r);
    state.rosterEmail = `bb-roster-${ts}@umd.edu`;
    r = await roster(`First Name,Last Name,School Email,Personal Email\nRosa,"Smith, Jr.",${state.rosterEmail},\nNo,,\nBad,Mail,not-an-address,\nPat,Lee,bb-roster2-${ts}@umd.edu,nope`);
    check('roster: invited 1, skipped 3 (no name, bad address, bad personal address); header case and spaces accepted', r.status === 200 && r.json.invited === 1 && r.json.skipped.length === 3, r.json && { invited: r.json.invited, skipped: r.json.skipped });
    const invite = r.json && (r.json.invites || []).find(i => i.schoolEmail === state.rosterEmail);
    check('roster: the invite is listed as sent, with the quoted last name', invite && invite.status === 'sent' && invite.name === 'Rosa Smith, Jr.', invite || (r.json && r.json.invites && r.json.invites[0]));
    r = await roster(`first_name,last_name,school_email\nRosa,Smith,${state.rosterEmail.toUpperCase()}`);
    check('roster: an address with an open invite is skipped, in any case', r.status === 200 && r.json.invited === 0 && /is already invited/.test(r.json.skipped[0] || ''), r.json && { invited: r.json.invited, skipped: r.json.skipped });

    // ---- 6.5 National
    r = await api('GET', '/api/national/queue');
    check('queue: 200, an array', r.status === 200 && Array.isArray(r.json), r);
    const none = '00000000-0000-4000-8000-000000000000';
    r = await api('POST', `/api/national/queue/${none}`, { action: 'done' });
    check('queue: 404 "No such task"', r.status === 404 && r.json.detail === 'No such task', r);
    r = await api('GET', '/api/national/tools');
    check('tools status: slack, google, github, vaultwarden with their fields', r.status === 200 && JSON.stringify(r.json.map(t => t.tool)) === '["slack","google","github","vaultwarden"]' && r.json.every(t => has(t, ['tool', 'enabled', 'dryRun', 'writable', 'pauseReason', 'maxRemovals', 'lastRun'])), r);
    check('tools status: every tool is in dry run', (r.json || []).every(t => t.dryRun === true), r.json);
    r = await api('PUT', '/api/national/tools/notion', { enabled: true, dryRun: true, reason: null });
    check('tools: 404 for a tool that doesn\'t sync', r.status === 404, r);
    r = await api('PUT', '/api/national/tools/slack', { enabled: false, dryRun: true, reason: null });
    check('tools: "Say why the tool is paused"', r.status === 400 && r.json.detail === 'Say why the tool is paused', r);
    r = await api('PUT', '/api/national/tools/slack', { enabled: true, dryRun: false, reason: null });
    check('tools: 409 leaving dry run without the write switch', r.status === 409, r);
    r = await api('PUT', '/api/national/tools/slack', { enabled: false, dryRun: true, reason: 'black-box test' });
    check('tools: paused with a reason', r.status === 200 && (r.json.find(t => t.tool === 'slack') || {}).pauseReason === 'black-box test', r);
    r = await api('PUT', '/api/national/tools/slack', { enabled: true, dryRun: true, reason: null });
    check('tools: back on, still in dry run', r.status === 200 && (r.json.find(t => t.tool === 'slack') || {}).enabled === true, r);
    r = await api('GET', '/api/national/dead-letters');
    check('dead letters: 200, an array', r.status === 200 && Array.isArray(r.json), r);
    r = await api('POST', '/api/national/dead-letters/retry', []);
    check('dead letters: retry all returns the list', r.status === 200 && Array.isArray(r.json), r);
    r = await api('POST', '/api/national/sync/run');
    check('sync: run answers 202', r.status === 202, r);
    r = await api('GET', '/api/national/chapters/umd/resources');
    check('national resources: 200, an array', r.status === 200 && Array.isArray(r.json), r);
    r = await api('POST', '/api/national/chapters/umd/resources', { tool: 'slack', name: '#umd', externalId: 'C0123' });
    check('national resources: 422 with both name and externalId', r.status === 422, r);
    r = await api('POST', '/api/national/chapters/umd/resources', { tool: 'slack' });
    check('national resources: 422 with neither', r.status === 422, r);
    r = await api('POST', '/api/national/chapters/umd/resources', { tool: 'slack', name: '#umd' });
    check('national resources: 503 when the portal isn\'t connected to the tool', r.status === 503, r);
    r = await api('DELETE', `/api/national/resources/${none}?mode=detach`);
    check('national resources: deleting an unknown one is 4xx', r.status >= 400 && r.status < 500, r);

    r = await api('GET', '/api/national/claims/preview?chapter=umd');
    state.claims = r.json;
    check('claims preview: eligible, alreadySent, withoutEmail', r.status === 200 && has(r.json, ['eligible', 'alreadySent', 'withoutEmail']), r);
    r = await api('GET', '/api/national/claims/preview');
    check('claims preview: every chapter counts at least as many', r.status === 200 && r.json.eligible >= state.claims.eligible, r);
    r = await api('POST', `/api/national/claims/people/${none}`);
    check('claims: 404 "No such person"', r.status === 404 && r.json.detail === 'No such person', r);
    r = await api('POST', `/api/national/claims/people/${state.me.personId}`);
    check('claims: 409 for someone with nothing to claim', r.status === 409, r);

    r = await api('GET', '/api/national/member-db');
    check('member db: status fields', r.status === 200 && has(r.json, ['configured', 'writeSwitch', 'scheduledPull', 'dryRun', 'paused', 'pauseReason', 'waiting', 'failed', 'drift', 'projectsEnabled', 'projectWrites', 'leadershipWrites']), r);
    state.memberDb = r.json || {};
    r = await api('PUT', '/api/national/member-db', { dryRun: false, paused: false, reason: null });
    check('member db: 409 leaving dry run without the write switch', state.memberDb.writeSwitch ? true : r.status === 409 && /write switch \(PORTAL_MEMBERDB_WRITE\)/.test(r.json.detail), r);
    r = await api('PUT', '/api/national/member-db', { dryRun: true, paused: true, reason: null });
    check('member db: "Say why it\'s paused"', r.status === 400 && r.json.detail === "Say why it's paused", r);
    r = await api('PUT', '/api/national/member-db', { dryRun: true, paused: false, reason: null });
    check('member db: saved, projectsEnabled kept when left out', r.status === 200 && r.json.projectsEnabled === state.memberDb.projectsEnabled, r);
    r = await api('POST', '/api/national/member-db/pull');
    check('member db: pull is completed or unchanged', r.status === 200 && ['completed', 'unchanged'].includes(r.json.status) && has(r.json, ['counts', 'drift', 'error']), r);
    r = await api('POST', `/api/national/member-db/writes/${none}/retry`);
    check('member db: 404 "Nothing to write for them"', r.status === 404 && r.json.detail === 'Nothing to write for them', r);
    r = await api('POST', `/api/national/member-db/projects/${none}/retry`);
    check('member db: 404 "Nothing to write for that project"', r.status === 404 && /Nothing to write for that project/.test(r.json.detail), r);
    r = await api('GET', `/api/national/member-db/writes/${state.me.personId}/preview`);
    check('member db: a person\'s preview has newVolunteer, fields, notes', r.status === 200 ? has(r.json, ['newVolunteer', 'fields', 'notes']) : r.status === 409, r);

    r = await api('GET', '/api/national/agreement-templates');
    check('templates: templates, connected, sending', r.status === 200 && has(r.json, ['templates', 'connected', 'sending']), r);
    r = await api('POST', '/api/national/agreement-templates', { kind: 'nda', name: 'X', documensoTemplateId: 'envelope_x' });
    check('templates: "Kind is volunteer, code_of_conduct or project"', r.status === 400 && r.json.detail === 'Kind is volunteer, code_of_conduct or project', r);
    r = await api('POST', `/api/national/agreement-templates/${none}/deactivate`);
    check('templates: 404 "No such template"', r.status === 404 && r.json.detail === 'No such template', r);

    // ---- 6.4 Projects
    r = await api('GET', '/api/project-roles');
    state.roles = r.json || [];
    check('project roles: 200, a list', r.status === 200 && Array.isArray(r.json), r);
    r = await api('GET', '/api/chapters/umd/terms');
    check('terms: 200, a list', r.status === 200 && Array.isArray(r.json), r);
    r = await api('GET', '/api/partners');
    check('partners: 200, a list', r.status === 200 && Array.isArray(r.json), r);
    r = await api('POST', '/api/national/partners', { name: `Blackbox Partner ${ts}` });
    check('partners: national adds one', r.status >= 200 && r.status < 300, r);
    state.partner = r.json;
    r = await api('POST', '/api/national/partners', { name: `BLACKBOX partner ${ts}` });
    check('partners: the same name in another case is 409', r.status === 409, r);
    r = await api('GET', '/api/chapters/umd/projects');
    check('projects: 200, a list', r.status === 200 && Array.isArray(r.json), r);
    const slug = `bb-${ts}`;
    r = await api('POST', '/api/chapters/umd/projects', { name: `Blackbox ${ts}`, slug, tags: ['Test'] });
    check('projects: created (201) as a draft with standard resources', r.status === 201 && r.json.status === 'draft' && r.json.resources.length > 0, r);
    r = await api('POST', '/api/chapters/umd/projects', { name: `Blackbox again ${ts}`, slug, tags: [] });
    check('projects: a taken short name is 409', r.status === 409, r);
    r = await api('POST', '/api/chapters/umd/projects', { name: ' ', tags: [] });
    check('projects: a blank name is 400', r.status === 400, r);
    r = await api('GET', `/api/chapters/umd/projects/${slug}`);
    check('projects: detail has members and resources', r.status === 200 && Array.isArray(r.json.members) && Array.isArray(r.json.resources), r);
    r = await api('GET', '/api/chapters/umd/projects/no-such-project');
    check('projects: unknown is 404', r.status === 404, r);
    r = await api('POST', `/api/chapters/umd/projects/${slug}/status`, { status: 'active' });
    check('projects: can\'t start without a partner', r.status >= 400 && r.status < 500, r);
    r = await api('GET', `/api/chapters/umd/projects/${slug}/candidates`);
    check('projects: candidates listed', r.status === 200 && Array.isArray(r.json), r);
    const candidate = (r.json || [])[0];
    const role = state.roles[0];
    if (candidate && role) {
      const personId = candidate.personId || candidate.id;
      r = await api('POST', `/api/chapters/umd/projects/${slug}/members`, { personId, roleId: role.id });
      check('team: someone added', r.status === 200 && r.json.members.length === 1, r);
      r = await api('POST', `/api/chapters/umd/projects/${slug}/members`, { personId, roleId: role.id });
      check('team: adding them again is 409', r.status === 409, r);
      r = await api('GET', `/api/chapters/umd/members/${personId}/access`);
      check('team: the member\'s access answers 200', r.status === 200, r);
      r = await api('DELETE', `/api/chapters/umd/projects/${slug}/members/${personId}`);
      check('team: removed', r.status === 200 && r.json.members.filter(m => !m.removedAt).length === 0, r);
      r = await api('DELETE', `/api/chapters/umd/projects/${slug}/members/${personId}`);
      check('team: removing them again is 404', r.status === 404, r);
    } else check('team: a candidate and a role to test with', false, { candidate, role });
    r = await api('GET', `/api/chapters/umd/projects/${slug}/activity`);
    check('projects: activity has items', r.status === 200 && Array.isArray(r.json.items) && r.json.items.length > 0, r);
    r = await api('GET', '/api/chapters/umd/sync');
    check('chapter sync: 200', r.status === 200, r);
    r = await api('POST', `/api/chapters/umd/projects/${slug}/status`, { status: 'closed' });
    check('projects: closed', r.status === 200 && r.json.status === 'closed', r);
    r = await api('PUT', `/api/chapters/umd/projects/${slug}`, { name: 'Renamed', tags: [] });
    check('projects: a closed project can\'t change (409)', r.status === 409, r);

    // ---- CSRF
    r = await api('POST', '/api/chapters/umd/roster', { csv: 'x' }, { noCsrf: true });
    check('csrf: a signed-in write without the token is 403', r.status === 403, r);
    return bb.summary();
  };

  // One applicant at a time (one cookie): send a code first, read it from Mailpit, then call this.
  bb.sendCode = async email => (await bb.api('POST', '/api/apply/chapters/umd/code', { email })).status;
  bb.startApplicant = async (email, code, first) => {
    const { api, check } = bb;
    let r = await api('POST', '/api/apply/chapters/umd/verify', { email, code });
    check(`applicant ${first}: verified`, r.status === 200 && r.json.kind === 'new' && r.json.status === 'started', r);
    r = await api('PUT', '/api/apply/me', { details: { firstName: first, lastName: 'Blackbox', personalEmail: `${first.toLowerCase()}@personal.test`, major: 'CS', degreeType: "Bachelor's", graduationSeason: 'Fall', graduationYear: 2028 }, answers: {} });
    check(`applicant ${first}: saved, only GitHub (and any agreements) missing`, r.status === 200 && r.json.missing.includes('your GitHub account') && !r.json.missing.includes('your name'), r.json && r.json.missing);
    return r.json;
  };
  bb.submit = async first => {
    const r = await bb.api('POST', '/api/apply/me/submit');
    bb.check(`applicant ${first}: sent`, r.status === 200 && r.json.status === 'submitted', r);
    const again = await bb.api('POST', '/api/apply/me/submit');
    bb.check(`applicant ${first}: sending again is 409 "This application was already sent"`, again.status === 409 && again.json.detail === 'This application was already sent', again);
    return r.json;
  };
  bb.decide = async (first, how) => {
    const { api, check } = bb;
    let r = await api('GET', '/api/chapters/umd/registrations');
    const reg = (r.json || []).find(x => x.name && x.name.startsWith(first) || x.firstName === first);
    check(`${how} ${first}: the application waits in the lead's list`, reg && reg.status === 'submitted', (r.json || []).slice(0, 2));
    if (!reg) return null;
    const base = `/api/chapters/umd/registrations/${reg.id}`;
    r = await api('GET', '/api/chapters/umd');
    check(`${how} ${first}: the chapter counts it as waiting`, r.json.stats.waitingApplications >= 1, r.json.stats);
    if (how === 'approve') {
      r = await api('POST', `${base}/approve`, { personId: '00000000-0000-4000-8000-000000000000', secondAccount: false });
      check('approve: 400 for a person who isn\'t a match', r.status === 400 && r.json.detail === 'Approve as a new person or as one of the matches', r);
      r = await api('POST', `/api/chapters/gt/registrations/${reg.id}/approve`, {});
      check('approve: 404 "Not this chapter\'s" through another chapter', r.status === 404 && r.json.detail === "Not this chapter's", r);
      r = await api('POST', `${base}/approve`, {});
      check('approve: 200 with the updated list', r.status === 200 && (r.json.find(x => x.id === reg.id) || {}).status === 'approved', r);
      r = await api('POST', `${base}/approve`, {});
      check('approve: again is 409 "This application is approved"', r.status === 409 && r.json.detail === 'This application is approved', r);
      r = await api('POST', `${base}/decline`, { reason: 'Too late' });
      check('approve: declining it afterwards is 409', r.status === 409, r);
      r = await api('GET', '/api/chapters/umd/members');
      check('approve: they are in the members list', (r.json || []).some(m => m.name && m.name.startsWith(first)), (r.json || []).map(m => m.name));
    } else {
      r = await api('POST', `${base}/decline`, { reason: '  ' });
      check('decline: a blank reason is 400 with the documented text', r.status === 400 && r.json.detail === 'Say why: the applicant gets the reason by email (PRD)', r);
      r = await api('POST', `${base}/decline`, { reason: 'Full this semester (black-box)' });
      check('decline: 200 with the updated list', r.status === 200 && (r.json.find(x => x.id === reg.id) || {}).status === 'declined', r);
      r = await api('POST', `${base}/decline`, { reason: 'Again' });
      check('decline: again is 409 "This application is declined"', r.status === 409 && r.json.detail === 'This application is declined', r);
      r = await api('POST', `${base}/approve`, {});
      check('decline: approving it afterwards is 409', r.status === 409, r);
    }
    r = await api('GET', '/api/apply/me');
    check(`${how} ${first}: the applicant's session has ended (401)`, r.status === 401, r);
    return reg.id;
  };
  bb.openLink = async (kind, token) => {
    const { api, check } = bb;
    let r = await api('POST', `/api/apply/${kind}`, { token });
    check(`${kind} link: opens a pre-filled ${kind} application`, r.status === 200 && r.json.kind === kind && r.json.details && r.json.details.firstName, r);
    const first = r.json;
    r = await api('POST', `/api/apply/${kind}`, { token });
    if (kind === 'claim') check('claim link: a second opening is 410 "This link was already used…"', r.status === 410 && /already used/.test(r.json.detail), r);
    else check('roster link: opening again picks up the same application', r.status === 200 && r.json.id === first.id, r);
    await api('POST', '/api/apply/signout');
    return first;
  };
})();
'loaded';
