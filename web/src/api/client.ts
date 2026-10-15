import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

export type Me = components['schemas']['Me']
export type ChapterOverview = components['schemas']['ChapterOverview']
export type MemberRow = components['schemas']['MemberRow']
export type QueueTask = components['schemas']['QueueTask']
export type ApplicantView = components['schemas']['ApplicantView']
export type ApplyChapter = components['schemas']['ApplyChapter']
export type RegistrationSummary = components['schemas']['RegistrationSummary']
export type RegistrationSettings = components['schemas']['RegistrationSettings']
export type RosterInviteView = components['schemas']['RosterInviteView']
export type ClaimPreview = components['schemas']['ClaimPreview']
export type MemberHome = components['schemas']['MemberHome']
export type ToolInfo = components['schemas']['ToolInfo']
export type MemberDbStatus = components['schemas']['MemberDbStatus']
export type MemberDbPreview = components['schemas']['MemberDbPreview']
export type AgreementTemplates = components['schemas']['AgreementTemplates']
export type ApplicantAgreement = components['schemas']['ApplicantAgreement']

/** Typed client for the portal API; types come from web/openapi.json (`npm run gen:api`). */
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: 'same-origin',
  // Look fetch up on each call (not once at startup), so tests can swap it.
  fetch: (request) => globalThis.fetch(request),
})

// Spring Security keeps the CSRF token in the XSRF-TOKEN cookie and expects it back on anything but GET.
api.use({
  onRequest({ request }) {
    if (request.method !== 'GET') {
      const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
      if (token) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(token))
    }
    return request
  },
})

/** The server's reason for refusing a request (problem details), or a plain fallback. */
export function reason(error: unknown, response: Response, fallback = 'That didn\'t work.') {
  const detail = error && typeof error === 'object' && 'detail' in error ? (error as { detail?: unknown }).detail : undefined
  if (typeof detail === 'string' && detail) return detail
  if (response.status === 403) return 'Only chapter leads and co-leads can change this.'
  return `${fallback} (HTTP ${response.status})`
}

/** Starts Google sign-in (a full-page redirect through Spring Security). */
export const signIn = () => window.location.assign('/oauth2/authorization/google')

export async function signOut() {
  const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
  await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {} })
  window.location.assign('/')
}
