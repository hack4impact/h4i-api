import { describe, expect, it } from 'vitest'
import type { MemberRow } from '../api/client'
import { filterMembers } from './MembersTable'

const member = (name: string, over: Partial<MemberRow> = {}): MemberRow => ({
  personId: name, name, email: `${name.toLowerCase()}@hack4impact.org`, status: 'active', kind: 'student',
  chapterRole: null, title: null, accounts: [], claimed: false, claimable: false, claimLinkSentAt: null, ...over,
})

describe('filterMembers', () => {
  const all = [member('Lena', { chapterRole: 'lead' }), member('Ada'), member('Alan', { status: 'alumni', email: null })]

  it('matches name or email, case-insensitively', () => {
    expect(filterMembers(all, 'ADA', '', '').map((m) => m.name)).toEqual(['Ada'])
    expect(filterMembers(all, 'lena@', '', '').map((m) => m.name)).toEqual(['Lena'])
  })

  it('filters by status and by chapter role, including "no role"', () => {
    expect(filterMembers(all, '', 'alumni', '').map((m) => m.name)).toEqual(['Alan'])
    expect(filterMembers(all, '', '', 'lead').map((m) => m.name)).toEqual(['Lena'])
    expect(filterMembers(all, '', '', 'none').map((m) => m.name)).toEqual(['Ada', 'Alan'])
  })
})
